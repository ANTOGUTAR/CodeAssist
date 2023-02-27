package org.jetbrains.kotlin.com.intellij.util.indexing.contentQueue;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.application.NonBlockingReadAction;
import org.jetbrains.kotlin.com.intellij.openapi.application.ReadAction;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileTypeRegistry;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicator;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.progress.Task;
import org.jetbrains.kotlin.com.intellij.openapi.progress.WrappedProgressIndicator;
import org.jetbrains.kotlin.com.intellij.openapi.progress.impl.ProgressSuspender;
import org.jetbrains.kotlin.com.intellij.openapi.progress.util.ProgressIndicatorUtils;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.EmptyRunnable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Ref;
import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtilRt;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.InvalidVirtualFileAccessException;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VfsUtilCore;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.impl.jar.CoreJarFileSystem;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.impl.CachedFileType;
import org.jetbrains.kotlin.com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.com.intellij.util.indexing.CoreFileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexImpl;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileIndexesValuesApplier;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexingFlag;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexingStamp;
import org.jetbrains.kotlin.com.intellij.util.indexing.roots.IndexableFilesDeduplicateFilter;

import java.io.FileNotFoundException;
import java.nio.file.NoSuchFileException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@ApiStatus.Internal
public final class IndexUpdateRunner {
    private static final Logger LOG = Logger.getInstance(IndexUpdateRunner.class);

    private static final long SOFT_MAX_TOTAL_BYTES_LOADED_INTO_MEMORY = 20 * FileUtilRt.MEGABYTE;

    private static final CopyOnWriteArrayList<IndexingJob> ourIndexingJobs =
            new CopyOnWriteArrayList<>();

    private static final ExecutorService GLOBAL_INDEXING_EXECUTOR =
            AppExecutorUtil.createBoundedApplicationPoolExecutor("Indexing",
                    1);

    private final CoreFileBasedIndex myFileBasedIndex;

    private final ExecutorService myIndexingExecutor;

    private final int myNumberOfIndexingThreads;

    private final AtomicInteger myIndexingAttemptCount = new AtomicInteger();
    private final AtomicInteger myIndexingSuccessfulCount = new AtomicInteger();

    private final boolean WRITE_INDEXES_ON_SEPARATE_THREAD =
            Boolean.getBoolean("idea.write.indexes.on.separate.thread");
    private final ExecutorService myIndexWriteExecutor =
            WRITE_INDEXES_ON_SEPARATE_THREAD ? AppExecutorUtil.createBoundedApplicationPoolExecutor(
                    "Index Write Thread",
                    1) : null;

    /**
     * Memory optimization to prevent OutOfMemory on loading file contents.
     * <p>
     * "Soft" total limit of bytes loaded into memory in the whole application is
     * {@link #SOFT_MAX_TOTAL_BYTES_LOADED_INTO_MEMORY}.
     * It is "soft" because one (and only one) "indexable" file can exceed this limit.
     * <p>
     * "Indexable" file is any file for which {@link FileBasedIndexImpl#isTooLarge(VirtualFile)}
     * returns {@code false}.
     * Note that this method may return {@code false} even for relatively big files with size
     * greater than {@link #SOFT_MAX_TOTAL_BYTES_LOADED_INTO_MEMORY}.
     * This is because for some files (or file types) the size limit is ignored.
     * <p>
     * So in its maximum we will load {@code SOFT_MAX_TOTAL_BYTES_LOADED_INTO_MEMORY + <size of
     * not "too large" file>}, which seems acceptable,
     * because we have to index this "not too large" file anyway (even if its size is 4 Gb), and
     * {@code SOFT_MAX_TOTAL_BYTES_LOADED_INTO_MEMORY}
     * additional bytes are insignificant.
     */
    private static long ourTotalBytesLoadedIntoMemory = 0;
    private static final Lock ourLoadedBytesLimitLock = new ReentrantLock();
    private static final Condition ourLoadedBytesAreReleasedCondition =
            ourLoadedBytesLimitLock.newCondition();

    public IndexUpdateRunner(@NotNull CoreFileBasedIndex fileBasedIndex,
                             int numberOfIndexingThreads) {
        myFileBasedIndex = fileBasedIndex;
        myIndexingExecutor = GLOBAL_INDEXING_EXECUTOR;
        myNumberOfIndexingThreads = numberOfIndexingThreads;
    }

