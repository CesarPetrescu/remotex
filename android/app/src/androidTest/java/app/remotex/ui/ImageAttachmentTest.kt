package app.remotex.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import app.remotex.security.TokenStore
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class ImageAttachmentTest {
    @Test
    fun platformDecodableUnsupportedFormatBecomesJpeg() {
        // A one-pixel BMP: Android can decode it, but Codex prompt images do
        // not accept BMP on the wire.
        val bmp = hex(
            "424d3a000000000000003600000028000000010000000100000001001800" +
                "0000000004000000130b0000130b000000000000000000000000ff00",
        )

        assertNull(detectedImageMime(bmp))
        val normalized = normalizeImageAttachment(bmp, maxBytes = 1024)

        assertEquals("image/jpeg", normalized.mime)
        assertEquals("image/jpeg", detectedImageMime(normalized.bytes))
    }

    @Test
    fun oversizedSupportedImageIsReencodedUnderTheWireBudget() {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val random = Random(7)
        val pixels = IntArray(512 * 512) { random.nextInt() or 0xFF000000.toInt() }
        bitmap.setPixels(pixels, 0, 512, 0, 0, 512, 512)
        val png = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()

        val normalized = normalizeImageAttachment(png, maxBytes = 60_000)

        assertEquals("image/jpeg", normalized.mime)
        assertTrue(normalized.bytes.size <= 60_000)
    }

    @Test
    fun fullAttachmentBudgetDoesNotLeavePreparationStuck() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val store = ViewModelStore()
        lateinit var viewModel: RemotexViewModel

        try {
            instrumentation.runOnMainSync {
                viewModel = RemotexViewModel(
                    application = application,
                    relayUrl = "http://127.0.0.1:1",
                    tokenStore = EmptyTokenStore,
                )
                store.put("image-budget", viewModel)
                viewModel.replacePendingImagesForTest(
                    listOf(
                        PendingImage(
                            uri = "content://existing",
                            mime = "image/jpeg",
                            base64 = "",
                            label = "existing.jpg",
                            bytes = MAX_IMAGE_ATTACHMENT_BYTES,
                        ),
                    ),
                )
                viewModel.attachImage(Uri.parse("content://never-read"))
            }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (viewModel.state.value.imagePreparing && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertTrue(!viewModel.state.value.imagePreparing)
            assertTrue(viewModel.state.value.error?.startsWith("image:") == true)
            assertEquals(1, viewModel.state.value.pendingImages.size)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    private fun hex(value: String): ByteArray = value.chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()

    private object EmptyTokenStore : TokenStore {
        override fun load(): String = ""
        override fun save(token: String) = Unit
        override fun clear() = Unit
    }
}
