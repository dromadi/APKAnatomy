package com.apkanatomy.domain

/** A quick look at one installed app, for comparing many apps at once. */
data class AppAudit(
    val packageName: String, val label: String, val system: Boolean,
    val versionName: String, val targetSdk: Int,
    val debuggable: Boolean, val allowBackup: Boolean, val cleartextTraffic: Boolean,
    val installer: String?,
    val permissions: List<String>,
    /** Exported, enabled components that any app can reach without a permission (the launcher activity included). */
    val openComponents: Int,
    /** Tracker SDK names with their purpose, from the DEX packages. */
    val trackers: List<Pair<String, String>>,
    /** Set when the code could not be read, e.g. the DEX files exceed the budget. */
    val note: String? = null,
)

/** One reason an app stands out, with the points it adds; [filter] names the list filter it belongs to. */
data class AuditReason(val text: String, val points: Int, val filter: String)

const val AUDIT_TRACKERS = "Pelacak"
const val AUDIT_DATA = "Data pribadi"
const val AUDIT_OLD = "Target lama"
const val AUDIT_DEBUG = "Debuggable"
const val AUDIT_SIDELOAD = "Bukan dari toko"
const val AUDIT_EXPOSED = "Komponen terbuka"

private val STORES = setOf("com.android.vending", "org.fdroid.fdroid", "org.fdroid.basic", "com.aurora.store", "com.sec.android.app.samsungapps",
    "com.huawei.appmarket", "com.xiaomi.market", "com.xiaomi.mipicks", "com.amazon.venezia")

/**
 * Why an app deserves a closer look. Points only order the list; they are not a security rating, and a high
 * count can be expected (a messenger needs contacts, a maps app needs location).
 */
fun AppAudit.reasons(): List<AuditReason> = buildList {
    if (debuggable) add(AuditReason("debuggable: aplikasi lain lewat adb dapat membaca datanya", 3, AUDIT_DEBUG))
    if (targetSdk in 1..28) add(AuditReason("targetSdk $targetSdk: berjalan dengan aturan lama (di bawah Android 10)", 2, AUDIT_OLD))
    trackers.groupBy({ it.second }, { it.first }).forEach { (purpose, names) -> add(AuditReason("Pelacak $purpose: ${names.joinToString()}", names.size, AUDIT_TRACKERS)) }
    val data = dataCategories(permissions)
    if (data.isNotEmpty()) add(AuditReason("Akses data: ${data.joinToString()}", minOf(data.size, 5), AUDIT_DATA))
    if (cleartextTraffic && targetSdk >= 28) add(AuditReason("Mengizinkan HTTP tanpa enkripsi", 1, AUDIT_EXPOSED))
    if (openComponents > 1) add(AuditReason("$openComponents komponen dapat dipanggil aplikasi lain tanpa izin", 1, AUDIT_EXPOSED))
    if (!system && installer !in STORES) add(AuditReason("Dipasang dari ${describeInstaller(installer)}", 1, AUDIT_SIDELOAD))
}

fun AppAudit.points(): Int = reasons().sumOf { it.points }

/** Counts for the summary line: how many apps fall under each filter. */
fun List<AppAudit>.auditSummary(): Map<String, Int> =
    listOf(AUDIT_TRACKERS, AUDIT_DATA, AUDIT_OLD, AUDIT_DEBUG, AUDIT_SIDELOAD, AUDIT_EXPOSED).associateWith { f -> count { a -> a.reasons().any { it.filter == f } } }
