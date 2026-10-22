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
    /** The import folder (APK or unpacked bundle) is kept while its report is open for previews; deleted on the next analysis or [release]. */
    @Volatile private var retainedImport: File? = null
    /** Resource table of the current base APK, parsed on the first preview. */
    @Volatile private var previewTable: Pair<File, com.apkanatomy.axml.ResourceTable?>? = null

    init {
        // A process killed while a report was open leaves its import behind.
        context.cacheDir.listFiles { f -> f.name.startsWith("inspection-") }?.forEach { it.deleteRecursively() }
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
        val traces = TraceCollector()
        report(info, "Aplikasi terinstal", StructureScanner().scan(files), DexCodeScanner().scan(files, info.packageName, traces) { context.ensureActive() },
            files, scanSigning(File(app.sourceDir), files.drop(1), File(app.sourceDir + ".idsig")), traces)
    }

    /** Analyzes a single APK or a split bundle (.apks, .xapk, .apkm) picked through the Storage Access Framework. */
    suspend fun analyzeFile(uri: Uri): AnatomyReport = withContext(Dispatchers.IO) {
        release()
        val work = File(context.cacheDir, "inspection-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        var kept = false
        try {
            val imported = File(work, "import.apk")
            val input = context.contentResolver.openInputStream(uri)
                ?: error("File tidak dapat dibuka.")
            input.use { source -> imported.outputStream().use { copyBounded(source, it, 512L * 1024 * 1024) } }
            val isApk = try { ZipFile(imported).use { it.getEntry("AndroidManifest.xml") != null } }
                catch (_: java.util.zip.ZipException) { error("File bukan APK atau bundle APK (bukan arsip ZIP).") }
            val (base, splits, bundle) = if (isApk) {
                ZipFile(imported).use { zip ->
                    require(zip.getEntry("AndroidManifest.xml").size in 1..(8L * 1024 * 1024)) { "Ukuran manifest tidak valid." }
                }
                Triple(imported, emptyList<File>(), null)
            } else {
                val extracted = try { BundleExtractor().extract(imported, File(work, "apks")) }
                    catch (e: BundleExtractor.BundleException) { error(e.message ?: "Bundle tidak valid.") }
                imported.delete()
                Triple(extracted.base, extracted.splits, extracted)
            }
            val info = pm.getPackageArchiveInfo(base.absolutePath, flags)
                ?: error("APK tidak didukung atau manifest rusak.")
            info.applicationInfo?.apply {
                sourceDir = base.absolutePath
                publicSourceDir = base.absolutePath
                splitSourceDirs = splits.map { it.absolutePath }.toTypedArray().takeIf { it.isNotEmpty() }
            }
            val files = listOf(base) + splits
            val context = coroutineContext
            val structure = StructureScanner().scan(files).let { it.copy(warnings = bundle?.skipped.orEmpty() + it.warnings) }
            val source = bundle?.let { "${it.format} (${files.size} APK)" } ?: "File APK lokal"
            val traces = TraceCollector()
            report(info, source, structure, DexCodeScanner().scan(files, info.packageName, traces) { context.ensureActive() },
                files, scanSigning(base, splits, v4Signature = null), traces).also {
                retainedImport = work
                archives = files.mapIndexed { i, f -> (if (i == 0) "base.apk" else f.name) to f }.toMap()
                kept = true
            }
        } finally { if (!kept) work.deleteRecursively() }
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
        retainedImport?.deleteRecursively()
        retainedImport = null
    }

    /** [files] is the base APK followed by its splits. */
    private suspend fun report(info: PackageInfo, source: String, structure: PackageStructure, code: CodeIndex,
        files: List<File>, signing: SigningReport, traces: TraceCollector): AnatomyReport {
        val baseApk = files.first()
        val (table, tableNote) = ResourceReader.read(baseApk)
        val manifest = ManifestReader.read(baseApk, table)
        val context = coroutineContext
        TraceSources.scanResources(table, traces)
        TraceSources.scanManifest(manifest.application, traces)
        val labeled = files.mapIndexed { i, f -> (if (i == 0) "base.apk" else f.name) to f }.toMap()
        TraceSources.scanFiles(labeled, traces) { context.ensureActive() }
        val native = NativeScanner().scan(labeled, info.applicationInfo?.let { it.flags and ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS != 0 }) { context.ensureActive() }
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
            iconPng = iconPng(app), networkSecurity = networkSecurity, traces = traces.result(), native = native,
            capabilities = manifest.root?.let { CapabilitiesReader.read(it, baseApk, table) } ?: Capabilities(),
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
