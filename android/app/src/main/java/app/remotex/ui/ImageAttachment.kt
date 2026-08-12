package app.remotex.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class NormalizedImage(
    val bytes: ByteArray,
    val mime: String,
)

/**
 * Codex currently accepts PNG, JPEG, GIF, and WebP prompt images. Android's
 * system picker can also return HEIC/HEIF/AVIF, so unsupported (and oversized)
 * selections are decoded by the platform and converted to a bounded JPEG.
 */
internal fun normalizeImageAttachment(
    source: ByteArray,
    maxBytes: Long,
): NormalizedImage {
    require(maxBytes > 0L)

    detectedImageMime(source)?.let { mime ->
        if (source.size <= maxBytes) return NormalizedImage(source, mime)
    }

    var bitmap = decodeForNormalization(source)
        ?: throw IllegalArgumentException(
            "unsupported image; choose PNG, JPEG, GIF, WebP, or a device-supported HEIC/AVIF",
        )

    try {
        if (bitmap.width > IMAGE_MAX_DIMENSION || bitmap.height > IMAGE_MAX_DIMENSION) {
            val ratio = min(
                IMAGE_MAX_DIMENSION.toFloat() / bitmap.width,
                IMAGE_MAX_DIMENSION.toFloat() / bitmap.height,
            )
            bitmap = bitmap.replaceWithScaled(ratio)
        }
        if (bitmap.hasAlpha()) {
            val flattened = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Canvas(flattened).apply {
                drawColor(Color.WHITE)
                drawBitmap(bitmap, 0f, 0f, null)
            }
            bitmap.recycle()
            bitmap = flattened
        }

        var encoded = bitmap.encodeJpeg(86)
        while (encoded.size > maxBytes && maxOf(bitmap.width, bitmap.height) > 256) {
            val targetRatio = (sqrt(maxBytes.toDouble() / encoded.size) * 0.9)
                .coerceIn(0.5, 0.85)
                .toFloat()
            bitmap = bitmap.replaceWithScaled(targetRatio)
            encoded = bitmap.encodeJpeg(82)
        }
        if (encoded.size > maxBytes) {
            encoded = bitmap.encodeJpeg(65)
        }
        if (encoded.size > maxBytes) {
            throw ContentTooLargeException(encoded.size.toLong(), maxBytes)
        }
        return NormalizedImage(encoded, "image/jpeg")
    } finally {
        bitmap.recycle()
    }
}

/**
 * ImageDecoder handles modern gallery formats (notably HEIC/HEIF and AVIF)
 * that BitmapFactory may reject even when the system photo picker can show
 * them. Decode directly to the final prompt dimensions to avoid allocating a
 * full-resolution camera bitmap.
 */
private fun decodeForNormalization(source: ByteArray): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        try {
            return ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(ByteBuffer.wrap(source)),
            ) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width
                val height = info.size.height
                if (width > IMAGE_MAX_DIMENSION || height > IMAGE_MAX_DIMENSION) {
                    val ratio = min(
                        IMAGE_MAX_DIMENSION.toFloat() / width,
                        IMAGE_MAX_DIMENSION.toFloat() / height,
                    )
                    decoder.setTargetSize(
                        (width * ratio).roundToInt().coerceAtLeast(1),
                        (height * ratio).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        } catch (_: Exception) {
            // Older vendor decoders vary; retain BitmapFactory as a fallback.
        }
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > IMAGE_MAX_DIMENSION * 2 ||
        bounds.outHeight / sampleSize > IMAGE_MAX_DIMENSION * 2
    ) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeByteArray(
        source,
        0,
        source.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    )
}

internal fun detectedImageMime(bytes: ByteArray): String? = when {
    bytes.size >= 3 &&
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() ->
        "image/jpeg"
    bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
    ) -> "image/png"
    bytes.size >= 6 && (
        bytes.copyOfRange(0, 6).decodeToString() == "GIF87a" ||
            bytes.copyOfRange(0, 6).decodeToString() == "GIF89a"
        ) -> "image/gif"
    bytes.size >= 12 &&
        bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
        bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> "image/webp"
    else -> null
}

private fun Bitmap.replaceWithScaled(ratio: Float): Bitmap {
    val next = Bitmap.createScaledBitmap(
        this,
        (width * ratio).roundToInt().coerceAtLeast(1),
        (height * ratio).roundToInt().coerceAtLeast(1),
        true,
    )
    if (next !== this) recycle()
    return next
}

private fun Bitmap.encodeJpeg(quality: Int): ByteArray = ByteArrayOutputStream().use { output ->
    check(compress(Bitmap.CompressFormat.JPEG, quality, output)) { "image encoding failed" }
    output.toByteArray()
}

private const val IMAGE_MAX_DIMENSION = 2048
