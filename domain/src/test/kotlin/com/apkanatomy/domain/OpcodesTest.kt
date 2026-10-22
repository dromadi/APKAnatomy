package com.apkanatomy.domain

import org.junit.Assert.*
import org.junit.Test

class OpcodesTest {
    @Test fun explainsListingLines() {
        val call = explainInstruction("0x1a  invoke-virtual v0, v1 · Landroid/app/Activity;->startActivity(Landroid/content/Intent;)V")!!
        assertEquals("invoke-virtual", call.opcode)
        assertEquals("Memanggil method objek", call.title)
        assertTrue("/range" in explainInstruction("0x0  invoke-static/range v0 .. (6 register)")!!.explanation)
        assertEquals("Mengambil hasil pemanggilan", explainInstruction("0x4  move-result-object v0")!!.title)
        assertTrue("referensi objek" in explainInstruction("0x4  move-result-object v0")!!.explanation)
        assertEquals("Mengambil exception yang ditangkap", explainInstruction("move-exception v1")!!.title)
        assertEquals("Selesai, tanpa nilai kembalian", explainInstruction("0x8  return-void")!!.title)
        assertTrue("-8 sampai 7" in explainInstruction("0x0  const/4 v0")!!.explanation)
        assertTrue("0x02000000" in explainInstruction("0x0  const/high16 v1")!!.explanation)
        assertEquals("Memuat string", explainInstruction("0x0  const-string v0 · \"https://x\"")!!.title)
        assertEquals("Memuat objek Class", explainInstruction("0x0  const-class v1 · Lx/Main;")!!.title)
        assertTrue("null" in explainInstruction("0x2  if-eqz v0 · target=0x8")!!.explanation)
        assertTrue(">=" in explainInstruction("0x2  if-ge v0, v1 · target=0x8")!!.explanation)
        assertTrue("(a = a op b)" in explainInstruction("0x2  add-int/2addr v0, v1")!!.explanation)
        assertTrue("ArithmeticException" in explainInstruction("0x2  div-int/lit8 v0, v1")!!.explanation)
        assertEquals("Konversi tipe", explainInstruction("0x2  int-to-long v0, v1")!!.title)
        assertEquals("Membandingkan dua angka", explainInstruction("0x2  cmp-long v0, v1, v2")!!.title)
        assertEquals("Switch dengan kunci acak", explainInstruction("0x2  sparse-switch v0 · target=0x20")!!.title)
        assertEquals("Lompat", explainInstruction("0x2  goto/16 · target=0x0")!!.title)
        assertEquals("Membaca field static", explainInstruction("0x2  sget-object v0 · Lx/A;->b:Lx/B;")!!.title)
        assertNull(explainInstruction("0x2  frobnicate v0"))
        assertNull(explainInstruction(""))
    }

    @Test fun sourceFileHints() {
        fun cls(type: String, source: String?) = CodeClass(type, null, emptyList(), "base.apk", "classes.dex", source)
        assertEquals("CheckoutViewModel.kt", cls("Lid/a/b;", "CheckoutViewModel.kt").sourceHint())
        assertEquals("CheckoutViewModel.kt", cls("Lid/a/b\$c;", "CheckoutViewModel.kt").sourceHint())
        // Readable names need no hint; R8's placeholders are not real names.
        assertNull(cls("Lid/toko/Checkout;", "Checkout.kt").sourceHint())
        assertEquals("Checkout.kt", cls("Lid/toko/Checkout;", "Checkout.kt").realSourceFile())
        assertNull(cls("Lid/a/b;", "SourceFile").sourceHint())
        assertNull(cls("Lid/a/b;", "r8-map-id-3f2a").sourceHint())
        assertNull(cls("Lid/a/b;", null).sourceHint())
        assertNull(cls("Lid/a/ab;", "ab.java").sourceHint())
    }
}
