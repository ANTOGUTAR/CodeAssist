package org.jetbrains.kotlin.com.intellij.util.io;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.util.ThreadLocalCachedByteArray;
import org.jetbrains.kotlin.com.intellij.openapi.util.ThrowableComputable;
import org.jetbrains.kotlin.com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream;
import org.jetbrains.kotlin.com.intellij.openapi.util.io.ByteArraySequence;
import org.jetbrains.kotlin.com.intellij.util.MathUtil;
import org.jetbrains.kotlin.com.intellij.util.SystemProperties;
import org.jetbrains.kotlin.com.intellij.util.io.AppendablePersistentMap.ValueDataAppender;
import org.jetbrains.kotlin.com.intellij.util.io.PersistentMapImpl.CompactionRecordInfo;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

public final class PersistentHashMapValueStorage {
  @Nullable
  private RAReader myCompactionModeReader;
  private volatile long mySize;
  private final Path myPath;
  private final CompressedAppendableFile myCompressedAppendableFile;

  private final CreationTimeOptions myOptions;

  private boolean myCompactionMode;

  private static final int CACHE_PROTECTED_QUEUE_SIZE = 10;
  private static final int CACHE_PROBATIONAL_QUEUE_SIZE = 20;
  private static final long MAX_RETAINED_LIMIT_WHEN_COMPACTING = 100 * 1024 * 1024;

  static final long SOFT_MAX_RETAINED_LIMIT = 10 * 1024 * 1024;
  static final int BLOCK_SIZE_TO_WRITE_WHEN_SOFT_MAX_RETAINED_LIMIT_IS_HIT = 1024;

  /**
   * Default options for {@link PersistentHashMap} and {@link PersistentHashMapValueStorage}.
   * <br/>
   * Why: Both PHMap and its components (like PHMValueStorage) are frequently initialized quite deeply
   * inside some wrapping code. Also, it could be >1 instances of PHMap/ValueStorage to support single
   * top-level structure, and params like READONLY should be the same for all them. But it is quite
   * daunting to pass parameters like READONLY through all the ctors and intermediate methods down to
   * the point of actual initialization (e.g. READONLY is used in {@linkplain com.intellij.util.io.PagedFileStorage}
   * even). CreationTimeOptions allows to set those arguments in thread-local instance, and get them
   * there they are needed.
   * <br/>
   * Instances of the class are immutable, all mutation methods return new instances -- one could
   * safely keep any instance of this class for later use.
   */
  //@Immutable
  public static final class CreationTimeOptions {
    public static final ThreadLocal<Boolean> READONLY = new ThreadLocal<>();
    public static final ThreadLocal<Boolean> COMPACT_CHUNKS_WITH_VALUE_DESERIALIZATION = new ThreadLocal<>();
    public static final ThreadLocal<Boolean> HAS_NO_CHUNKS = new ThreadLocal<>();

    static final ThreadLocal<Boolean> DO_COMPRESSION = new ThreadLocal<Boolean>() {
      @Override
      protected Boolean initialValue() {
        return COMPRESSION_ENABLED;
      }
    };

    private final @NonNull IOCancellationCallback myIOCancellationCallback;
    private final boolean myReadOnly;
    private final boolean myCompactChunksWithValueDeserialization;
    private final boolean myHasNoChunks;
    private final boolean myUseCompression;

    private CreationTimeOptions(@NonNull IOCancellationCallback callback,
                                boolean readOnly,
                                boolean compactChunksWithValueDeserialization,
                                boolean hasNoChunks,
                                boolean doCompression) {
      myIOCancellationCallback = callback;
      myReadOnly = readOnly;
      myCompactChunksWithValueDeserialization = compactChunksWithValueDeserialization;
      myHasNoChunks = hasNoChunks;
      myUseCompression = doCompression;
    }

    int getVersion() {
      return (myHasNoChunks ? 10 : 0) * 31 + (myUseCompression ? 0x13 : 0);
    }

    boolean isReadOnly() {
      return myReadOnly;
    }

    boolean useCompression() {
      return myUseCompression;
    }

    public CreationTimeOptions setReadOnly() {
      return new CreationTimeOptions(
        myIOCancellationCallback,
        true,
        myCompactChunksWithValueDeserialization,
        myHasNoChunks,
        myUseCompression
      );
    }

    public CreationTimeOptions readOnly(final boolean readOnly) {
      return new CreationTimeOptions(
        myIOCancellationCallback,
        readOnly,
        myCompactChunksWithValueDeserialization,
        myHasNoChunks,
        myUseCompression
      );
    }

    public CreationTimeOptions setCompactChunksWithValueDeserialization(){
      return new CreationTimeOptions(myIOCancellationCallback, myReadOnly,
                                     true,
                                     myHasNoChunks, myUseCompression);
    }

