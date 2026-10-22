package com.apkanatomy.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apkanatomy.data.AnatomyRepository
import com.apkanatomy.data.AuditStore
import com.apkanatomy.data.HistoryEntry
import com.apkanatomy.data.HistoryStore
import com.apkanatomy.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class UiState(val apps: List<AppEntry> = emptyList(), val report: AnatomyReport? = null,
    val busy: Boolean = false, val error: String? = null,
    /** A slimmed earlier report to compare against; [reportIsBaseline] is true while that same report is open. */
    val baseline: AnatomyReport? = null, val reportIsBaseline: Boolean = false,
    /** The baseline was picked automatically from the history (the last analysis of the same package), not by the user. */
    val baselineAuto: Boolean = false,
    /** Index into ANALYSIS_STEPS while an analysis runs; null otherwise. */
    val step: Int? = null,
    val history: List<HistoryEntry> = emptyList(),
    /** A neutral message such as a cancelled analysis. */
    val info: String? = null,
    /** Results of the last audit of all installed apps; null before the first one. */
    val audit: List<AppAudit>? = null,
    /** (done, total, current app) while an audit runs. */
    val auditProgress: Triple<Int, Int, String>? = null,
    /** When the shown audit was taken; it may come from an earlier session. */
    val auditSavedAt: Long? = null,
    /** Changes against the audit before the shown one; null when there was none to compare. */
    val auditDiff: AuditDiff? = null)
class AnatomyViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AnatomyRepository(application)
    private val historyStore = HistoryStore(File(application.filesDir, "history"))
    private val auditStore = AuditStore(File(application.filesDir, "audit"))
    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    init { reload() }
    fun reload() = runWork {
        val apps = repository.installed()
        val history = withContext(Dispatchers.IO) { historyStore.list() }
        mutable.update { it.copy(apps = apps, history = history) }
        if (mutable.value.audit == null) {
            val last = withContext(Dispatchers.IO) { auditStore.load() }
            if (last != null) mutable.update { it.copy(audit = last.apps, auditSavedAt = last.savedAt) }
        }
    }
    fun inspect(packageName: String) = analyze { repository.analyzeInstalled(packageName, it) }
    fun inspect(uri: Uri) = analyze { repository.analyzeFile(uri, it) }
    /** Stops a running analysis; listing apps or opening history is not cancellable. */
    fun cancel() { if (mutable.value.step != null || mutable.value.auditProgress != null) work?.cancel() }
    fun audit(includeSystem: Boolean) = runWork {
        try {
            mutable.update { it.copy(auditProgress = Triple(0, 0, "")) }
            val results = repository.audit(includeSystem) { done, total, label -> mutable.update { it.copy(auditProgress = Triple(done, total, label)) } }
            val snapshot = AuditSnapshot(System.currentTimeMillis(), includeSystem, results)
            val diff = withContext(Dispatchers.IO) {
                val previous = auditStore.load()
                try { auditStore.save(snapshot) } catch (_: IOException) { }
                previous?.let { auditChanges(it, snapshot) }
            }
            mutable.update { it.copy(audit = results, auditSavedAt = snapshot.savedAt, auditDiff = diff) }
        } catch (cancelled: CancellationException) {
            mutable.update { it.copy(info = "Audit dibatalkan.") }
            throw cancelled
        } finally { mutable.update { it.copy(auditProgress = null) } }
    }
    fun back() {
        if (work?.isActive == true) return
        mutable.update { it.copy(report = null, error = null, info = null) }
        repository.release()
    }
    fun keepAsBaseline() = mutable.update { state -> state.copy(baseline = state.report?.asBaseline(), reportIsBaseline = state.report != null, baselineAuto = false) }
    fun clearBaseline() = mutable.update { it.copy(baseline = null, reportIsBaseline = false, baselineAuto = false) }
    fun openHistory(id: String) = runWork {
        val report = loadHistory(id) ?: return@runWork
        repository.release()
        mutable.update { it.copy(report = report, reportIsBaseline = false) }
    }
    fun baselineFromHistory(id: String) = runWork {
        val report = loadHistory(id) ?: return@runWork
        mutable.update { it.copy(baseline = report.asBaseline(), reportIsBaseline = false,
            info = "Pembanding: ${report.label} ${report.version}. Buka aplikasi atau APK lain, lalu lihat tab Banding.") }
    }
    fun deleteHistory(id: String) = runWork { withContext(Dispatchers.IO) { historyStore.delete(id) }; refreshHistory() }
    fun clearHistory() = runWork { withContext(Dispatchers.IO) { historyStore.clear() }; refreshHistory() }
    suspend fun preview(entry: ArchiveEntry): FilePreview = repository.preview(entry.archive, entry.path)
    suspend fun resources(): ResourceListing = repository.resources()
    override fun onCleared() = repository.release()

    private suspend fun loadHistory(id: String): AnatomyReport? {
        val report = withContext(Dispatchers.IO) { historyStore.load(id) }
        if (report == null) {
            mutable.update { it.copy(error = "Laporan di riwayat tidak dapat dibaca (mungkin dari versi APKAnatomy yang lebih lama) dan sudah dihapus.") }
            refreshHistory()
        }
        return report
    }
    private suspend fun refreshHistory() {
        val history = withContext(Dispatchers.IO) { historyStore.list() }
        mutable.update { it.copy(history = history) }
    }
    private fun analyze(run: suspend (onStep: (Int) -> Unit) -> AnatomyReport) = runWork {
        try {
            val report = run { step -> mutable.update { it.copy(step = step) } }
            mutable.update { it.copy(report = report, reportIsBaseline = false, step = null) }
            compareWithPrevious(report)
            val saved = withContext(Dispatchers.IO) {
                try { historyStore.save(report, System.currentTimeMillis()) } catch (_: IOException) { false }
            }
            if (!saved) mutable.update { it.copy(info = "Laporan terlalu besar atau gagal disimpan ke riwayat.") }
            refreshHistory()
        } catch (cancelled: CancellationException) {
            mutable.update { it.copy(info = "Analisis dibatalkan.") }
            throw cancelled
        } finally { mutable.update { it.copy(step = null) } }
    }
    /**
     * Before the new report replaces it in the history, the last analysis of the same package becomes the
     * Banding baseline, unless the user picked a baseline themselves.
     */
    private suspend fun compareWithPrevious(report: AnatomyReport) {
        val state = mutable.value
        if (state.baseline != null && !state.baselineAuto) return
        val previous = withContext(Dispatchers.IO) {
            historyStore.list().firstOrNull { it.packageName == report.packageName }?.let { entry -> historyStore.load(entry.id)?.let { entry to it } }
        }
        if (previous == null) {
            if (state.baselineAuto) mutable.update { it.copy(baseline = null, baselineAuto = false) }
            return
        }
        val (entry, old) = previous
        val baseline = old.asBaseline()
        val changes = compareReports(baseline, report, System.currentTimeMillis()).sumOf { it.changes.size }
        val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(entry.savedAt))
        mutable.update {
            it.copy(baseline = baseline, baselineAuto = true, reportIsBaseline = false,
                info = if (changes == 0) "Tidak ada perubahan sejak analisis $date (versi ${entry.version})."
                else "Dibandingkan otomatis dengan analisis $date (versi ${entry.version}): $changes perubahan. Lihat tab Banding.")
        }
    }

    private fun runWork(block: suspend () -> Unit) {
        if (work?.isActive == true) return
        work = viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null, info = null) }
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(error = failure.message ?: "Analisis gagal.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
