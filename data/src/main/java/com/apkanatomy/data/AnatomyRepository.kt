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
    /** Archives of the current report by the labels StructureScanner uses, for on-demand previews. */
    @Volatile private var archives: Map<String, File> = emptyMap()
    /** The imported APK is kept while its report is open so files can be previewed; deleted on the next analysis or [release]. */
    @Volatile private var retainedImport: File? = null
    /** Resource table of the current base APK, parsed on the first preview. */
    @Volatile private var previewTable: Pair<File, com.apkanatomy.axml.ResourceTable?>? = null

    init {
        // A process killed while a report was open leaves its import behind.
        context.cacheDir.listFiles { f -> f.name.startsWith("inspection-") && f.name.endsWith(".apk") }?.forEach { it.delete() }
    }
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
        release()
        archives = files.mapIndexed { i, f -> (if (i == 0) "base.apk" else f.name) to f }.toMap()
        report(info, "Aplikasi terinstal", StructureScanner().scan(files), DexCodeScanner().scan(files, info.packageName) { context.ensureActive() },
            File(app.sourceDir), SigningScanner().scan(File(app.sourceDir), File(app.sourceDir + ".idsig")))
    }

    suspend fun analyzeFile(uri: Uri): AnatomyReport = withContext(Dispatchers.IO) {
        release()
        val file = File.createTempFile("inspection-", ".apk", context.cacheDir)
        var kept = false
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
            report(info, "File APK lokal", StructureScanner().scan(listOf(file)), DexCodeScanner().scan(listOf(file), info.packageName) { context.ensureActive() },
                file, SigningScanner().scan(file, v4Signature = null)).also {
                retainedImport = file; archives = mapOf("base.apk" to file); kept = true
            }
        } finally { if (!kept) file.delete() }
    }

    /** Reads one file of the current report's archives for display. */
    suspend fun preview(archive: String, path: String): FilePreview = withContext(Dispatchers.IO) {
        val file = archives[archive] ?: return@withContext FilePreview.Unavailable("Arsip tidak lagi tersedia; buka ulang laporannya.")
        // Binary XML in splits still points into the base table, so names come from base.apk.
        val base = archives["base.apk"]
        val table = previewTable?.takeIf { it.first == base }?.second
            ?: base?.let { ResourceReader.read(it).first.also { t -> previewTable = base to t } }
        PreviewReader.preview(file, path, table)
    }

    /** Forgets the current report's archives and deletes a retained import. */
    fun release() {
        archives = emptyMap()
        previewTable = null
        retainedImport?.delete()
        retainedImport = null
    }

    private fun report(info: PackageInfo, source: String, structure: PackageStructure, code: CodeIndex,
        baseApk: File, signing: SigningReport): AnatomyReport {
        val (table, tableNote) = ResourceReader.read(baseApk)
        val manifest = ManifestReader.read(baseApk, table)
        val (networkSecurity, nscNote) = ResourceReader.networkSecurityConfig(baseApk, manifest.application, table)
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
            .let { list -> manifest.root?.let(::intentFiltersByComponent)?.let { byName -> list.map { it.copy(intentFilters = byName[it.name].orEmpty()) } } ?: list }
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
            }, structure, code, manifestXml = manifest.xml, manifestNote = manifest.note,
            signing = signing, security = securityFlags(app, manifest), sdks = detectSdks(code.packages, structure.libraryVersions),
            iconPng = iconPng(app), networkSecurity = networkSecurity,
            resources = (table?.let { ResourceSummary(it.resourceCount, it.typeCounts().entries.sortedByDescending { e -> e.value }.associate { e -> e.key to e.value }, it.locales().toList(), nscNote) }
                ?: tableNote?.let { ResourceSummary(0, emptyMap(), emptyList(), listOfNotNull(it, nscNote).joinToString(" ")) }))
    }

    private fun iconPng(app: ApplicationInfo): ByteArray? = try {
        val size = 144
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        app.loadIcon(pm).apply { setBounds(0, 0, size, size) }.draw(android.graphics.Canvas(bitmap))
        java.io.ByteArrayOutputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
    } catch (_: Exception) { null }

    private fun securityFlags(app: ApplicationInfo, manifest: DecodedManifest) = SecurityFlags(
        debuggable = app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        allowBackup = app.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0,
        // The package manager already applies the target-SDK default (true below API 28).
        cleartextTraffic = app.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0,
        testOnly = app.flags and ApplicationInfo.FLAG_TEST_ONLY != 0,
        targetSdk = app.targetSdkVersion,
        networkSecurityConfig = manifest.application?.attribute("networkSecurityConfig"),
    )

    private fun component(info: ComponentInfo, kind: ComponentKind, permission: String?, appEnabled: Boolean) =
        AppComponent(info.name, kind, when (info) {
            is ActivityInfo -> info.exported
            is ServiceInfo -> info.exported
            is ProviderInfo -> info.exported
            else -> false
        }, info.enabled && appEnabled, permission)
}
