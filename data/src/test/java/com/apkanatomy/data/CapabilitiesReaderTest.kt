package com.apkanatomy.data

import com.apkanatomy.domain.BackupRule
import com.apkanatomy.domain.FeatureUse
import com.apkanatomy.domain.ForegroundServiceUse
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** extras/extras.apk is aapt2 output of extras/AndroidManifest.xml and extras/source-res. */
class CapabilitiesReaderTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun readsPlatformRequestsFromTheManifest() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("extras/extras.apk")!!.readBytes()
        val apk = temp.newFile("extras.apk").apply { writeBytes(bytes) }
        val table = ResourceReader.read(apk).first
        val caps = CapabilitiesReader.read(ManifestReader.read(apk, table).root!!, apk, table)

        assertEquals(listOf(FeatureUse("android.hardware.camera", true), FeatureUse("android.hardware.nfc", false)), caps.features)
        assertEquals("3.1", caps.glEsVersion)
        assertEquals(listOf("com.whatsapp"), caps.queries.packages)
        assertEquals(listOf("VIEW https:"), caps.queries.intents)
        assertEquals(listOf("com.example.other.provider"), caps.queries.providers)
        assertEquals(listOf("org.apache.http.legacy" to false), caps.libraries)
        assertEquals(listOf(ForegroundServiceUse("com.example.extras.Tracker", listOf("dataSync", "location", "camera")),
            ForegroundServiceUse("com.example.extras.Special", listOf("specialUse"))), caps.foregroundServices)
        assertEquals("com.example.shared", caps.sharedUserId)
        assertTrue(caps.requestLegacyExternalStorage)
        assertEquals(listOf("res/xml/backup_rules.xml", "res/xml/full_backup.xml"), caps.backupRuleFiles)
        assertEquals(listOf(BackupRule("cloud-backup", true, "sharedpref", "settings.xml"), BackupRule("cloud-backup", false, "database", "tokens.db"),
            BackupRule("device-transfer", true, "file", "."), BackupRule("full-backup-content", false, "sharedpref", "secrets.xml")), caps.backupRules)
        val hijack = caps.foreignTaskAffinities.single()
        assertEquals("com.example.extras.Hijack" to "com.victim.app", hijack.activity to hijack.affinity)
        assertTrue(hijack.allowReparenting && hijack.exported)
        assertEquals(listOf("com.example.extras.Files"), caps.grantUriProviders)
        assertTrue(caps.notes.isEmpty())
    }
}
