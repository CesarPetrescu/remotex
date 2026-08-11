package app.remotex.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.InputStream

data class OpenableMetadata(
    val displayName: String? = null,
    val size: Long? = null,
)

fun ContentResolver.openableMetadata(uri: Uri): OpenableMetadata =
    query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use OpenableMetadata()
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        OpenableMetadata(
            displayName = nameIndex.takeIf { it >= 0 }?.let(cursor::getString),
            size = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong),
        )
    } ?: OpenableMetadata()

class ContentTooLargeException(val actualBytes: Long, val maxBytes: Long) :
    IllegalArgumentException("${formatBytes(actualBytes)} exceeds the ${formatBytes(maxBytes)} limit")

/** Reads at most [maxBytes], even when a provider omits OpenableColumns.SIZE. */
fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
    require(maxBytes >= 0L)
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE.toLong()).toInt())
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        val next = total + read
        if (next > maxBytes) throw ContentTooLargeException(next, maxBytes)
        output.write(buffer, 0, read)
        total = next
    }
    return output.toByteArray()
}
