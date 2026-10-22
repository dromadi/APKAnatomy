package com.apkanatomy.domain

import org.junit.Assert.*
import org.junit.Test

/** Fake secrets are assembled at runtime so no secret-shaped literal sits in the source (and trips secret scanners). */
class TraceCollectorTest {
    private val googleKey = "AI" + "za" + "SyA1234567890abcdefghijklmnopqrstuv"
    private val awsKey = "AK" + "IA" + "IOSFODNN7EXAMPLE"
    private val stripeKey = "sk" + "_live_" + "51HabcdefghijklmnopqrstuvWXYZ"
    private val midtransKey = "SB-Mid-" + "server-" + "AbCdEfGhIjKlMnOpQrSt"
    private val privateKey = "-----BEGIN RSA " + "PRIVATE KEY-----\nMIIE..."

    private fun collect(vararg strings: Pair<String, String>) = TraceCollector().apply { strings.forEach { (text, where) -> scan(text, where) } }.result()

    @Test fun groupsUrlsByHostWithoutQueriesOrCredentials() {
        val report = collect(
            "https://api.contoh.id/v1/orders?key=$googleKey#x" to "classes.dex",
            "load http://user:pass@cdn.contoh.id:8080/img.png now" to "assets/config.json",
            "https://api.contoh.id/v1/users" to "classes2.dex",
            "http://schemas.android.com/apk/res/android" to "classes.dex",
            "https://abc.firebaseio.com/" to "res: string/firebase_database_url",
            "http://10.0.2.2:3000/dev" to "classes.dex")
        val hosts = report.hosts.associateBy { it.host }
        assertEquals(setOf("api.contoh.id", "cdn.contoh.id", "abc.firebaseio.com", "10.0.2.2"), hosts.keys)
        assertEquals(listOf("https://api.contoh.id/v1/orders", "https://api.contoh.id/v1/users"), hosts.getValue("api.contoh.id").examples)
        assertEquals(2, hosts.getValue("api.contoh.id").count)
        assertEquals(listOf("http://…@cdn.contoh.id:8080/img.png"), hosts.getValue("cdn.contoh.id").examples)
        assertTrue(hosts.getValue("cdn.contoh.id").cleartext)
        assertEquals("Firebase Realtime Database", hosts.getValue("abc.firebaseio.com").service)
        assertTrue(hosts.getValue("10.0.2.2").local)
        assertEquals("abc.firebaseio.com", report.hosts.first().host) // known services first
    }

    @Test fun findsAndMasksSecrets() {
        val report = collect(
            "https://api.contoh.id/v1/orders?key=$googleKey" to "classes.dex",
            awsKey to "assets/aws.json",
            "not${awsKey}X" to "classes.dex",
            stripeKey to "classes.dex",
            privateKey to "assets/key.pem",
            midtransKey to "res: string/midtrans")
        val byType = report.secrets.associateBy { it.type }
        assertEquals(setOf("Google API key", "AWS access key ID", "Stripe secret key", "Private key", "Midtrans server key"), byType.keys)
        assertEquals("AIzaSy…stuv", byType.getValue("Google API key").masked)
        assertEquals(NoteLevel.INFO, byType.getValue("Google API key").level)
        assertEquals("AKIA…MPLE", byType.getValue("AWS access key ID").masked)
        assertEquals(listOf("assets/aws.json"), byType.getValue("AWS access key ID").locations)
        assertEquals("-----BEGIN RSA PRIVATE KEY----- (isi kunci disembunyikan)", byType.getValue("Private key").masked)
        assertEquals(NoteLevel.PERHATIAN, report.secrets.first().level)
        assertFalse(report.secrets.any { it.masked.contains("1234567890abcdefghijklmnop") })
        assertFalse(report.hosts.single().examples.single().contains("AIza"))
    }

    @Test fun linksMethodsThatLoadKnownHostsAndSecrets() {
        val collector = TraceCollector()
        collector.scan("https://api.contoh.id/v1/login?x=1", "classes.dex")
        collector.scan(awsKey, "classes.dex")
        collector.reference("https://api.contoh.id/v1/login?x=1", "m1")
        collector.reference("https://unknown.contoh.id/", "m2") // not in the string pool: nothing is added
        collector.reference(awsKey, "m3")
        val report = collector.result()
        assertEquals(listOf("m1"), report.hosts.single().usedBy)
        assertEquals(listOf("m3"), report.secrets.single().usedBy)
    }

    @Test fun staysBounded() {
        val collector = TraceCollector(maxHosts = 2)
        (1..5).forEach { collector.scan("https://h$it.contoh.id/", "classes.dex") }
        val report = collector.result()
        assertEquals(2, report.hosts.size)
        assertEquals(listOf("Batas 2 host tercapai."), report.notes)
    }
}
