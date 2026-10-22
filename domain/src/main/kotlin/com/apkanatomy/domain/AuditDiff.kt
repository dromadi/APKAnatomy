package com.apkanatomy.domain

/** A finished audit as it is kept on the device, to compare the next one against. */
data class AuditSnapshot(val savedAt: Long, val includeSystem: Boolean, val apps: List<AppAudit>)

/** What changed for one app between two audits; [attention] marks changes worth a look (new trackers, new data access, debuggable). */
data class AuditChange(val packageName: String, val label: String, val kind: String, val details: List<String>, val attention: Boolean)

/** Changes since the audit taken at [since]. */
data class AuditDiff(val since: Long, val changes: List<AuditChange>)

const val CHANGE_NEW = "Baru dipasang"
const val CHANGE_REMOVED = "Dihapus"
const val CHANGE_UPDATED = "Diperbarui"
const val CHANGE_CHANGED = "Berubah"

/**
 * Apps installed, removed or changed between [old] and [new]. When only one of the two audits included
 * system apps, system apps are left out of the comparison so they do not show up as installed or removed.
 * Trackers are compared only when the code of both versions could be read.
 */
fun auditChanges(old: AuditSnapshot, new: AuditSnapshot): AuditDiff {
    val bothSystem = old.includeSystem && new.includeSystem
    fun scope(s: AuditSnapshot) = s.apps.filter { bothSystem || !it.system }.associateBy { it.packageName }
    val before = scope(old)
    val after = scope(new)
    val changes = mutableListOf<AuditChange>()
    after.values.filter { it.packageName !in before }.forEach { app ->
        val data = dataCategories(app.permissions)
        changes += AuditChange(app.packageName, app.label, CHANGE_NEW, listOfNotNull(
            "versi ${app.versionName}, dari ${describeInstaller(app.installer)}",
            data.takeIf { it.isNotEmpty() }?.let { "Akses data: ${it.joinToString()}" },
            app.trackers.takeIf { it.isNotEmpty() }?.let { t -> "Pelacak: ${t.joinToString { it.first }}" },
        ), attention = app.trackers.isNotEmpty() || data.isNotEmpty() || app.debuggable)
    }
    before.values.filter { it.packageName !in after }.forEach { app ->
        changes += AuditChange(app.packageName, app.label, CHANGE_REMOVED, listOf("versi terakhir ${app.versionName}"), attention = false)
    }
    after.values.forEach { app ->
        val was = before[app.packageName] ?: return@forEach
        val details = mutableListOf<String>()
        var attention = false
        if (was.versionName != app.versionName) details += "versi ${was.versionName} → ${app.versionName}"
        if (was.targetSdk != app.targetSdk) details += "targetSdk ${was.targetSdk} → ${app.targetSdk}"
        val added = app.permissions - was.permissions.toSet()
        val removed = was.permissions - app.permissions.toSet()
        if (added.isNotEmpty()) details += "Izin baru: ${added.joinToString { it.substringAfterLast('.') }}"
        if (removed.isNotEmpty()) details += "Izin dilepas: ${removed.joinToString { it.substringAfterLast('.') }}"
        val newData = dataCategories(app.permissions) - dataCategories(was.permissions).toSet()
        if (newData.isNotEmpty()) { details += "Akses data baru: ${newData.joinToString()}"; attention = true }
        if (app.note == null && was.note == null) {
            val names = { a: AppAudit -> a.trackers.map { it.first }.toSet() }
            val newTrackers = names(app) - names(was)
            val goneTrackers = names(was) - names(app)
            if (newTrackers.isNotEmpty()) { details += "Pelacak baru: ${newTrackers.joinToString()}"; attention = true }
            if (goneTrackers.isNotEmpty()) details += "Pelacak hilang: ${goneTrackers.joinToString()}"
        }
        if (app.debuggable && !was.debuggable) { details += "Sekarang debuggable"; attention = true }
        if (app.installer != was.installer) { details += "Sumber pemasangan: ${describeInstaller(was.installer)} → ${describeInstaller(app.installer)}"; attention = true }
        if (details.isNotEmpty()) changes += AuditChange(app.packageName, app.label,
            if (was.versionName != app.versionName) CHANGE_UPDATED else CHANGE_CHANGED, details, attention)
    }
    val order = listOf(CHANGE_NEW, CHANGE_UPDATED, CHANGE_CHANGED, CHANGE_REMOVED)
    return AuditDiff(old.savedAt, changes.sortedWith(compareBy({ !it.attention }, { order.indexOf(it.kind) }, { it.label.lowercase() })))
}
