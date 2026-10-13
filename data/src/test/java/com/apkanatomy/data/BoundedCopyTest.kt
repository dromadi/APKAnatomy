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
    @Test fun handlesEmptyInput() { assertEquals(0L, copyBounded(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), 1)) }
}