    public CreationTimeOptions setHasNoChunks(){
      return new CreationTimeOptions(myIOCancellationCallback, myReadOnly,
                                     myCompactChunksWithValueDeserialization,
                                     true,
                                     myUseCompression);
    }

    public <T, E extends Throwable> T with(final @NonNull ThrowableComputable<T, E> func) throws E {
      final CreationTimeOptions previousOptions = setThreadLocalOptions(this);
      try {
        return func.compute();
      }
      finally {
        setThreadLocalOptions(previousOptions);
      }
    }

    @NonNull
    public static CreationTimeOptions threadLocalOptions() {
      return new CreationTimeOptions(
        IOCancellationCallbackHolder.getUsedIoCallback(),
        READONLY.get() == Boolean.TRUE,
        COMPACT_CHUNKS_WITH_VALUE_DESERIALIZATION.get() == Boolean.TRUE,
        HAS_NO_CHUNKS.get() == Boolean.TRUE,
        DO_COMPRESSION.get() == Boolean.TRUE);
    }

    @NonNull
    public static CreationTimeOptions setThreadLocalOptions(final CreationTimeOptions options){
      final CreationTimeOptions currentOptions = threadLocalOptions();
      READONLY.set(options.myReadOnly);
      COMPACT_CHUNKS_WITH_VALUE_DESERIALIZATION.set(options.myCompactChunksWithValueDeserialization);
      HAS_NO_CHUNKS.set(options.myHasNoChunks);
      DO_COMPRESSION.set(options.myUseCompression);
      return currentOptions;
    }
  }

  @NonNull
  CreationTimeOptions getOptions() {
    return myOptions;
  }

  // cache size is twice larger than constants because (when used) it replaces two caches
  private static final FileAccessorCache<Path, FileChannelWithSizeTracking> ourFileChannelCache =
    new FileAccessorCache<Path, FileChannelWithSizeTracking>(
      2 * CACHE_PROTECTED_QUEUE_SIZE, 2 * CACHE_PROBATIONAL_QUEUE_SIZE) {
      @NonNull
      @Override
      protected FileChannelWithSizeTracking createAccessor(Path path) throws IOException {
        return new FileChannelWithSizeTracking(path);
      }

      @Override
      protected void disposeAccessor(@NonNull FileChannelWithSizeTracking fileAccessor) throws IOException {
        fileAccessor.close();
      }
    };

  private static final FileAccessorCache<Path, SyncAbleBufferedOutputStreamOverCachedFileChannel> ourAppendersCache =
    new FileAccessorCache<Path, SyncAbleBufferedOutputStreamOverCachedFileChannel>(CACHE_PROTECTED_QUEUE_SIZE, CACHE_PROBATIONAL_QUEUE_SIZE) {
      @NonNull
      @Override
      protected PersistentHashMapValueStorage.SyncAbleBufferedOutputStreamOverCachedFileChannel createAccessor(Path path) {
        return new SyncAbleBufferedOutputStreamOverCachedFileChannel(path);
      }

      @Override
      protected void disposeAccessor(@NonNull PersistentHashMapValueStorage.SyncAbleBufferedOutputStreamOverCachedFileChannel stream) throws IOException {
        stream.close();
      }
    };

  private static final FileAccessorCache<Path, RAReader> ourReadersCache =
    new FileAccessorCache<Path, RAReader>(CACHE_PROTECTED_QUEUE_SIZE, CACHE_PROBATIONAL_QUEUE_SIZE) {
      @NonNull
      @Override
      protected RAReader createAccessor(Path path) {
        return new ReaderOverFileChannelCache(path);
      }

      @Override
      protected void disposeAccessor(@NonNull RAReader fileAccessor) throws IOException {
        fileAccessor.dispose();
      }
    };

  public static final boolean COMPRESSION_ENABLED = SystemProperties.getBooleanProperty("idea.compression.enabled", true);

  PersistentHashMapValueStorage(@NonNull Path path, @NonNull CreationTimeOptions options) throws IOException {
    myPath = path;
    myOptions = options;

    if (myOptions.useCompression()) {
      myCompressedAppendableFile = new MyCompressedAppendableFile();
      mySize = myCompressedAppendableFile.length();
    }
    else {
      myCompressedAppendableFile = null;
      mySize = Files.exists(myPath) ? Files.size(myPath) : 0;
    }
  }

  public long appendBytes(ByteArraySequence data, long prevChunkAddress) throws IOException {
    return appendBytes(data.getBytes(), data.getOffset(), data.getLength(), prevChunkAddress);
  }