    /**
     * This exception contains indexing statistics accumulated by the time of a thrown exception.
     */
    public static class IndexingInterruptedException extends Exception {
        public IndexingInterruptedException(@NotNull Throwable cause) {
            super(cause);
        }
    }


    public static final class FileSet {
        public final String debugName;
        public final @Nullable String progressText;
        public final Collection<VirtualFile> files;

        public FileSet(@NotNull Project project,
                       @NotNull String debugName,
                       @NotNull Collection<VirtualFile> files) {
            this(project, debugName, files, null);
        }

        public FileSet(@NotNull Project project,
                       @NotNull String debugName,
                       @NotNull Collection<VirtualFile> files,
                       @Nullable String progressText) {
            this.debugName = debugName;
            this.files = files;
            this.progressText = progressText;
        }
    }

    public void indexFiles(@NotNull Project project,
                           @NotNull List<FileSet> fileSets,
                           @NotNull ProgressIndicator indicator) throws IndexingInterruptedException {
        long startTime = System.nanoTime();
        try {
            doIndexFiles(project, fileSets, indicator);
        } catch (RuntimeException e) {
            throw new IndexingInterruptedException(e);
        } finally {
            long visibleProcessingTime = System.nanoTime() - startTime;
//            long totalProcessingTimeInAllThreads = fileSets.stream().mapToLong(b -> b
//            .statistics.getProcessingTimeInAllThreads()).sum();
//            projectIndexingHistory.setVisibleTimeToAllThreadsTimeRatio
//            (totalProcessingTimeInAllThreads == 0
//                    ? 0 : ((double)visibleProcessingTime) / totalProcessingTimeInAllThreads);
            if (myIndexWriteExecutor != null) {
                ProgressIndicatorUtils.awaitWithCheckCanceled(myIndexWriteExecutor.submit(
                        EmptyRunnable.getInstance()));
            }
        }
    }

    private void doIndexFiles(@NotNull Project project,
                              @NotNull List<FileSet> fileSets,
                              @NotNull ProgressIndicator indicator) {
        if (ContainerUtil.and(fileSets, b -> b.files.isEmpty())) {
            return;
        }
        indicator.checkCanceled();
        indicator.setIndeterminate(false);

        CachedFileContentLoader contentLoader =
                new CurrentProjectHintedCachedFileContentLoader(project);
        ProgressIndicator originalIndicator = unwrapAll(indicator);
        ProgressSuspender originalSuspender = ProgressSuspender.getSuspender(originalIndicator);
        IndexingJob indexingJob = new IndexingJob(project,
                indicator,
                contentLoader,
                fileSets,
                originalIndicator,
                originalSuspender);
        if (ApplicationManager.getApplication().isWriteAccessAllowed()) {
            // If the current thread has acquired the write lock, we can't grant it to worker
            // threads, so we must do the work in the current thread.
            while (!indexingJob.areAllFilesProcessed()) {
                indexOneFileOfJob(indexingJob);
            }
        } else {
            ourIndexingJobs.add(indexingJob);
            try {
                AtomicInteger numberOfRunningWorkers = new AtomicInteger();
                Runnable worker = () -> {
                    try {
                        indexJobsFairly();
                    } finally {
                        numberOfRunningWorkers.decrementAndGet();
                    }
                };
                for (int i = 0; i < myNumberOfIndexingThreads; i++) {
                    myIndexingExecutor.execute(worker);
                    numberOfRunningWorkers.incrementAndGet();
                }
                while (!project.isDisposed() &&
                       !indexingJob.areAllFilesProcessed() &&
                       indexingJob.myError.get() == null) {
                    // Internally checks for suspension of the indexing and blocks the current
                    // thread if necessary.
                    indicator.checkCanceled();
                    // Add workers if the previous have stopped for whatever reason.
                    int toAddWorkersNumber =
                            myNumberOfIndexingThreads - numberOfRunningWorkers.get();
                    for (int i = 0; i < toAddWorkersNumber; i++) {
                        myIndexingExecutor.execute(worker);
                        numberOfRunningWorkers.incrementAndGet();
                    }
                    try {
                        if (indexingJob.myAllFilesAreProcessedLatch.await(100,
                                TimeUnit.MILLISECONDS)) {
                            break;
                        }
                    } catch (InterruptedException e) {
                        throw new ProcessCanceledException(e);
                    }
                }
                Throwable error = indexingJob.myError.get();
                if (error instanceof ProcessCanceledException) {
                    // original error has happened in a different thread. Make stacktrace easier
                    // to understand by wrapping PCE into PCE
                    ProcessCanceledException pce = new ProcessCanceledException();
                    pce.addSuppressed(error);
                    throw pce;
                }
                if (error != null) {
                    throw new RuntimeException("Indexing of " + project.getName() + " has failed",
                            error);
                }
            } finally {
                ourIndexingJobs.remove(indexingJob);
            }
        }
    }

