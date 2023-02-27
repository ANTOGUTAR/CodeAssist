package org.jetbrains.kotlin.com.intellij.psi.stubs;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.kotlin.com.intellij.psi.impl.DebugUtil;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexEx;
import org.jetbrains.kotlin.com.intellij.util.indexing.StorageException;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.DirectInputDataDiffBuilder;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.IndexDebugProperties;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.KeyValueUpdateProcessor;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.RemovedKeyProcessor;

import java.util.*;

class StubCumulativeInputDiffBuilder extends DirectInputDataDiffBuilder<Integer, SerializedStubTree> {
  private static final Logger LOG = Logger.getInstance(SerializedStubTree.class);
  @Nullable
  private final SerializedStubTree myCurrentTree;

  StubCumulativeInputDiffBuilder(int inputId, @Nullable SerializedStubTree currentTree) {
    super(inputId);
    myCurrentTree = currentTree;
  }

  @Override
  public boolean differentiate(@NotNull Map<Integer, SerializedStubTree> newData,
                               @NotNull KeyValueUpdateProcessor<? super Integer, ? super SerializedStubTree> addProcessor,
                               @NotNull KeyValueUpdateProcessor<? super Integer, ? super SerializedStubTree> updateProcessor,
                               @NotNull RemovedKeyProcessor<? super Integer> removeProcessor) throws StorageException
  {
    return differentiate(newData, addProcessor, updateProcessor, removeProcessor, false);
  }

  /**
   * @param dryRun if true, won't update the stub indices
   */
  public boolean differentiate(@NotNull Map<Integer, SerializedStubTree> newData,
                               @NotNull KeyValueUpdateProcessor<? super Integer, ? super SerializedStubTree> addProcessor,
                               @NotNull KeyValueUpdateProcessor<? super Integer, ? super SerializedStubTree> updateProcessor,
                               @NotNull RemovedKeyProcessor<? super Integer> removeProcessor,
                               boolean dryRun) throws StorageException {
    if (!newData.isEmpty()) {
      SerializedStubTree newSerializedStubTree = newData.values().iterator().next();
      if (myCurrentTree != null) {
          if (treesAreEqual(newSerializedStubTree, myCurrentTree)) {
              return false;
          }
        removeProcessor.process(myInputId, myInputId);
      }
      addProcessor.process(myInputId, newSerializedStubTree, myInputId);
        if (!dryRun) {
            updateStubIndices(newSerializedStubTree);
        }
    }
    else {
        if (myCurrentTree == null) {
            return false; // ?????????
        }
      removeProcessor.process(myInputId, myInputId);
        if (!dryRun) {
            updateStubIndices(null);
        }
    }
    return true;
  }

  @Nullable
  SerializedStubTree getSerializedStubTree() {
    return myCurrentTree;
  }

  @Override
  public @NotNull Collection<Integer> getKeys() {
    return myCurrentTree != null ? Collections.singleton(myInputId) : Collections.emptySet();
  }

  private static boolean treesAreEqual(@NotNull SerializedStubTree newSerializedStubTree,
                                       @NotNull SerializedStubTree currentTree) {
    return Arrays.equals(currentTree.getTreeHash(), newSerializedStubTree.getTreeHash()) &&
           treesAreReallyEqual(newSerializedStubTree, currentTree);
  }

  private static boolean treesAreReallyEqual(@NotNull SerializedStubTree newSerializedStubTree,
                                             @NotNull SerializedStubTree currentTree) {
    if (newSerializedStubTree.equals(currentTree)) {
      return true;
    }
    if (IndexDebugProperties.DEBUG) {
      reportStubTreeHashCollision(newSerializedStubTree, currentTree);
    }
    return false;
  }

  private void updateStubIndices(@Nullable SerializedStubTree newTree) {
    try {
      Map<StubIndexKey<?, ?>, Map<Object, StubIdList>> oldForwardIndex =
        myCurrentTree == null ? Collections.emptyMap() : myCurrentTree.getStubIndicesValueMap();

      Map<StubIndexKey<?, ?>, Map<Object, StubIdList>> newForwardIndex =
        newTree == null ? Collections.emptyMap() : newTree.getStubIndicesValueMap();

      Collection<StubIndexKey<?, ?>> affectedIndexes =
        ContainerUtil.union(oldForwardIndex.keySet(), newForwardIndex.keySet());

      StubIndexEx stubIndex = (StubIndexEx)StubIndex.getInstance();
      if (FileBasedIndexEx.DO_TRACE_STUB_INDEX_UPDATE) {
        stubIndex.getLogger()
          .info("stub indexes " + (newTree == null ? "deletion" : "update") + ": file = " + myInputId + " indexes " + affectedIndexes);
      }

      //noinspection rawtypes
      for (StubIndexKey key : affectedIndexes) {
        // StubIdList-s are ignored.
        Set<Object> oldKeys = oldForwardIndex.getOrDefault(key, Collections.emptyMap()).keySet();
        Set<Object> newKeys = newForwardIndex.getOrDefault(key, Collections.emptyMap()).keySet();

        //noinspection unchecked
        stubIndex.updateIndex(key, myInputId, oldKeys, newKeys);
      }
    } catch (ProcessCanceledException e) {
      LOG.error("ProcessCanceledException is not expected here", e);
      throw e;
    }
  }

  private static void reportStubTreeHashCollision(@NotNull SerializedStubTree newTree,
                                                  @NotNull SerializedStubTree existingTree) {
    String oldTreeDump = "\nexisting tree " + dumpStub(existingTree);
    String newTreeDump = "\nnew tree " + dumpStub(newTree);
    byte[] hash = newTree.getTreeHash();
    LOG.info("Stub tree hashing collision. " +
             "Different trees have the same hash = " + StringUtil.toHexString(hash) + ". " +
             oldTreeDump + newTreeDump, new Exception());
  }

  @NotNull
  private static String dumpStub(@NotNull SerializedStubTree tree) {
    String deserialized;
    try {
      deserialized = "stub: " + DebugUtil.stubTreeToString(tree.getStub());
    }
    catch (SerializerNotFoundException e) {
      LOG.error(e);
      deserialized = "error while stub deserialization: " + e.getMessage();
    }
    return deserialized + "\n bytes: " + StringUtil.toHexString(tree.myTreeBytes);
  }
}