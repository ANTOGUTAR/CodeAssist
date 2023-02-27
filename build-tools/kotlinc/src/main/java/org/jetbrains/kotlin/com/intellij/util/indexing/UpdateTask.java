package org.jetbrains.kotlin.com.intellij.util.indexing;

import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.util.concurrency.Semaphore;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public abstract class UpdateTask<Type> {
  private final Semaphore myUpdateSemaphore = new Semaphore();
  private final Set<Type> myItemsBeingIndexed = Collections.synchronizedSet(new HashSet<>());
  private static final boolean DEBUG = false;

  final boolean processAll(Collection<? extends Type> itemsToProcess, Project project) {
      if (DEBUG) {
          trace("enter processAll");
      }
    try {
      boolean hasMoreToProcess;
      boolean allItemsProcessed = true;

      do {
        hasMoreToProcess = false;
          if (DEBUG) {
              trace("observing " + itemsToProcess.size());
          }
        // todo we can decrease itemsToProcess
        for (Type item : itemsToProcess) {
          myUpdateSemaphore.down();

          try {
              if (DEBUG) {
                  trace("about to process");
              }
            boolean processed = process(item, project);
              if (DEBUG) {
                  trace(processed ? "processed " : "skipped");
              }

            if (!processed) {
              hasMoreToProcess = true;
              allItemsProcessed = false;
            }
          }
          finally {
            myUpdateSemaphore.up();
          }
          ProgressManager.checkCanceled();
        }

        do {
          ProgressManager.checkCanceled();
        }
        while (!myUpdateSemaphore.waitFor(500));
          if (DEBUG) {
              if (hasMoreToProcess) {
                  trace("reiterating");
              }
          }
      }
      while (hasMoreToProcess);

      return allItemsProcessed;
    } finally {
        if (DEBUG) {
            trace("exits processAll");
        }
    }
  }

  private boolean process(Type item, Project project) {
    if (myItemsBeingIndexed.add(item)) {
      try {
        doProcess(item, project);
        return true;
      } finally {
        myItemsBeingIndexed.remove(item);
      }
    }
    return false;
  }

  public abstract void doProcess(Type item, Project project);

  protected static void trace(String s) {
    System.out.println(Thread.currentThread() + " " + s);
  }
}