    private static class FileIndexingJob {
        private final VirtualFile file;
        private final FileSet fileSet;

        public FileIndexingJob(VirtualFile file, FileSet fileSet) {
            this.file = file;
            this.fileSet = fileSet;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof FileIndexingJob)) {
                return false;
            }

            FileIndexingJob that = (FileIndexingJob) o;

            if (!Objects.equals(file, that.file)) {
                return false;
            }
            return Objects.equals(fileSet, that.fileSet);
        }

        @Override
        public int hashCode() {
            int result = file != null ? file.hashCode() : 0;
            result = 31 * result + (fileSet != null ? fileSet.hashCode() : 0);
            return result;
        }

        public VirtualFile getFile() {
            return file;
        }

        public FileSet getFileSet() {
            return fileSet;
        }
    }

    private static void releaseFile(VirtualFile file, long length) {
        signalThatFileIsUnloaded(length);
        IndexingStamp.flushCache(FileBasedIndex.getFileId(file));
        IndexingFlag.unlockFile(file);
    }

    private @NotNull ContentLoadingResult loadContent(@NotNull ProgressIndicator indicator,
                                                      @NotNull VirtualFile file,
                                                      @NotNull CachedFileContentLoader loader) throws TooLargeContentException, FailedToLoadContentException {

        if (myFileBasedIndex.isTooLarge(file)) {
            throw new TooLargeContentException(file);
        }

        long fileLength;
        try {
            fileLength = file.getLength();
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (Throwable e) {
            throw new FailedToLoadContentException(file, e);
        }

        // Reserve bytes for the file.
        waitForFreeMemoryToLoadFileContent(indicator, fileLength);

        try {
            CachedFileContent fileContent = loader.loadContent(file);
            return new ContentLoadingResult(fileContent, fileLength);
        } catch (Throwable e) {
            signalThatFileIsUnloaded(fileLength);
            throw e;
        }
    }

    // Index jobs one by one while there are some. Jobs may belong to different projects, and we
    // index them fairly.
    // Drops finished, cancelled and failed jobs from {@code ourIndexingJobs}. Does not throw
    // exceptions.
    private void indexJobsFairly() {
        while (!ourIndexingJobs.isEmpty()) {
            boolean allJobsAreSuspended = true;
            for (IndexingJob job : ourIndexingJobs) {
                ProgressIndicator jobIndicator = job.myIndicator;
                if (job.myProject.isDisposed() ||
                    job.myNoMoreFilesInQueue.get() ||
                    jobIndicator.isCanceled() ||
                    job.myError.get() != null) {
                    ourIndexingJobs.remove(job);
                    allJobsAreSuspended = false;
                    continue;
                }
                ProgressSuspender suspender = job.myOriginalProgressSuspender;
                if (suspender != null && suspender.isSuspended()) {
                    continue;
                }
                allJobsAreSuspended = false;
                try {
                    Runnable work = () -> indexOneFileOfJob(job);
                    if (suspender != null) {
                        // Here it is important to use the original progress indicator which is
                        // directly associated with the ProgressSuspender.
                        suspender.executeNonSuspendableSection(job.myOriginalProgressIndicator,
                                work);
                    } else {
                        work.run();
                    }
                } catch (Throwable e) {
                    job.myError.compareAndSet(null, e);
                    ourIndexingJobs.remove(job);
                }
            }
            if (allJobsAreSuspended) {
                // To avoid busy-looping.
                break;
            }
        }
    }

    private void indexOneFileOfJob(@NotNull IndexingJob indexingJob) throws ProcessCanceledException {
        long startTime = System.nanoTime();
        long contentLoadingTime;
        ContentLoadingResult loadingResult;

        FileIndexingJob fileIndexingJob = indexingJob.myQueueOfFiles.poll();
        if (fileIndexingJob == null) {
            indexingJob.myNoMoreFilesInQueue.set(true);
            return;
        }

        VirtualFile file = fileIndexingJob.file;
        try {
            // Propagate ProcessCanceledException and unchecked exceptions. The latter fail the
            // whole indexing (see IndexingJob.myError).
            loadingResult = loadContent(indexingJob.myIndicator, file, indexingJob.myContentLoader);
        } catch (ProcessCanceledException e) {
            indexingJob.myQueueOfFiles.add(fileIndexingJob);
            throw e;
        } catch (TooLargeContentException e) {
            indexingJob.oneMoreFileProcessed();
//            IndexingFileSetStatistics statistics = indexingJob.getStatistics(fileIndexingJob);
//            //noinspection SynchronizationOnLocalVariableOrMethodParameter
//            synchronized (statistics) {
//                statistics.addTooLargeForIndexingFile(e.getFile());
//            }
            CoreFileBasedIndex.LOG.info("File: " +
                                        e.getFile().getUrl() +
                                        " is too large for indexing");
            return;
        } catch (FailedToLoadContentException e) {
            indexingJob.oneMoreFileProcessed();
            logFailedToLoadContentException(e);
            return;
        } finally {
            contentLoadingTime = System.nanoTime() - startTime;
        }

        CachedFileContent fileContent = loadingResult.getContent();
        long length = loadingResult.fileLength;

        if (file.isDirectory()) {
            LOG.info("Directory was passed for indexing unexpectedly: " + file.getPath());
        }

        try {
            indexingJob.setLocationBeingIndexed(fileIndexingJob);
            @NotNull Supplier<@NotNull Boolean> fileTypeChangeChecker =
                    CachedFileType.getFileTypeChangeChecker();
            FileType type =
                    FileTypeRegistry.getInstance().getFileTypeByFile(file, fileContent.getBytes());
            FileIndexesValuesApplier applier = ReadAction.compute(() -> {
                Ref<FileIndexesValuesApplier> ref = new Ref<>();
                ProgressManager.getInstance().runInReadActionWithWriteActionPriority(() -> {
                    myIndexingAttemptCount.incrementAndGet();
                    FileType fileType = fileTypeChangeChecker.get() ? type : null;
                    ref.set(myFileBasedIndex.indexFileContent(indexingJob.myProject,
                            fileContent,
                            fileType));
                }, indexingJob.myIndicator);
                return ref.get();
            });
            myIndexingSuccessfulCount.incrementAndGet();
            if (LOG.isTraceEnabled() && myIndexingSuccessfulCount.longValue() % 10_000 == 0) {
                LOG.trace("File indexing attempts = " +
                          myIndexingAttemptCount.longValue() +
                          ", indexed file count = " +
                          myIndexingSuccessfulCount.longValue());
            }

            writeIndexesForFile(indexingJob,
                    fileIndexingJob,
                    applier,
                    startTime,
                    length,
                    contentLoadingTime);
        } catch (ProcessCanceledException e) {
            // Push back the file.
            indexingJob.myQueueOfFiles.add(fileIndexingJob);
            releaseFile(file, length);
            throw e;
        } catch (Throwable e) {
            indexingJob.oneMoreFileProcessed();
            releaseFile(file, length);
            CoreFileBasedIndex.LOG.error("Error while indexing " +
                                         file.getPresentableUrl() +
                                         "\n" +
                                         "To reindex this file IDEA has to be restarted", e);
        }
    }

    private static class ContentLoadingResult {
        private final CachedFileContent content;
        private final long fileLength;

        public ContentLoadingResult(CachedFileContent content, long fileLength) {
            this.content = content;
            this.fileLength = fileLength;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ContentLoadingResult)) {
                return false;
            }

            ContentLoadingResult that = (ContentLoadingResult) o;

            if (fileLength != that.fileLength) {
                return false;
            }
            return content.equals(that.content);
        }

        @Override
        public int hashCode() {
            int result = content.hashCode();
            result = 31 * result + (int) (fileLength ^ (fileLength >>> 32));
            return result;
        }

        public CachedFileContent getContent() {
            return content;
        }

        public long getFileLength() {
            return fileLength;
        }
    }

    private static void waitForFreeMemoryToLoadFileContent(@NotNull ProgressIndicator indicator,
                                                           long fileLength) throws ProcessCanceledException {
        ourLoadedBytesLimitLock.lock();
        try {
            while (ourTotalBytesLoadedIntoMemory >= SOFT_MAX_TOTAL_BYTES_LOADED_INTO_MEMORY) {
                indicator.checkCanceled();
                try {
                    ourLoadedBytesAreReleasedCondition.await(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    throw new ProcessCanceledException(e);
                }
            }
            ourTotalBytesLoadedIntoMemory += fileLength;
        } finally {
            ourLoadedBytesLimitLock.unlock();
        }
    }

    private static void signalThatFileIsUnloaded(long fileLength) {
        ourLoadedBytesLimitLock.lock();
        try {
            assert ourTotalBytesLoadedIntoMemory >= fileLength;
            ourTotalBytesLoadedIntoMemory -= fileLength;
            if (ourTotalBytesLoadedIntoMemory < SOFT_MAX_TOTAL_BYTES_LOADED_INTO_MEMORY) {
                ourLoadedBytesAreReleasedCondition.signalAll();
            }
        } finally {
            ourLoadedBytesLimitLock.unlock();
        }
    }

    private void writeIndexesForFile(@NotNull IndexingJob indexingJob,
                                     @NotNull FileIndexingJob fileIndexingJob,
                                     @NotNull FileIndexesValuesApplier applier,
                                     long startTime,
                                     long length,
                                     long contentLoadingTime) {
        if (myIndexWriteExecutor != null) {
            myIndexWriteExecutor.execute(() -> doWriteIndexesForFile(indexingJob,
                    fileIndexingJob,
                    applier,
                    startTime,
                    length,
                    contentLoadingTime));
        } else {
            doWriteIndexesForFile(indexingJob,
                    fileIndexingJob,
                    applier,
                    startTime,
                    length,
                    contentLoadingTime);
        }
    }

    private static void doWriteIndexesForFile(@NotNull IndexingJob indexingJob,
                                              @NotNull FileIndexingJob fileIndexingJob,
                                              @NotNull FileIndexesValuesApplier applier,
                                              long startTime,
                                              long length,
                                              long contentLoadingTime) {
        VirtualFile file = fileIndexingJob.file;
        try {
            applier.apply(file);
            long processingTime = System.nanoTime() - startTime;
//            IndexingFileSetStatistics statistics = indexingJob.getStatistics(fileIndexingJob);
//            //noinspection SynchronizationOnLocalVariableOrMethodParameter
//            synchronized (statistics) {
//                statistics.addFileStatistics(file,
//                        applier.stats,
//                        processingTime,
//                        contentLoadingTime,
//                        length,
//                        applier.isWriteValuesSeparately,
//                        applier.getSeparateApplicationTimeNanos()
//                );
//            }
            indexingJob.oneMoreFileProcessed();
        } finally {
            releaseFile(file, length);
        }
    }

    private static void logFailedToLoadContentException(@NotNull FailedToLoadContentException e) {
        Throwable cause = e.getCause();
        VirtualFile file = e.getFile();
        String fileUrl = "File: " + file.getUrl();
        if (cause instanceof FileNotFoundException || cause instanceof NoSuchFileException) {
            // It is possible to not observe file system change until refresh finish, we handle
            // missed file properly anyway.
            CoreFileBasedIndex.LOG.debug(fileUrl, e);
        } else if (cause instanceof IndexOutOfBoundsException ||
                   cause instanceof InvalidVirtualFileAccessException) {
            CoreFileBasedIndex.LOG.info(fileUrl, e);
        } else {
            CoreFileBasedIndex.LOG.error(fileUrl, e);
        }
    }

    @NotNull
    public static String getPresentableLocationBeingIndexed(@NotNull Project project,
                                                            @NotNull VirtualFile file) {
        VirtualFile actualFile = file;
        if (actualFile.getFileSystem() instanceof CoreJarFileSystem) {
            actualFile = VfsUtilCore.getVirtualFileForJar(actualFile);
        }
        String path = getProjectRelativeOrAbsolutePath(project, actualFile);
        path = "/".equals(path) ? actualFile.getName() : path;
        return FileUtil.toSystemDependentName(path);
    }

    @NotNull
    private static String getProjectRelativeOrAbsolutePath(@NotNull Project project,
                                                           @NotNull VirtualFile file) {
        String projectBase = project.getBasePath();
        if (StringUtil.isNotEmpty(projectBase)) {
            String filePath = file.getPath();
            if (FileUtil.isAncestor(projectBase, filePath, true)) {
                String projectDirName = FileUtil.getNameWithoutExtension(projectBase);
                String relativePath = FileUtil.getRelativePath(projectBase, filePath, '/');
                if (StringUtil.isNotEmpty(projectDirName) && StringUtil.isNotEmpty(relativePath)) {
                    return projectDirName + "/" + relativePath;
                }
            }
        }
        return file.getPath();
    }

    private static @NotNull ProgressIndicator unwrapAll(@NotNull ProgressIndicator indicator) {
        // Can't use "ProgressWrapper.unwrapAll" here because it unwraps "ProgressWrapper"s only
        // (not any "WrappedProgressIndicator")
        while (indicator instanceof WrappedProgressIndicator) {
            indicator = ((WrappedProgressIndicator) indicator).getOriginalProgressIndicator();
        }
        return indicator;
    }

    private static class IndexingJob {
        final Project myProject;
        final CachedFileContentLoader myContentLoader;
        final ArrayBlockingQueue<FileIndexingJob> myQueueOfFiles;
        // for Community sources the size is about 615K entries
        final ProgressIndicator myIndicator;
        final int myTotalFiles;
        final AtomicBoolean myNoMoreFilesInQueue = new AtomicBoolean();
        final CountDownLatch myAllFilesAreProcessedLatch;
        final ProgressIndicator myOriginalProgressIndicator;
        @Nullable
        final ProgressSuspender myOriginalProgressSuspender;
        final AtomicReference<Throwable> myError = new AtomicReference<>();

        IndexingJob(@NotNull Project project,
                    @NotNull ProgressIndicator indicator,
                    @NotNull CachedFileContentLoader contentLoader,
                    @NotNull List<FileSet> fileSets,
                    @NotNull ProgressIndicator originalProgressIndicator,
                    @Nullable ProgressSuspender originalProgressSuspender) {
            myProject = project;
            myIndicator = indicator;
            int maxFilesCount = fileSets.stream().mapToInt(fileSet -> fileSet.files.size()).sum();
            myQueueOfFiles = new ArrayBlockingQueue<>(maxFilesCount);
            // UnindexedFilesIndexer may produce duplicates during merging.
            // E.g. Indexer([origin:someFiles]) + Indexer[anotherOrigin:someFiles] => Indexer
            // ([origin:someFiles, anotherOrigin:someFiles])
            // Don't touch UnindexedFilesIndexer.tryMergeWith now, because eventually we want
            // UnindexedFilesIndexer to process the queue itself
            // instead of processing and merging queue snapshots
            IndexableFilesDeduplicateFilter deduplicateFilter =
                    IndexableFilesDeduplicateFilter.create();
            for (FileSet fileSet : fileSets) {
                for (VirtualFile file : fileSet.files) {
                    if (deduplicateFilter.accept(file)) {
                        myQueueOfFiles.add(new FileIndexingJob(file, fileSet));
                    }
                }
            }
            // todo: maybe we want to do something with statistics: deduplicateFilter
            //  .getNumberOfSkippedFiles();
            myTotalFiles = myQueueOfFiles.size();
            myContentLoader = contentLoader;
            myAllFilesAreProcessedLatch = new CountDownLatch(myTotalFiles);
            myOriginalProgressIndicator = originalProgressIndicator;
            myOriginalProgressSuspender = originalProgressSuspender;
        }

        public void oneMoreFileProcessed() {
            myAllFilesAreProcessedLatch.countDown();
            double newFraction =
                    1.0 - myAllFilesAreProcessedLatch.getCount() / (double) myTotalFiles;
            try {
                myIndicator.setFraction(newFraction);
            } catch (Exception ignored) {
                //Unexpected here. A misbehaved progress indicator must not break our code flow.
            }
        }

        boolean areAllFilesProcessed() {
            return myAllFilesAreProcessedLatch.getCount() == 0;
        }

        public void setLocationBeingIndexed(@NotNull FileIndexingJob fileIndexingJob) {
            String presentableLocation =
                    getPresentableLocationBeingIndexed(myProject, fileIndexingJob.file);
//            if (myIndicator instanceof SubTaskProgressIndicator) {
//                myIndicator.setText(presentableLocation);
//            } else {
                FileSet fileSet = fileIndexingJob.fileSet;
                if (fileSet.progressText != null &&
                    !fileSet.progressText.equals(myIndicator.getText())) {
                    myIndicator.setText(fileSet.progressText);
                }
                myIndicator.setText2(presentableLocation);
//            }
        }
    }
}
