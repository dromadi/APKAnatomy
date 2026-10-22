package com.apkanatomy.data

import com.apkanatomy.domain.NoteLevel
import com.apkanatomy.domain.pageSizeNote
import com.apkanatomy.domain.pageSizeProblems
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fixtures in resources/elf (libraries renamed to .so.bin): native.c built with clang 18 + lld for android26 (arm64 with
 * max-page-size 4096/16384, armv7, and an unstripped arm64 build), and APKs aligned with
 * `zipalign -P 16` and `zipalign -p`. Expected values match readelf and `zipalign -c`.
 */
class NativeScannerTest {
    @get:Rule val temp = TemporaryFolder()
    // AGP drops *.so from unit-test resources, so the libraries are stored as *.so.bin.
    private fun bytes(name: String) = javaClass.classLoader!!.getResourceAsStream("elf/" + if (name.endsWith(".so")) "$name.bin" else name)!!.readBytes()
    private fun file(name: String) = temp.newFile(name).apply { writeBytes(bytes(name)) }

    @Test fun readsAlignmentDependenciesAndJniExports() {
        val elf = ElfReader.parse(bytes("arm64-4k.so"))
        assertEquals(64 to "AArch64", elf.bits to elf.machine)
        assertEquals(listOf(4096L, 4096L, 4096L), elf.loadAligns)
        assertEquals("libdemo.so" to listOf("libdep.so"), elf.soname to elf.needed)
        assertEquals(setOf("Java_com_example_demo_NativeBridge_add", "Java_com_example_demo_Native_1Util_version__I", "JNI_OnLoad"), elf.exports.toSet())
        assertFalse(elf.hasSymbolTable || elf.hasDebugInfo)
        assertEquals(listOf(16384L, 16384L, 16384L), ElfReader.parse(bytes("arm64-16k.so")).loadAligns)
        val arm32 = ElfReader.parse(bytes("armv7-4k.so"))
        assertEquals(32 to "ARM", arm32.bits to arm32.machine)
        assertEquals(listOf("libdep.so"), arm32.needed)
        val debug = ElfReader.parse(bytes("arm64-16k-debug.so"))
        assertTrue(debug.hasSymbolTable && debug.hasDebugInfo)
    }

    @Test fun rejectsNonElfAndTruncatedFiles() {
        assertThrows(ElfReader.ElfException::class.java) { ElfReader.parse("bukan elf, hanya teks yang cukup panjang untuk header".toByteArray()) }
        val full = bytes("arm64-16k.so")
        for (length in listOf(52, 200, full.size / 2)) assertThrows(ElfReader.ElfException::class.java) { ElfReader.parse(full.copyOf(length)) }
    }

    @Test fun pageSizeReadinessCombinesElfAndZipAlignment() {
        val aligned = NativeScanner().scan(mapOf("base.apk" to file("native-16k.apk")), extractNativeLibs = false)
        val arm64 = aligned.libraries.single { it.abi == "arm64-v8a" }
        assertEquals(16384L, arm64.zipDataOffset)
        assertTrue(arm64.stored && arm64.elfAligned16k == true && arm64.zipAligned16k == true)
        assertEquals(listOf("com.example.demo.NativeBridge.add", "com.example.demo.Native_Util.version"), arm64.jniMethods)
        assertTrue(arm64.hasJniOnLoad)
        assertEquals(NoteLevel.BAIK, aligned.pageSizeNote()!!.level) // the 4 KB armv7 library is 32-bit, so it does not count

        val zipFile = file("native-4k-zip.apk")
        val zip4k = NativeScanner().scan(mapOf("base.apk" to zipFile), extractNativeLibs = false)
        assertEquals("offset ZIP 4096 bukan kelipatan 16 KB", zip4k.pageSizeProblems().single().second)
        assertEquals(NoteLevel.PERHATIAN, zip4k.pageSizeNote()!!.level)
        // When libraries are extracted at install, the ZIP offset does not matter.
        assertNull(NativeScanner().scan(mapOf("base.apk" to zipFile), extractNativeLibs = true).pageSizeProblems().firstOrNull())
    }
}
