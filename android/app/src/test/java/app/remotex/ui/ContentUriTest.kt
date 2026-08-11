package app.remotex.ui

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ContentUriTest {
    @Test
    fun boundedReadAcceptsExactLimit() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(bytes, readBounded(ByteArrayInputStream(bytes), 4))
    }

    @Test
    fun boundedReadRejectsUnknownOversizeStream() {
        val error = runCatching {
            readBounded(ByteArrayInputStream(ByteArray(12)), 8)
        }.exceptionOrNull() as ContentTooLargeException

        assertEquals(12, error.actualBytes)
        assertEquals(8, error.maxBytes)
    }
}
