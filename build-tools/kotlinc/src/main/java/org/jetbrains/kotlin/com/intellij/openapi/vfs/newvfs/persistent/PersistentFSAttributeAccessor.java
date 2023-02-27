package org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.persistent;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.AttributeInputStream;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.AttributeOutputStream;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.FileAttribute;
import org.jetbrains.kotlin.com.intellij.util.io.DataInputOutputUtil;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntList;

import java.io.IOException;

final class PersistentFSAttributeAccessor {

  @NotNull
  private final PersistentFSConnection connection;
  @NotNull
  private final AbstractAttributesStorage attributesStorage;

  PersistentFSAttributeAccessor(final @NotNull PersistentFSConnection connection) {
    this.connection = connection;
    attributesStorage = connection.getAttributes();
  }

  public boolean hasAttributePage(final int fileId,
                                  final @NotNull FileAttribute attribute) throws IOException {
    return attributesStorage.hasAttributePage(connection, fileId, attribute);
  }

  @Nullable
  public AttributeInputStream readAttribute(final int fileId,
                                            final @NotNull FileAttribute attribute) throws IOException {
    final AttributeInputStream attributeStream = attributesStorage.readAttribute(connection, fileId, attribute);
    if (attributeStream != null && attribute.isVersioned()) {
      try {
        final int actualVersion = DataInputOutputUtil.readINT(attributeStream);
        if (actualVersion != attribute.getVersion()) {
          return null;
        }
      }
      catch (IOException e) {
        return null;
      }
    }
    return attributeStream;
  }

  //======== RC: methods to access attributesStorage raw byteBuffer, if storage supports it

//  protected boolean supportsRawAccess() {
//    return attributesStorage instanceof AttributesStorageOverBlobStorage;
//  }
//
//  /**
//   * Method to read attribute content, alternative to {@link #readAttribute(int, FileAttribute)} -- provides access
//   * to underlying {@link java.nio.ByteBuffer}, instead of {@link AttributeInputStream}.
//   * Check {@link #supportsRawAccess()} before call -- method throws {@link UnsupportedOperationException} otherwise.
//   *
//   * @return null if an appropriate attribute record does not exist
//   */
//  protected <R> @Nullable R readAttributeRaw(final int fileId,
//                                             final @NotNull FileAttribute attribute,
//                                             final ByteBufferReader<R> reader) throws IOException {
//    if (attributesStorage instanceof AttributesStorageOverBlobStorage) {
//      final AttributesStorageOverBlobStorage storage = (AttributesStorageOverBlobStorage)attributesStorage;
//      return storage.readAttributeRaw(connection, fileId, attribute, buffer -> {
//        if (attribute.isVersioned()) {
//          final int actualVersion = DataInputOutputUtil.readINT(buffer);
//          if (actualVersion != attribute.getVersion()) {
//            return null;
//          }
//        }
//        return reader.read(buffer);
//      });
//    }
//    else {
//      throw new UnsupportedOperationException("Raw attribute access is not implemented for " + attributesStorage.getClass().getName());
//    }
//  }
//
//  public void writeAttributeRaw(final int fileId,
//                                final @NotNull FileAttribute attribute,
//                                final ByteBufferWriter writer) {
//    if (attributesStorage instanceof AttributesStorageOverBlobStorage) {
//      final AttributesStorageOverBlobStorage storage = (AttributesStorageOverBlobStorage)attributesStorage;
//      throw new UnsupportedOperationException("Method not implemented yet");
//      //TODO RC: drill hole for storage.writeAttributeRaw(connection, fileId, attribute, writer)
//      //return storage.writeAttribute(connection, fileId, attribute, buffer -> {
//      //  if (attribute.isVersioned()) {
//      //    final int actualVersion = DataInputOutputUtil.writeINT(buffer);
//      //  }
//      //  return writer.write(buffer);
//      //});
//    }
//    else {
//      throw new UnsupportedOperationException("Raw attribute access is not implemented for " + attributesStorage.getClass().getName());
//    }
//  }

  //======================================================================================

  /**
   * Opens given attribute of given file for writing
   */
  @NotNull
  public AttributeOutputStream writeAttribute(final int fileId,
                                              final @NotNull FileAttribute attribute) {
    final AttributeOutputStream attributeStream = attributesStorage.writeAttribute(connection, fileId, attribute);
    if (attribute.isVersioned()) {
      try {
        DataInputOutputUtil.writeINT(attributeStream, attribute.getVersion());
      }
      catch (IOException e) {
        throw new AssertionError("Write to byte[]-backed stream should not throw IOException -- a bug?", e);
      }
    }

    return attributeStream;
  }

  public void deleteAttributes(final int fileId) throws IOException {
    attributesStorage.deleteAttributes(connection, fileId);
  }

  public int getLocalModificationCount() {
    return attributesStorage.getLocalModificationCount();
  }

  public void checkAttributesStorageSanity(final int fileId,
                                           final @NotNull IntList usedAttributeRecordIds,
                                           final @NotNull IntList validAttributeIds) throws IOException {
    attributesStorage.checkAttributesStorageSanity(connection, fileId, usedAttributeRecordIds, validAttributeIds);
  }
}