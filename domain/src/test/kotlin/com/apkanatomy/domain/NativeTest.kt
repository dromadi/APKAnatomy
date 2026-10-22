package com.apkanatomy.domain

import org.junit.Assert.*
import org.junit.Test

class NativeTest {
    @Test fun demanglesJniNames() {
        assertEquals("com.example.Foo.bar", demangleJni("Java_com_example_Foo_bar"))
        assertEquals("com.example.Native_Util.version", demangleJni("Java_com_example_Native_1Util_version__I"))
        assertEquals("com.example.Café.go", demangleJni("Java_com_example_Caf_000e9_go"))
        assertNull(demangleJni("JNI_OnLoad"))
        assertNull(demangleJni("Java_broken_0zz"))
    }

    private fun lib(bits: Int, align: Long, stored: Boolean = true, offset: Long = 16384) =
        NativeLibInfo("base.apk", "lib/x/liba.so", if (bits == 64) "arm64-v8a" else "armeabi-v7a", 1, "AArch64", bits, align, stored, offset, null, emptyList(), emptyList(), false, false, false)

    @Test fun noteOnlyCountsSixtyFourBitLibraries() {
        assertNull(NativeReport(listOf(lib(32, 4096))).pageSizeNote())
        assertEquals(NoteLevel.BAIK, NativeReport(listOf(lib(64, 16384), lib(32, 4096))).pageSizeNote()!!.level)
        val bad = NativeReport(listOf(lib(64, 4096, offset = 4096)), extractNativeLibs = false)
        assertEquals("segmen LOAD rata 4 KB dan offset ZIP 4096 bukan kelipatan 16 KB", bad.pageSizeProblems().single().second)
        assertEquals(NoteLevel.PERHATIAN, bad.pageSizeNote()!!.level)
    }
}
