package com.apkanatomy.data
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
class BoundedCopyTest {
    @Test fun acceptsExactlyAtLimit() {
        val source = ByteArray(8193) { 42 }
        val output = ByteArrayOutputStream()
        assertEquals(8193L, copyBounded(ByteArrayInputStream(source), output, 8193))
        assertArrayEquals(source, output.toByteArray())
    }
    @Test fun rejectsOversizedStreamWithoutWritingPastLimit() {
        val output = ByteArrayOutputStream()
        try {
            copyBounded(ByteArrayInputStream(ByteArray(8193)), output, 8192)
            fail("Expected size rejection")
        } catch (_: IllegalArgumentException) { assertEquals(8192, output.size()) }
    }
    @Test fun checksForCancellationWhileCopying() {
        var checks = 0
        try {
            copyBounded(ByteArrayInputStream(ByteArray(3 * 1024 * 1024)), ByteArrayOutputStream(), 8L * 1024 * 1024) {
                if (++checks == 2) throw java.util.concurrent.CancellationException()
            }
            fail("Expected cancellation")
        } catch (_: java.util.concurrent.CancellationException) { assertEquals(2, checks) }
    }
    @Test fun handlesEmptyInput() { assertEquals(0L, copyBounded(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), 1)) }
}
