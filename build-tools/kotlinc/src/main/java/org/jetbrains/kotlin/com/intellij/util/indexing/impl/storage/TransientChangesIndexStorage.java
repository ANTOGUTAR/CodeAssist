package org.jetbrains.kotlin.com.intellij.util.indexing.impl.storage;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.concurrency.ConcurrentCollectionFactory;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.util.Processor;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexEx;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexExtension;
import org.jetbrains.kotlin.com.intellij.util.indexing.ID;
import org.jetbrains.kotlin.com.intellij.util.indexing.IdFilter;
import org.jetbrains.kotlin.com.intellij.util.indexing.StorageException;
import org.jetbrains.kotlin.com.intellij.util.indexing.ValueContainer;
import org.jetbrains.kotlin.com.intellij.util.indexing.VfsAwareIndexStorage;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.ChangeTrackingValueContainer;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.IndexDebugProperties;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.IndexStorage;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.IndexStorageUtil;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.UpdatableValueContainer;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This storage is needed for indexing yet unsaved data without saving those changes to 'main' backend storage
 */
public class TransientChangesIndexStorage<Key, Value> implements VfsAwareIndexStorage<Key, Value> {
  private static final Logger LOG = Logger.getInstance(TransientChangesIndexStorage.class);
  private final Map<Key, TransientChangeTrackingValueContainer<Value>> myMap;
  @NonNull
  private final VfsAwareIndexStorage<Key, Value> myBackendStorage;
  private final List<BufferingStateListener> myListeners = ContainerUtil.createLockFreeCopyOnWriteList();
  @NonNull
  private final ID<?, ?> myIndexId;
  private boolean myBufferingEnabled;

  public interface BufferingStateListener {
    void bufferingStateChanged(boolean newState);

    void memoryStorageCleared();
  }

  public TransientChangesIndexStorage(@NonNull IndexStorage<Key, Value> backend, @NonNull FileBasedIndexExtension<Key, Value> extension) {
    myBackendStorage = (VfsAwareIndexStorage<Key, Value>)backend;
    myIndexId = extension.getName();
    myMap = ConcurrentCollectionFactory.createConcurrentMap(IndexStorageUtil.adaptKeyDescriptorToStrategy(extension.getKeyDescriptor()));
  }

  @NonNull
  VfsAwareIndexStorage<Key, Value> getBackendStorage() {
    return myBackendStorage;
  }

  public void addBufferingStateListener(@NonNull BufferingStateListener listener) {
    myListeners.add(listener);
  }

  public void setBufferingEnabled(boolean enabled) {
    final boolean wasEnabled = myBufferingEnabled;
    LOG.assertTrue(wasEnabled != enabled);

    myBufferingEnabled = enabled;
    for (BufferingStateListener listener : myListeners) {
      listener.bufferingStateChanged(enabled);
    }
  }

  public boolean clearMemoryMap() {
    boolean modified = !myMap.isEmpty();
    myMap.clear();
    return modified;
  }

  public boolean clearMemoryMapForId(Key key, int fileId) {
    TransientChangeTrackingValueContainer<Value> container = myMap.get(key);
    if (container != null) {
      container.dropAssociatedValue(fileId);
      return true;
    }
    return false;
  }

  public void fireMemoryStorageCleared() {
    for (BufferingStateListener listener : myListeners) {
      listener.memoryStorageCleared();
    }
  }

  @Override
  public void clearCaches() {
    try {
        if (myMap.size() == 0) {
            return;
        }

      if (IndexDebugProperties.DEBUG) {
        String message = "Dropping caches for " + myIndexId + ", number of items:" + myMap.size();
        ((FileBasedIndexEx) FileBasedIndex.getInstance()).getLogger().info(message);
      }

      for (ChangeTrackingValueContainer<Value> v : myMap.values()) {
        v.dropMergedData();
      }
    } finally {
      myBackendStorage.clearCaches();
    }
  }

  @Override
  public void close() throws StorageException {
    myBackendStorage.close();
  }

  @Override
  public void clear() throws StorageException {
    clearMemoryMap();
    myBackendStorage.clear();
  }

  @Override
  public void flush() throws IOException {
    myBackendStorage.flush();
  }

  @Override
  public boolean processKeys(@NonNull final Processor<? super Key> processor, GlobalSearchScope scope, IdFilter idFilter) throws StorageException {
    final Set<Key> stopList = new HashSet<>();

    Processor<Key> decoratingProcessor = key -> {
        if (stopList.contains(key)) {
            return true;
        }

      final UpdatableValueContainer<Value> container = myMap.get(key);
      if (container != null && container.size() == 0) {
        return true;
      }
      return processor.process(key);
    };

    for (Key key : myMap.keySet()) {
      if (!decoratingProcessor.process(key)) {
        return false;
      }
      stopList.add(key);
    }
    return myBackendStorage.processKeys(stopList.isEmpty() ? processor : decoratingProcessor, scope, idFilter);
  }

  @Override
  public void addValue(final Key key, final int inputId, final Value value) throws StorageException {
    if (myBufferingEnabled) {
      getMemValueContainer(key).addValue(inputId, value);
      return;
    }
    final ChangeTrackingValueContainer<Value> valueContainer = myMap.get(key);
    if (valueContainer != null) {
      valueContainer.dropMergedData();
    }

    myBackendStorage.addValue(key, inputId, value);
  }

  @Override
  public void removeAllValues(@NonNull Key key, int inputId) throws StorageException {
    if (myBufferingEnabled) {
      getMemValueContainer(key).removeAssociatedValue(inputId);
      return;
    }
    final ChangeTrackingValueContainer<Value> valueContainer = myMap.get(key);
    if (valueContainer != null) {
      valueContainer.dropMergedData();
    }

    myBackendStorage.removeAllValues(key, inputId);
  }

  private UpdatableValueContainer<Value> getMemValueContainer(final Key key) {
    return myMap.computeIfAbsent(key, k -> {
      return new TransientChangeTrackingValueContainer<>(() -> {
        try {
          return myBackendStorage.read(key);
        }
        catch (StorageException e) {
          throw new RuntimeException(e);
        }
      });
    });
  }

  @Override
  @NonNull
  public ValueContainer<Value> read(final Key key) throws StorageException {
    final ValueContainer<Value> valueContainer = myMap.get(key);
    if (valueContainer != null) {
      return valueContainer;
    }

    return myBackendStorage.read(key);
  }

  @Override
  public int keysCountApproximately() {
    //RC: this imprecise upper bound -- some keys counted twice since they present in both transient
    //    and persistent storage
    return myMap.size() + myBackendStorage.keysCountApproximately();
  }
}