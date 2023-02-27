package org.jetbrains.kotlin.com.intellij.util.io.keyStorage;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.util.io.ResizeableMappedFile;

import java.io.IOException;
import java.io.InputStream;

final class MappedFileInputStream extends InputStream {
  private final ResizeableMappedFile raf;
  private final long limit;
  private final boolean myCheckAccess;
  private int cur;

  MappedFileInputStream(@NonNull ResizeableMappedFile raf, final long pos, final long limit, boolean checkAccess) {
    this.raf = raf;
    this.cur = (int)pos;
    this.limit = limit;
    myCheckAccess = checkAccess;
  }

  @Override
  public int available()
  {
      return (int)(limit - cur);
  }

  @Override
  public void close()
  {
      //do nothing because we want to leave the random access file open.
  }

  @Override
  public int read() throws IOException
  {
    int retval = -1;
    if( cur < limit )
    {
        retval = raf.get(cur++, myCheckAccess);
    }
    return retval;
  }

  @Override
  public int read(byte[] b, int offset, int length ) throws IOException
  {
      //only allow a read of the amount available.
      if( length > available() )
      {
          length = available();
      }

      if( available() > 0 )
      {
        raf.get(cur, b, offset, length, myCheckAccess);
        cur += length;
      }

      return length;
  }

  @Override
  public long skip( long amountToSkip )
  {
      long amountSkipped = Math.min( amountToSkip, available() );
      cur+= amountSkipped;
      return amountSkipped;
  }
}