package org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.application.Application;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.application.ReadAction;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.AsyncFileListener;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.impl.VirtualFileManagerImpl;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import org.jetbrains.kotlin.com.intellij.util.containers.CollectionFactory;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.com.intellij.util.containers.HashingStrategy;

import java.util.*;
import java.util.concurrent.TimeUnit;

import kotlin.Unit;

@ApiStatus.Internal
public final class AsyncEventSupport {
  private static final Logger LOG = Logger.getInstance(AsyncEventSupport.class);

  @ApiStatus.Internal
  public static final ExtensionPointName<AsyncFileListener> EP_NAME = new ExtensionPointName<>("com.intellij.vfs.asyncListener");

  // VFS events could be fired with any unpredictable nesting.
  // One may fire new events (for example, using `syncPublisher()`) while processing current events in `before()` or `after()`.
  // So, we cannot rely on listener's execution and nesting order in this case and have to explicitly mark events
  // that are supposed to be processed asynchronously.
  private static final @NotNull Set<List<? extends VFileEvent>> ourAsyncProcessedEvents =
    CollectionFactory.createCustomHashingStrategySet(HashingStrategy.identity());
  private static final @NotNull Map<List<? extends VFileEvent>, List<AsyncFileListener.ChangeApplier>> ourAppliers =
    CollectionFactory.createSmallMemoryFootprintMap(1);

  public static void startListening() {
    Application app = ApplicationManager.getApplication();
    Disposer.register(app, AsyncEventSupport::ensureAllEventsProcessed);

    app.getMessageBus().connect().subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
      @Override
      public void before(@NotNull List<? extends @NotNull VFileEvent> events) {
        if (ourAsyncProcessedEvents.contains(events)) {
          return;
        }
        List<AsyncFileListener.ChangeApplier> appliers = runAsyncListeners(events);
        ourAppliers.put(events, appliers);
        beforeVfsChange(appliers);
      }

      @Override
      public void after(@NotNull List<? extends @NotNull VFileEvent> events) {
        if (ourAsyncProcessedEvents.contains(events)) {
          return;
        }
        List<AsyncFileListener.ChangeApplier> appliers = ourAppliers.remove(events);
        afterVfsChange(appliers);
      }
    });
  }

  private static void ensureAllEventsProcessed() {
    LOG.assertTrue(ourAsyncProcessedEvents.isEmpty(), "Some VFS events were not properly processed " + ourAsyncProcessedEvents);
    LOG.assertTrue(ourAppliers.isEmpty(), "Some VFS events were not processed after VFS change performed " + ourAppliers);
  }

  static @NotNull List<AsyncFileListener.ChangeApplier> runAsyncListeners(@NotNull List<? extends VFileEvent> events) {
//    throw new UnsupportedOperationException();
      if (events.isEmpty()) {
          return Collections.emptyList();
      }

    if (LOG.isDebugEnabled()) {
      LOG.debug("Processing " + events);
    }

    List<AsyncFileListener.ChangeApplier> appliers = new ArrayList<>();
    List<AsyncFileListener> allListeners = new ArrayList<>(EP_NAME.getExtensionList());
    ((VirtualFileManagerImpl)VirtualFileManager.getInstance()).addAsyncFileListenersTo(allListeners);
    for (AsyncFileListener listener : allListeners) {
      ProgressManager.checkCanceled();
      long startNs = System.nanoTime();
      boolean canceled = false;
      try {
        ReadAction.compute(() -> {
          ContainerUtil.addIfNotNull(appliers, listener.prepareChange(events));
          return Unit.INSTANCE;
        });
      }
      catch (ProcessCanceledException e) {
        canceled = true;
        throw e;
      }
      catch (Throwable e) {
        LOG.error(e);
      }
      finally {
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
        if (elapsedMs > 10_000) {
          LOG.warn(listener + " took too long (" + elapsedMs + "ms) on " + events.size() + " events" + (canceled ? ", canceled" : ""));
        }
      }
    }
    return appliers;
  }

  public static void markAsynchronouslyProcessedEvents(@NotNull List<? extends VFileEvent> events) {
    ourAsyncProcessedEvents.add(events);
  }

  public static void unmarkAsynchronouslyProcessedEvents(@NotNull List<? extends VFileEvent> events) {
    LOG.assertTrue(ourAsyncProcessedEvents.remove(events));
  }

  private static void beforeVfsChange(@NotNull List<? extends AsyncFileListener.ChangeApplier> appliers) {
    for (AsyncFileListener.ChangeApplier applier : appliers) {
//      PingProgress.interactWithEdtProgress();
      try {
        applier.beforeVfsChange();
      }
      catch (Throwable e) {
        LOG.error(e);
      }
    }
  }

  private static void afterVfsChange(@NotNull List<? extends AsyncFileListener.ChangeApplier> appliers) {
    for (AsyncFileListener.ChangeApplier applier : appliers) {
//      PingProgress.interactWithEdtProgress();
      try {
        applier.afterVfsChange();
      }
      catch (ProcessCanceledException e) {
        throw e;
      }
      catch (Throwable e) {
        LOG.error(e);
      }
    }
  }

//  static void processEventsFromRefresh(@NotNull List<? extends CompoundVFileEvent> events,
//                                       @NotNull List<? extends AsyncFileListener.ChangeApplier> appliers,
//                                       boolean asyncProcessing) {
//    ApplicationManager.getApplication().assertWriteAccessAllowed();
//    beforeVfsChange(appliers);
//    try {
//      ((PersistentFSImpl)PersistentFS.getInstance()).processEventsImpl(events, asyncProcessing);
//    }
//    catch (Throwable e) {
//      LOG.error(e);
//    }
//    finally {
//      afterVfsChange(appliers);
//    }
//  }
}