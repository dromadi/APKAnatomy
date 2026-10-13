package com.apkanatomy.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apkanatomy.data.AnatomyRepository
import com.apkanatomy.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(val apps: List<AppEntry> = emptyList(), val report: AnatomyReport? = null,
    val busy: Boolean = false, val error: String? = null)
class AnatomyViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AnatomyRepository(application)
    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    init { reload() }
    fun reload() = runWork {
        val apps = repository.installed()
        mutable.update { it.copy(apps = apps) }
    }
    fun inspect(packageName: String) = runWork {
        val report = repository.analyzeInstalled(packageName)
        mutable.update { it.copy(report = report) }
    }
    fun inspect(uri: Uri) = runWork {
        val report = repository.analyzeFile(uri)
        mutable.update { it.copy(report = report) }
    }
    fun back() { if (work?.isActive != true) mutable.update { it.copy(report = null, error = null) } }
    private fun runWork(block: suspend () -> Unit) {
        if (work?.isActive == true) return
        work = viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null) }
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(error = failure.message ?: "Analisis gagal.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
