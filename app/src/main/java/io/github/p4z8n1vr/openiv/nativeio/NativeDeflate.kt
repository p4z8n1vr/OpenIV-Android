package io.github.p4z8n1vr.openiv.nativeio

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Streams a raw (RFC 1951) DEFLATE payload from a bounded archive entry. */
object NativeDeflate {
    private const val BUFFER_SIZE = 32 * 1024

    private val nativeAvailable = try {
        System.loadLibrary("openiv_native")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    /** Leaves both streams open. Trailing stored padding is allowed. */
    @Throws(IOException::class)
    fun inflate(
        input: InputStream,
        output: OutputStream,
        compressedSize: Long,
        expectedSize: Long? = null,
        maxOutputSize: Long = Long.MAX_VALUE,
    ): Long {
        require(compressedSize >= 0)
        require(expectedSize == null || expectedSize >= 0)
        require(maxOutputSize >= 0)
        if (expectedSize != null && expectedSize > maxOutputSize) {
            throw IOException("Decompressed payload exceeds its size limit")
        }

        val outputLimit = expectedSize ?: maxOutputSize
        val written = if (nativeAvailable) {
            inflateNative(input, output, compressedSize, outputLimit)
        } else {
            inflateJvm(input, output, compressedSize, outputLimit)
        }
        if (expectedSize != null && written != expectedSize) {
            throw IOException("Decompressed payload size does not match its archive entry")
        }
        return written
    }

    private external fun inflateNative(
        input: InputStream,
        output: OutputStream,
        compressedSize: Long,
        outputLimit: Long,
    ): Long

    private fun inflateJvm(
        input: InputStream,
        output: OutputStream,
        compressedSize: Long,
        outputLimit: Long,
    ): Long {
        val source = ByteArray(BUFFER_SIZE)
        val destination = ByteArray(BUFFER_SIZE)
        val inflater = Inflater(true)
        var remaining = compressedSize
        var written = 0L

        try {
            while (!inflater.finished()) {
                if (inflater.needsInput() && remaining > 0) {
                    val requested = minOf(source.size.toLong(), remaining).toInt()
                    val count = input.read(source, 0, requested)
                    if (count < 0) throw IOException("Truncated raw DEFLATE payload")
                    if (count == 0 || count > requested) {
                        throw IOException("Input stream returned an invalid byte count")
                    }
                    remaining -= count
                    inflater.setInput(source, 0, count)
                }

                val inputBefore = inflater.bytesRead
                val count = inflater.inflate(destination)
                if (count.toLong() > outputLimit - written) {
                    throw IOException("Decompressed payload exceeds its size limit")
                }
                if (count > 0) {
                    output.write(destination, 0, count)
                    written += count
                } else if (!inflater.finished() && inflater.bytesRead == inputBefore) {
                    throw IOException("Incomplete raw DEFLATE payload")
                }
            }
        } catch (exception: DataFormatException) {
            throw IOException("Invalid raw DEFLATE payload", exception)
        } finally {
            inflater.end()
        }
        return written
    }
}
