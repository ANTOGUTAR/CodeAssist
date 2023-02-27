package org.jetbrains.kotlin.com.intellij.util.io

import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.nio.file.Path
import java.security.MessageDigest
import java.security.Provider
import java.security.SecureRandom
import kotlin.io.path.inputStream

object DigestUtil {
  private val sunSecurityProvider: Provider = java.security.Security.getProvider("SUN")

  @JvmStatic
  val random: SecureRandom by lazy { SecureRandom() }

  // http://stackoverflow.com/a/41156 - shorter than UUID, but secure
  @JvmStatic
  fun randomToken(): String = BigInteger(130, random).toString(32)

  @JvmStatic
  fun md5(): MessageDigest = md5.cloneDigest()
  private val md5 by lazy(LazyThreadSafetyMode.PUBLICATION) { getMessageDigest("MD5") }

  @JvmStatic
  fun sha1(): MessageDigest = sha1.cloneDigest()
  private val sha1 by lazy(LazyThreadSafetyMode.PUBLICATION) { getMessageDigest("SHA-1") }

  @JvmStatic
  fun sha256(): MessageDigest = sha256.cloneDigest()
  private val sha256 by lazy(LazyThreadSafetyMode.PUBLICATION) { getMessageDigest("SHA-256") }

  @JvmStatic
  fun sha512(): MessageDigest = sha512.cloneDigest()
  private val sha512 by lazy(LazyThreadSafetyMode.PUBLICATION) { getMessageDigest("SHA-512") }

  @JvmStatic
  fun digestToHash(digest: MessageDigest) = bytesToHex(digest.digest())

  @JvmStatic
  fun sha256Hex(input: ByteArray): String = bytesToHex(sha256().digest(input))

  @JvmStatic
  fun sha256Hex(file: Path): String {
    try {
      val digest = sha256()
      val buffer = ByteArray(512 * 1024)
      file.inputStream().use {
        updateContentHash(digest, it, buffer)
      }
      return bytesToHex(digest.digest())
    }
    catch (e: IOException) {
      throw RuntimeException("Failed to read $file. ${e.message}", e)
    }
  }

  @JvmStatic
  fun sha1Hex(input: ByteArray): String = bytesToHex(sha1().digest(input))

  @JvmStatic
  fun md5Hex(input: ByteArray): String = bytesToHex(md5().digest(input))

  /**
   * Digest cloning is faster than requesting a new one from [MessageDigest.getInstance].
   * This approach is used in Guava as well.
   */
  private fun MessageDigest.cloneDigest(): MessageDigest {
    return try {
      clone() as MessageDigest
    }
    catch (e: CloneNotSupportedException) {
      throw IllegalArgumentException("Message digest is not cloneable: $this")
    }
  }

  @JvmStatic
  @JvmOverloads
  fun updateContentHash(digest: MessageDigest, file: Path, buffer: ByteArray = ByteArray(512 * 1024)) {
    try {
      file.inputStream().use {
        updateContentHash(digest, it, buffer)
      }
    }
    catch (e: IOException) {
      throw RuntimeException("Failed to read $file. ${e.message}", e)
    }
  }

  @JvmStatic
  @JvmOverloads
  fun updateContentHash(digest: MessageDigest, inputStream: InputStream, buffer: ByteArray = ByteArray(512 * 1024)) {
    try {
      while (true) {
        val sz = inputStream.read(buffer)
        if (sz <= 0) {
          break
        }
        digest.update(buffer, 0, sz)
      }
    }
    catch (e: IOException) {
      throw RuntimeException("Failed to read stream. ${e.message}", e)
    }
  }

  private fun getMessageDigest(algorithm: String): MessageDigest {
    return MessageDigest.getInstance(algorithm, sunSecurityProvider)
  }
}

private fun bytesToHex(data: ByteArray): String {
  val l = data.size
  val chars = CharArray(l shl 1)
  var i = 0
  var j = 0
  while (i < l) {
    val v = data[i].toInt()
    chars[j++] = HEX_ARRAY[0xF0 and v ushr 4]
    chars[j++] = HEX_ARRAY[0x0F and v]
    i++
  }
  return String(chars)
}

private val HEX_ARRAY = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')