package com.apkanatomy.data

import android.content.Context
import android.content.pm.*
import android.net.Uri
import com.apkanatomy.domain.*
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

@Suppress("DEPRECATION")
class AnatomyRepository(private val context: Context) {
    private val pm = context.packageManager
    private val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
        PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or
        PackageManager.GET_PERMISSIONS or PackageManager.MATCH_DISABLED_COMPONENTS

    suspend fun installed(): List<AppEntry> = withContext(Dispatchers.IO) {
        pm.getInstalledApplications(0).map { info ->
            AppEntry(info.packageName, pm.getApplicationLabel(info).toString(),
                info.flags and ApplicationInfo.FLAG_SYSTEM != 0)
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    suspend fun analyzeInstalled(packageName: String): AnatomyReport = withContext(Dispatchers.IO) {
        val info = pm.getPackageInfo(packageName, flags)
        val app = info.applicationInfo ?: error("Informasi aplikasi tidak tersedia.")
        val paths = listOfNotNull(app.sourceDir) + app.splitSourceDirs.orEmpty().toList()
        val context = coroutineContext
        val files = paths.map(::File)
        report(info, "Aplikasi terinstal", StructureScanner().scan(files), DexCodeScanner().scan(files) { context.ensureActive() })
    }

    suspend fun analyzeFile(uri: Uri): AnatomyReport = withContext(Dispatchers.IO) {
        val file = File.createTempFile("inspection-", ".apk", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("File tidak dapat dibuka.")
            input.use { source -> file.outputStream().use { copyBounded(source, it, 512L * 1024 * 1024) } }
            ZipFile(file).use { zip ->
                val manifest = zip.getEntry("AndroidManifest.xml")
                    ?: error("File bukan APK: manifest tidak ditemukan.")
                require(manifest.size in 1..(8L * 1024 * 1024)) { "Ukuran manifest tidak valid." }
            }
            val info = pm.getPackageArchiveInfo(file.absolutePath, flags)
                ?: error("APK tidak didukung atau manifest rusak. Pilih APK tunggal, bukan XAPK/APKS.")
            info.applicationInfo?.apply {
                sourceDir = file.absolutePath
                publicSourceDir = file.absolutePath
            }
            val context = coroutineContext
            report(info, "File APK lokal", StructureScanner().scan(listOf(file)), DexCodeScanner().scan(listOf(file)) { context.ensureActive() })
        } finally { file.delete() }
    }

    private fun report(info: PackageInfo, source: String, structure: PackageStructure, code: CodeIndex): AnatomyReport {
        val app = info.applicationInfo ?: error("Informasi aplikasi tidak tersedia.")
        val components = buildList {
            info.activities.orEmpty().forEach { add(component(it, ComponentKind.ACTIVITY, it.permission, app.enabled)) }
            info.services.orEmpty().forEach { add(component(it, ComponentKind.SERVICE, it.permission, app.enabled)) }
            info.receivers.orEmpty().forEach { add(component(it, ComponentKind.RECEIVER, it.permission, app.enabled)) }
            info.providers.orEmpty().forEach {
                add(component(it, ComponentKind.PROVIDER, it.readPermission, app.enabled)
                    .copy(authority = it.authority, writePermission = it.writePermission))
            }
        }.sortedWith(compareBy({ it.kind.ordinal }, { it.name }))
        return AnatomyReport(info.packageName, pm.getApplicationLabel(app).toString(),
            info.versionName ?: "—", app.minSdkVersion, app.targetSdkVersion, source,
            components, info.requestedPermissions.orEmpty().map { name ->
                val protection = try {
                    when (pm.getPermissionInfo(name, 0).protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) {
                        PermissionInfo.PROTECTION_NORMAL -> "Normal"
                        PermissionInfo.PROTECTION_DANGEROUS -> "Dangerous"
                        PermissionInfo.PROTECTION_SIGNATURE -> "Signature"
                        else -> "Lainnya"
                    }
                } catch (_: PackageManager.NameNotFoundException) { "Tidak dikenal di perangkat ini" }
                AppPermission(name, protection)
            }, structure, code)
    }

    private fun component(info: ComponentInfo, kind: ComponentKind, permission: String?, appEnabled: Boolean) =
        AppComponent(info.name, kind, when (info) {
            is ActivityInfo -> info.exported
            is ServiceInfo -> info.exported
            is ProviderInfo -> info.exported
            else -> false
        }, info.enabled && appEnabled, permission)
}