  public long appendBytes(byte[] data, int offset, int dataLength, long prevChunkAddress) throws IOException {
    if (mySize == 0) {
      byte[] bytes = "Header Record For PersistentHashMapValueStorage".getBytes(StandardCharsets.UTF_8);
      doAppendBytes(bytes, 0, bytes.length, 0);

      // avoid corruption issue when disk fails to write first record synchronously or unexpected first write file increase (IDEA-106306),
      // code depends on correct value of mySize
      FileAccessorCache.Handle<SyncAbleBufferedOutputStreamOverCachedFileChannel> streamCacheValue = ourAppendersCache.getIfCached(myPath);
      if (streamCacheValue != null) {
        try {
          final SyncAbleBufferedOutputStreamOverCachedFileChannel stream = streamCacheValue.get();
          stream.flush();
          stream.sync();
        }
        catch (IOException e) {
          throw new RuntimeException(e);
        }
        finally {
          streamCacheValue.release();
        }
      }

      long currentLength = Files.exists(myPath) ? Files.size(myPath) : 0;
      if (currentLength > mySize) {  // if real file length (unexpectedly) increases
        Logger.getInstance(getClass().getName()).info("Avoided PSHM corruption due to write failure:" + myPath);
        mySize = currentLength;  // volatile write
      }
    }

    return doAppendBytes(data, offset, dataLength, prevChunkAddress);
  }

  void checkAppendsAllowed(int previouslyAccumulatedChunkSize) {
      if (previouslyAccumulatedChunkSize != 0 && myOptions.myHasNoChunks) {
          throw new AssertionError();
      }
  }

  private long doAppendBytes(byte[] data, int offset, int dataLength, long prevChunkAddress) throws IOException {
      if (!allowedToCompactChunks()) {
          throw new AssertionError();
      }
      if (prevChunkAddress != 0 && myOptions.myHasNoChunks) {
          throw new AssertionError();
      }
    long currentChunkAddress = mySize; // volatile read

    DataOutputStream dataOutputStream;
    if (myCompressedAppendableFile != null) {
      dataOutputStream = new DataOutputStream(new OutputStream() {
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
          myCompressedAppendableFile.append(b, off, len);
        }

        @Override
        public void write(int b) throws IOException {
          byte[] r = {(byte)(b & 0xFF)};
          write(r);
        }
      });
    } else {
      FileAccessorCache.Handle<SyncAbleBufferedOutputStreamOverCachedFileChannel> appender = ourAppendersCache.get(myPath);
      dataOutputStream = toDataOutputStream(appender);
    }

    try {
      saveHeader(dataLength, prevChunkAddress, currentChunkAddress, dataOutputStream);
      dataOutputStream.write(data, offset, dataLength);
      mySize += dataOutputStream.resetWrittenBytesCount();  // volatile write
    }
    finally {
      dataOutputStream.close();
    }

    return currentChunkAddress;
  }

  private void saveHeader(int dataLength,
                          long prevChunkAddress,
                          long chunkAddress,
                          @NonNull DataOutputStream dataOutputStream) throws IOException {
    DataInputOutputUtil.writeINT(dataOutputStream, dataLength);
    if (!myOptions.myHasNoChunks) {
      if (chunkAddress < prevChunkAddress) {
        throw new IOException("writePrevChunkAddress:" + chunkAddress + "," + prevChunkAddress + "," + myPath);
      }
      long diff = chunkAddress - prevChunkAddress;
      DataInputOutputUtil.writeLONG(dataOutputStream, prevChunkAddress == 0 ? 0 : diff);
    }
  }

  private static final ThreadLocalCachedByteArray myBuffer = new ThreadLocalCachedByteArray();
  private static final int ourBufferLength = 1024;

  private long compactValuesWithoutChunks(@NonNull List<CompactionRecordInfo> infos, @NonNull PersistentHashMapValueStorage storage)
    throws IOException {
    //infos = new ArrayList<PersistentHashMap.CompactionRecordInfo>(infos);
    infos.sort(Comparator.comparingLong(info -> info.valueAddress));

    final int fileBufferLength = 256 * 1024;
    final byte[] buffer = new byte[fileBufferLength];

    int fragments = 0;
    int newFragments = 0;

    byte[] outputBuffer = new byte[4096];

    long readStartOffset = -1;
    int bytesRead = -1;

    for (CompactionRecordInfo info : infos) {
      int recordStartInBuffer = (int)(info.valueAddress - readStartOffset);

      if (recordStartInBuffer + 5 > fileBufferLength || readStartOffset == -1) {
        readStartOffset = info.valueAddress;
        long remainingBytes = readStartOffset != -1 ? mySize - readStartOffset : mySize;
        bytesRead = remainingBytes < fileBufferLength ? (int)remainingBytes : fileBufferLength;

        myCompactionModeReader.get(readStartOffset, buffer, 0, bytesRead); // buffer contains [readStartOffset, readStartOffset + bytesRead)
        recordStartInBuffer = (int)(info.valueAddress - readStartOffset);
      }

      DataInputStream stream = toDataInputStream(buffer, recordStartInBuffer, buffer.length);
      int available = stream.available();
      int chunkSize = readChunkSize(stream);
      long prevChunkAddress = readPrevChunkAddress(info.valueAddress, stream);
      assert prevChunkAddress == 0;
      int dataOffset = available - stream.available() + recordStartInBuffer;

      if (chunkSize >= outputBuffer.length) {
        outputBuffer = new byte[(chunkSize / 4096 + 1) * 4096];
      }

      // dataOffset .. dataOffset + chunkSize
      int bytesFitInBuffer = Math.min(chunkSize, fileBufferLength - dataOffset);
      System.arraycopy(buffer, dataOffset, outputBuffer, 0, bytesFitInBuffer);

      while (bytesFitInBuffer != chunkSize) {
        readStartOffset += bytesRead;

        long remainingBytes = mySize - readStartOffset;
        bytesRead = remainingBytes < fileBufferLength ? (int)remainingBytes : fileBufferLength;

        myCompactionModeReader.get(readStartOffset, buffer, 0, bytesRead); // buffer contains [readStartOffset, readStartOffset + bytesRead)
        int newBytesFitInBuffer = Math.min(chunkSize - bytesFitInBuffer, fileBufferLength);
        System.arraycopy(buffer, 0, outputBuffer, bytesFitInBuffer, newBytesFitInBuffer);
        bytesFitInBuffer += newBytesFitInBuffer;
      }

      info.newValueAddress = storage.appendBytes(outputBuffer, 0, chunkSize, 0);

      ++fragments;
      ++newFragments;
    }

    return fragments | ((long)newFragments << 32);
  }

  long compactValues(@NonNull List<CompactionRecordInfo> infos, @NonNull PersistentHashMapValueStorage storage) throws IOException {
    if (myOptions.myHasNoChunks) {
      return compactValuesWithoutChunks(infos, storage);
    }

    PriorityQueue<CompactionRecordInfo> records = new PriorityQueue<>(
      infos.size(), (info, info2) -> Long.compare(info2.valueAddress, info.valueAddress));

    records.addAll(infos);

    final int fileBufferLength = 256 * 1024;
    final int maxRecordHeader = 5 /* max length - variable int */ + 10 /* max long offset*/;
    final byte[] buffer = new byte[fileBufferLength + maxRecordHeader];
    byte[] reusedAccumulatedChunksBuffer = {};

    long lastReadOffset = mySize;
    long lastConsumedOffset = lastReadOffset;
    long allRecordsStart = 0;
    int fragments = 0;
    int newFragments = 0;

    byte[] stuffFromPreviousRecord = null;
    int bytesRead = (int)(mySize - (mySize / fileBufferLength) * fileBufferLength);
    long retained = 0;

    while (lastReadOffset != 0) {
      final long readStartOffset = lastReadOffset - bytesRead;
      myCompactionModeReader.get(readStartOffset, buffer, 0, bytesRead); // buffer contains [readStartOffset, readStartOffset + bytesRead)

      while (!records.isEmpty()) {
        final CompactionRecordInfo info = records.peek();
        if (info.valueAddress >= readStartOffset) {
          if (info.valueAddress >= lastReadOffset) {
            throw new IOException("Value storage is corrupted: value file size:" + mySize + ", readStartOffset:" + readStartOffset +
                                  ", record address:" + info.valueAddress + "; file: " + myPath);
          }
          // record start is inside our buffer

          final int recordStartInBuffer = (int)(info.valueAddress - readStartOffset);
          DataInputStream inputStream = toDataInputStream(buffer, recordStartInBuffer, buffer.length);

          if (stuffFromPreviousRecord != null && fileBufferLength - recordStartInBuffer < maxRecordHeader) {
            // add additional bytes to read offset / size
            if (allRecordsStart != 0) {
              myCompactionModeReader.get(allRecordsStart, buffer, bytesRead, maxRecordHeader);
            }
            else {
              final int maxAdditionalBytes = Math.min(stuffFromPreviousRecord.length, maxRecordHeader);
              System.arraycopy(stuffFromPreviousRecord, 0, buffer, bytesRead, maxAdditionalBytes);
            }
          }

          int available = inputStream.available();
          int chunkSize = readChunkSize(inputStream);
          final long prevChunkAddress = readPrevChunkAddress(info.valueAddress, inputStream);
          final int dataOffset = available - inputStream.available();

          byte[] accumulatedChunksBuffer;
          if (info.value != null) {
            int defragmentedChunkSize = info.value.length + chunkSize;
            if (prevChunkAddress == 0) {
              if (defragmentedChunkSize >= reusedAccumulatedChunksBuffer.length) {
                reusedAccumulatedChunksBuffer = new byte[defragmentedChunkSize];
              }
              accumulatedChunksBuffer = reusedAccumulatedChunksBuffer;
            }
            else {
              accumulatedChunksBuffer = new byte[defragmentedChunkSize];
              retained += defragmentedChunkSize;
            }
            System.arraycopy(info.value, 0, accumulatedChunksBuffer, chunkSize, info.value.length);
          }
          else {
            if (prevChunkAddress == 0) {
                if (chunkSize >= reusedAccumulatedChunksBuffer.length) {
                    reusedAccumulatedChunksBuffer = new byte[chunkSize];
                }
              accumulatedChunksBuffer = reusedAccumulatedChunksBuffer;
            }
            else {
              accumulatedChunksBuffer = new byte[chunkSize];
              retained += chunkSize;
            }
          }

          final int chunkSizeOutOfBuffer = MathUtil.clamp((int)(info.valueAddress + dataOffset + chunkSize - lastReadOffset), 0, chunkSize);
          if (chunkSizeOutOfBuffer > 0) {
            if (allRecordsStart != 0) {
              myCompactionModeReader.get(allRecordsStart, accumulatedChunksBuffer, chunkSize - chunkSizeOutOfBuffer, chunkSizeOutOfBuffer);
            }
            else {
              int offsetInStuffFromPreviousRecord = Math.max((int)(info.valueAddress + dataOffset - lastReadOffset), 0);
              // stuffFromPreviousRecord starts from lastReadOffset
              System.arraycopy(stuffFromPreviousRecord, offsetInStuffFromPreviousRecord, accumulatedChunksBuffer,
                               chunkSize - chunkSizeOutOfBuffer, chunkSizeOutOfBuffer);
            }
          }

          stuffFromPreviousRecord = null;
          allRecordsStart = 0;

          lastConsumedOffset = info.valueAddress;
          checkPreconditions(accumulatedChunksBuffer, chunkSize);

          System.arraycopy(buffer, recordStartInBuffer + dataOffset, accumulatedChunksBuffer, 0, chunkSize - chunkSizeOutOfBuffer);

          ++fragments;
          records.remove(info);
          if (info.value != null) {
            chunkSize += info.value.length;
            retained -= info.value.length;
            info.value = null;
          }

          if (prevChunkAddress == 0) {
            info.newValueAddress = storage.appendBytes(accumulatedChunksBuffer, 0, chunkSize, info.newValueAddress);
            ++newFragments;
          }
          else {
            if (retained > SOFT_MAX_RETAINED_LIMIT &&
                accumulatedChunksBuffer.length > BLOCK_SIZE_TO_WRITE_WHEN_SOFT_MAX_RETAINED_LIMIT_IS_HIT ||
                retained > MAX_RETAINED_LIMIT_WHEN_COMPACTING) {
              // to avoid OOME we need to save bytes in accumulatedChunksBuffer
              newFragments +=
                saveAccumulatedDataOnDiskPreservingWriteOrder(storage, info, prevChunkAddress, accumulatedChunksBuffer, chunkSize);
              retained -= accumulatedChunksBuffer.length;
              continue;
            }
            info.value = accumulatedChunksBuffer;
            info.valueAddress = prevChunkAddress;
            records.add(info);
          }
        }
        else {
          // [readStartOffset,lastConsumedOffset) is from previous segment
          if (stuffFromPreviousRecord == null) {
            stuffFromPreviousRecord = new byte[(int)(lastConsumedOffset - readStartOffset)];
            System.arraycopy(buffer, 0, stuffFromPreviousRecord, 0, stuffFromPreviousRecord.length);
          }
          else {
            allRecordsStart = readStartOffset;
          }
          break; // request next read
        }
      }

      lastReadOffset -= bytesRead;
      bytesRead = fileBufferLength;
    }

    return fragments | ((long)newFragments << 32);
  }

  private int saveAccumulatedDataOnDiskPreservingWriteOrder(PersistentHashMapValueStorage storage,
                                                            CompactionRecordInfo info,
                                                            long prevChunkAddress,
                                                            byte[] accumulatedChunksData,
                                                            int accumulatedChunkDataLength) throws IOException {
    ReadResult result = readBytes(prevChunkAddress);
    // to avoid possible OOME result.bytes and accumulatedChunksData are not combined in one chunk, instead they are
    // placed one after another, such near placement should be fine because of disk caching
    info.newValueAddress = storage.appendBytes(result.buffer, 0, result.buffer.length, info.newValueAddress);
    info.newValueAddress = storage.appendBytes(accumulatedChunksData, 0, accumulatedChunkDataLength, info.newValueAddress);

    info.value = null;
    info.valueAddress = 0;
    return 2; // number of chunks produced = number of appendBytes called
  }

  static class ReadResult {
    final byte[] buffer;
    final int chunksCount;

    ReadResult(byte[] buffer, int chunksCount) {
      this.buffer = buffer;
      this.chunksCount = chunksCount;
    }
  }

  private long myChunksRemovalTime;
  private long myChunksReadingTime;
  private int myChunks;
  private long myChunksOriginalBytes;
  private long myChunksBytesAfterRemoval;
  private int myLastReportedChunksCount;

  /**
   * Reads bytes pointed by tailChunkAddress into result passed, returns new address if linked list compactification have been performed
   */
  public ReadResult readBytes(long tailChunkAddress) throws IOException {
    forceAppender(myPath);

    checkCancellation();
    long startedTime = ourDumpChunkRemovalTime ? System.nanoTime() : 0;

    RAReader reader = myCompactionModeReader;
    FileAccessorCache.Handle<RAReader> readerHandle = null;
    if (myCompressedAppendableFile != null) {
      reader = new ReaderOverCompressedFile(myCompressedAppendableFile);
    }
    if (reader == null) {
      readerHandle = ourReadersCache.get(myPath);
      reader = readerHandle.get();
    }

    int chunkCount = 0;
    byte[] result = null;
    try {
      long chunk = tailChunkAddress;
      while (chunk != 0) {
          if (chunk < 0 || chunk > mySize) {
              throw new CorruptedException(myPath);
          }

        byte[] buffer = myBuffer.getBuffer(ourBufferLength);
        int len = (int)Math.min(ourBufferLength, mySize - chunk);

        reader.get(chunk, buffer, 0, len);
        DataInputStream inputStream = toDataInputStream(buffer, 0, len);

        final int chunkSize = readChunkSize(inputStream);
        final long prevChunkAddress = readPrevChunkAddress(chunk, inputStream);
        final int headerOffset = len - inputStream.available();

        byte[] b = new byte[(result != null ? result.length : 0) + chunkSize];
          if (result != null) {
              System.arraycopy(result, 0, b, b.length - result.length, result.length);
          }
        result = b;

        checkPreconditions(result, chunkSize);
        if (chunkSize < ourBufferLength - headerOffset) {
          System.arraycopy(buffer, headerOffset, result, 0, chunkSize);
        }
        else {
          reader.get(chunk + headerOffset, result, 0, chunkSize);
        }

          if (prevChunkAddress >= chunk) {
              throw new CorruptedException(myPath);
          }

        chunk = prevChunkAddress;
        chunkCount++;

        if (prevChunkAddress != 0) {
          checkCancellation();
          assert !myOptions.myHasNoChunks;
        }
        if (result.length > mySize && myCompressedAppendableFile == null) {
          throw new CorruptedException(myPath);
        }
      }
    }
    catch (OutOfMemoryError error) {
      throw new CorruptedException(myPath);
    }
    finally {
      if (readerHandle != null) {
        readerHandle.release();
      }
    }

    if (chunkCount > 1) {
      checkCancellation();

      myChunksReadingTime += (ourDumpChunkRemovalTime ? System.nanoTime() : 0) - startedTime;
      myChunks += chunkCount;
      myChunksOriginalBytes += result.length;
    }

    return new ReadResult(result, chunkCount);
  }

  private boolean allowedToCompactChunks() {
    return !myCompactionMode && !myOptions.myReadOnly;
  }

  boolean performChunksCompaction(int chunksCount) {
    return chunksCount > 1 && allowedToCompactChunks();
  }

  long compactChunks(@NonNull ValueDataAppender appender, @NonNull ReadResult result) throws IOException {
    checkCancellation();
    long startedTime = ourDumpChunkRemovalTime ? System.nanoTime() : 0;
    long newValueOffset;

    if (myOptions.myCompactChunksWithValueDeserialization) {
      final BufferExposingByteArrayOutputStream stream = new BufferExposingByteArrayOutputStream(result.buffer.length);
      DataOutputStream testStream = new DataOutputStream(stream);
      appender.append(testStream);
      newValueOffset = appendBytes(stream.toByteArraySequence(), 0);
      myChunksBytesAfterRemoval += stream.size();
    }
    else {
      newValueOffset = appendBytes(new ByteArraySequence(result.buffer), 0);
      myChunksBytesAfterRemoval += result.buffer.length;
    }

    if (ourDumpChunkRemovalTime) {
      myChunksRemovalTime += System.nanoTime() - startedTime;

      if (myChunks - myLastReportedChunksCount > 1000) {
        myLastReportedChunksCount = myChunks;

        System.out.println(myChunks + " chunks were read " + (myChunksReadingTime / 1000000) +
                           "ms, bytes: " + myChunksOriginalBytes +
                           (myChunksOriginalBytes != myChunksBytesAfterRemoval ? "->" + myChunksBytesAfterRemoval : "") +
                           " compaction:" + (myChunksRemovalTime / 1000000) + "ms in " + myPath);
      }
    }

    return newValueOffset;
  }

  private static final boolean ourDumpChunkRemovalTime = SystemProperties.getBooleanProperty("idea.phmp.dump.chunk.removal.time", false);

  // hook for exceptional termination of long io operation
  private void checkCancellation() {
    myOptions.myIOCancellationCallback.checkCancelled();
  }

  private static int readChunkSize(@NonNull DataInputStream in) throws IOException {
    final int chunkSize = DataInputOutputUtil.readINT(in);
    if (chunkSize < 0) {
      throw new IOException("Value storage corrupted: negative chunk size: " + chunkSize);
    }
    return chunkSize;
  }

  private long readPrevChunkAddress(long chunkAddress, @NonNull DataInputStream in) throws IOException {
      if (myOptions.myHasNoChunks) {
          return 0;
      }
    final long prevOffsetDiff = DataInputOutputUtil.readLONG(in);
    if (prevOffsetDiff >= chunkAddress) {
      throw new IOException("readPrevChunkAddress:" + chunkAddress + "," + prevOffsetDiff + "," + mySize + "," + myPath);
    }
    return prevOffsetDiff != 0 ? chunkAddress - prevOffsetDiff : 0;
  }

  public long getSize() {
    return mySize;
  }

  private static void checkPreconditions(final byte[] result, final int chunkSize) throws IOException {
    if (chunkSize < 0) {
      throw new IOException("Value storage corrupted: negative chunk size");
    }
    if (chunkSize > result.length) {
      throw new IOException("Value storage corrupted");
    }
  }

  public void force() {
      if (myOptions.myReadOnly) {
          return;
      }
    if (myCompressedAppendableFile != null) {
      myCompressedAppendableFile.force();
    }
      if (mySize < 0) {
          assert false;  // volatile read
      }
    forceAppender(myPath);
  }

  private static void forceAppender(Path path) {
    final FileAccessorCache.Handle<? extends OutputStream> cached = ourAppendersCache.getIfCached(path);
    if (cached != null) {
      try {
        cached.get().flush();
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
      finally {
        cached.release();
      }
    }
  }

  public void dispose() {
    try {
        if (myCompressedAppendableFile != null) {
            myCompressedAppendableFile.dispose();
        }
    }
    finally {
        if (mySize < 0) {
            assert false; // volatile read
        }
      ourReadersCache.remove(myPath);
      ourAppendersCache.remove(myPath);

      ourFileChannelCache.remove(myPath);

      if (myCompactionModeReader != null) {
        try {
          myCompactionModeReader.dispose();
        }
        catch (IOException e) {
          throw new RuntimeException(e);
        }
        myCompactionModeReader = null;
      }
    }
  }

  void switchToCompactionMode() throws IOException {
    ourReadersCache.remove(myPath);
    ourFileChannelCache.remove(myPath);

    // in compaction mode use faster reader
    if (myCompressedAppendableFile != null) {
      myCompactionModeReader = new ReaderOverCompressedFile(myCompressedAppendableFile);
    }
    else {
      myCompactionModeReader = new FileReader(myPath);
    }

    myCompactionMode = true;
  }

  @NonNull
  private static DataInputStream toDataInputStream(byte[] buffer, int offset, int length) {
    return new DataInputStream(new UnsyncByteArrayInputStream(buffer, offset, length));
  }

  @NonNull
  private static DataOutputStream toDataOutputStream(FileAccessorCache.Handle<SyncAbleBufferedOutputStreamOverCachedFileChannel> handle) {
    return new DataOutputStream(handle.get()) {
      @Override
      public void close() throws IOException {
        super.close();
        handle.close();
      }
    };
  }

  public static PersistentHashMapValueStorage create(@NonNull Path path, @NonNull CreationTimeOptions options) throws IOException {
    return new PersistentHashMapValueStorage(path, options);
  }

  private interface RAReader {
    void get(long addr, byte[] dst, int off, int len) throws IOException;

    void dispose() throws IOException;
  }

  private static class ReaderOverCompressedFile implements RAReader {
    @NonNull
    private final CompressedAppendableFile myCompressedAppendableFile;

    ReaderOverCompressedFile(@NonNull CompressedAppendableFile compressedAppendableFile) {
      myCompressedAppendableFile = compressedAppendableFile;
    }

    @Override
    public void get(long addr, byte[] dst, int off, int len) throws IOException {
      try (DataInputStream stream = myCompressedAppendableFile.getStream(addr)) {
        stream.readFully(dst, off, len);
      }
    }

    @Override
    public void dispose() {
    }
  }

  private static final class ReaderOverFileChannelCache implements RAReader {
    private final Path myPath;

    private ReaderOverFileChannelCache(@NonNull Path path) {
      myPath = path;
    }

    @Override
    public void get(final long addr, final byte[] dst, final int off, final int len) throws IOException {
      try (FileAccessorCache.Handle<FileChannelWithSizeTracking> fileAccessor = ourFileChannelCache.get(myPath)) {
        FileChannelWithSizeTracking file = fileAccessor.get();
        file.read(addr, dst, off, len);
      }
    }

    @Override
    public void dispose() {
    }
  }

  private static final class FileReader implements RAReader {
    private final UnInterruptibleFileChannel myFile;

    private FileReader(Path file) throws IOException {
      myFile = new UnInterruptibleFileChannel(file, StandardOpenOption.READ);
    }

    @Override
    public void get(final long addr, final byte[] dst, final int off, final int len) throws IOException {
      myFile.read(ByteBuffer.wrap(dst, off, len), addr);
    }

    @Override
    public void dispose() throws IOException {
      myFile.close();
    }
  }

  private static class SyncAbleBufferedOutputStreamOverCachedFileChannel extends BufferedOutputStream {
    private final Path myPath;

    SyncAbleBufferedOutputStreamOverCachedFileChannel(final Path path) {
      super(new OutputStreamOverRandomAccessFileCache(path));
      myPath = path;
    }

    public void sync() throws IOException {
      final FileAccessorCache.Handle<FileChannelWithSizeTracking> fileAccessor = ourFileChannelCache.get(myPath);
      final FileChannelWithSizeTracking fileChannel = fileAccessor.get();
      try {
        Method force = FileChannelWithSizeTracking.class.getDeclaredMethod("force");
        force.setAccessible(true);
        force.invoke(fileChannel);
      } catch (ReflectiveOperationException e) {
        if (e.getCause() instanceof InvocationTargetException) {
          if (((InvocationTargetException) e.getCause()).getTargetException() instanceof IOException) {
            throw ((IOException) ((InvocationTargetException) e.getCause()).getTargetException());
          }
        }
        throw new RuntimeException(e);
      }
    }

    /** Implements output stream by writing data through {@link FileChannelWithSizeTracking} out of {@link #ourFileChannelCache} */
    private static class OutputStreamOverRandomAccessFileCache extends OutputStream {
      private final Path myPath;

      public OutputStreamOverRandomAccessFileCache(Path path) { myPath = path; }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        FileAccessorCache.Handle<FileChannelWithSizeTracking> fileAccessor = ourFileChannelCache.get(myPath);
        FileChannelWithSizeTracking file = fileAccessor.get();
        try {
          file.write(file.length(), b, off, len);
        }
        finally {
          fileAccessor.release();
        }
      }

      @Override
      public void write(int b) throws IOException {
        byte[] r = {(byte)(b & 0xFF)};
        write(r);
      }
    }

  }

  private class MyCompressedAppendableFile extends CompressedAppendableFile {
    MyCompressedAppendableFile() throws IOException {
      super(myPath);
    }

    @NonNull
    @Override
    protected InputStream getChunkInputStream(long offset, int pageSize) throws IOException {
      forceAppender(myPath);
      FileAccessorCache.Handle<RAReader> fileAccessor = ourReadersCache.get(myPath);

      try {
        byte[] bytes = new byte[pageSize];
        fileAccessor.get().get(offset, bytes, 0, pageSize);
        return new ByteArrayInputStream(bytes);
      }
      finally {
        fileAccessor.release();
      }
    }

    @Override
    protected @NonNull DataOutputStream getChunkAppendStream() {
      return toDataOutputStream(ourAppendersCache.get(myPath));
    }

    @Override
    protected @NonNull DataOutputStream getChunkLengthAppendStream() {
      return toDataOutputStream(ourAppendersCache.get(getChunkLengthFile()));
    }

    @NonNull
    @Override
    protected Path getChunksFile() {
      return myPath;
    }

    @Override
    public synchronized void force() {
      super.force();
      forceAppender(getChunkLengthFile());
    }

    @Override
    public synchronized void dispose() {
      super.dispose();

      ourAppendersCache.remove(getChunkLengthFile());
      ourFileChannelCache.remove(getChunkLengthFile());
    }
  }

  public boolean isReadOnly() {
    return myOptions.myReadOnly;
  }
}