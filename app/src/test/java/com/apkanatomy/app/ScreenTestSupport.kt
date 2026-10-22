package com.apkanatomy.app

import android.net.Uri
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.apkanatomy.domain.*
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File

internal fun fixtureUri(name: String): Uri {
    val bytes = object {}.javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()
    val file = File.createTempFile("fixture", "-" + name.substringAfterLast('/')).apply { writeBytes(bytes); deleteOnExit() }
    return Uri.fromFile(file)
}

internal fun ComposeContentTestRule.openTab(name: String) {
    val tab = onNode(hasText(name) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
    tab.performScrollTo()
    tab.performClick()
    waitForIdle()
}

/** Scrolls the (first) lazy list until a node containing [text] is composed and visible. */
internal fun ComposeContentTestRule.scrollToText(text: String) {
    onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text, substring = true))
    waitForIdle()
}

internal fun ComposeContentTestRule.shot(name: String) = onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

internal const val CHECKOUT = "base.apk:classes.dex:Lid/contoh/toko/net/Api;->checkout()V"

/** A report with every section populated, for screenshots of busy screens. */
internal fun richReport(): AnatomyReport {
    val filters = listOf(IntentFilterInfo(listOf("android.intent.action.MAIN"), listOf("android.intent.category.LAUNCHER")),
        IntentFilterInfo(listOf("android.intent.action.VIEW"), listOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"),
            schemes = listOf("https"), hosts = listOf("shop.example.com"), paths = listOf("/produk*"), autoVerify = true))
    val entries = listOf(
        ArchiveEntry("base.apk", "classes.dex", 9_400_000, 3_900_000), ArchiveEntry("base.apk", "classes2.dex", 4_100_000, 1_700_000),
        ArchiveEntry("base.apk", "lib/arm64-v8a/libflutter.so", 11_200_000, 5_100_000), ArchiveEntry("base.apk", "resources.arsc", 1_300_000, 1_300_000),
        ArchiveEntry("base.apk", "res/font/inter.ttf", 310_000, 290_000), ArchiveEntry("base.apk", "assets/flutter_assets/model.tflite", 2_400_000, 2_100_000),
        ArchiveEntry("base.apk", "assets/certs/pin.pem", 1_200, 900), ArchiveEntry("base.apk", "META-INF/CERT.SF", 40_000, 12_000))
    val cert = SignerCertificate("CN=Toko Contoh,O=PT Contoh,C=ID", "CN=Toko Contoh,O=PT Contoh,C=ID", "5a1f", 1_600_000_000_000, 2_600_000_000_000,
        "SHA256withRSA", "RSA", 2048, "3f".repeat(32), "a1".repeat(20), "b2".repeat(16), listOf("v1", "v2", "v3 (API 24+)"))
    return AnatomyReport("id.contoh.toko", "Toko Contoh", "4.2.0", 24, 35, "Aplikasi terinstal",
        components = listOf(
            AppComponent("id.contoh.toko.MainActivity", ComponentKind.ACTIVITY, true, true, null, intentFilters = filters),
            AppComponent("id.contoh.toko.checkout.PaymentActivity", ComponentKind.ACTIVITY, false, true, null),
            AppComponent("id.contoh.toko.push.PushService", ComponentKind.SERVICE, false, true, null,
                intentFilters = listOf(IntentFilterInfo(listOf("com.google.firebase.MESSAGING_EVENT"), emptyList()))),
            AppComponent("id.contoh.toko.a11y.HelperService", ComponentKind.SERVICE, true, true, "android.permission.BIND_ACCESSIBILITY_SERVICE"),
            AppComponent("id.contoh.toko.BootReceiver", ComponentKind.RECEIVER, true, true, null,
                intentFilters = listOf(IntentFilterInfo(listOf("android.intent.action.BOOT_COMPLETED"), emptyList()))),
            AppComponent("id.contoh.toko.files", ComponentKind.PROVIDER, false, true, null, authority = "id.contoh.toko.fileprovider")),
        permissions = listOf(AppPermission("android.permission.INTERNET", "Normal"), AppPermission("android.permission.CAMERA", "Dangerous"),
            AppPermission("android.permission.SYSTEM_ALERT_WINDOW", "Signature"), AppPermission("android.permission.POST_NOTIFICATIONS", "Dangerous")),
        structure = PackageStructure(archives = listOf("base.apk"), entries = entries,
            technologies = listOf(Technology("Flutter", "Runtime Flutter ditemukan.", listOf("base.apk/lib/arm64-v8a/libflutter.so"))),
            libraryVersions = mapOf("com.google.firebase:firebase-messaging" to "24.1.0", "androidx.core:core" to "1.15.0")),
        code = CodeIndex(
            classes = listOf(CodeClass("Lid/contoh/toko/net/Api;", "Ljava/lang/Object;", emptyList(), "base.apk", "classes.dex"),
                CodeClass("Lid/contoh/toko/checkout/PaymentActivity;", "Landroid/app/Activity;", emptyList(), "base.apk", "classes.dex")),
            methods = listOf(CodeMethod(CHECKOUT.substringAfter("classes.dex:"), "Lid/contoh/toko/net/Api;", "checkout", "base.apk", "classes.dex",
                "public static", true, listOf("0x0  const-string v0, \"https://api.contoh.id/v2/checkout\"", "0x2  return-void"), 2, false,
                listOf("v0 = \"https://api.contoh.id/v2/checkout\";", "return;")),
                CodeMethod("Lid/contoh/toko/checkout/PaymentActivity;->onCreate(Landroid/os/Bundle;)V", "Lid/contoh/toko/checkout/PaymentActivity;", "onCreate",
                    "base.apk", "classes.dex", "protected", true, listOf("0x0  invoke-super {p0, p1} Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V", "0x3  invoke-static Lid/contoh/toko/net/Api;->checkout()V", "0x6  return-void"), 3, false)),
            strings = listOf(StringUse(CHECKOUT, "https://api.contoh.id/v2/checkout")),
            calls = listOf(MethodCall("base.apk:classes.dex:Lid/contoh/toko/checkout/PaymentActivity;->onCreate(Landroid/os/Bundle;)V", CHECKOUT.substringAfter("classes.dex:"), 3)),
            definedClasses = 18_000, shortNamedClasses = 9_500,
            packageClasses = mapOf("id.contoh.toko.checkout" to 900, "id.contoh.toko.net" to 600, "androidx.compose.ui" to 4_200, "androidx.core" to 1_500,
                "kotlin.collections" to 1_200, "okhttp3" to 700, "com.google.android.gms.ads" to 2_400, "com.google.firebase.messaging" to 800,
                "io.flutter.embedding" to 1_100, "a" to 4_600)),
        manifestXml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<manifest\n    package=\"id.contoh.toko\">\n    <application\n        android:label=\"@string/app_name\" />\n</manifest>",
        signing = SigningReport(setOf(SignatureScheme.V1, SignatureScheme.V2, SignatureScheme.V3), listOf(cert),
            otherBlocks = listOf("Metadata dependensi dari Android Gradle Plugin (terenkripsi, untuk Google Play).")),
        security = SecurityFlags(debuggable = false, allowBackup = true, cleartextTraffic = false, testOnly = false, targetSdk = 35, networkSecurityConfig = "@xml/network_security_config"),
        sdks = listOf(DetectedSdk("Firebase Cloud Messaging", "Firebase", "Menerima notifikasi push.", listOf("com.google.firebase.messaging"), 4, listOf("firebase-messaging 24.1.0")),
            DetectedSdk("OkHttp", "Jaringan", "Klien HTTP.", listOf("okhttp3", "okhttp3.internal"), 12),
            DetectedSdk("Google AdMob", "Iklan", "Menampilkan iklan Google.", listOf("com.google.android.gms.ads"), 30)),
        networkSecurity = NetworkSecurityConfig("res/8G.xml", base = NscScope(cleartextPermitted = false, trustAnchors = listOf("system")),
            domains = listOf(NscScope(listOf("*.api.contoh.id"), pins = 2, pinExpiration = "2030-01-01"))),
        resources = ResourceSummary(5120, mapOf("string" to 2100, "drawable" to 800, "layout" to 120), listOf("en", "in", "ms")),
        capabilities = Capabilities(features = listOf(FeatureUse("android.hardware.camera", true), FeatureUse("android.hardware.nfc", false)),
            queries = QueriesDecl(packages = listOf("com.whatsapp"), intents = listOf("VIEW https:")),
            foregroundServices = listOf(ForegroundServiceUse("id.contoh.toko.delivery.TrackingService", listOf("location"))),
            backupRuleFiles = listOf("res/xml/backup_rules.xml"), backupRules = listOf(BackupRule("cloud-backup", false, "sharedpref", "auth.xml"))),
        native = NativeReport(listOf(
            NativeLibInfo("base.apk", "lib/arm64-v8a/libflutter.so", "arm64-v8a", 11_200_000, "AArch64", 64, 4096, true, 4096, "libflutter.so",
                listOf("libc.so", "libdl.so", "liblog.so"), emptyList(), true, false, false),
            NativeLibInfo("base.apk", "lib/arm64-v8a/libtoko.so", "arm64-v8a", 210_000, "AArch64", 64, 16384, true, 16384, "libtoko.so",
                listOf("libc.so"), listOf("id.contoh.toko.crypto.Native.sign", "id.contoh.toko.crypto.Native.verify"), false, true, false)),
            extractNativeLibs = false),
        behaviors = BehaviorCollector("id.contoh.toko").apply {
            method(CHECKOUT, listOf("Landroid/webkit/WebView;" to "addJavascriptInterface", "Landroid/webkit/WebSettings;" to "setJavaScriptEnabled"), emptyList(), 12)
            pattern(BehaviorPatterns.TRUST_ALL, "base.apk:classes2.dex:Lcom/vendor/net/LaxTrust;->checkServerTrusted([Ljava/security/cert/X509Certificate;Ljava/lang/String;)V")
            method("base.apk:classes2.dex:Lcom/vendor/Loader;->load()V", listOf("Ldalvik/system/DexClassLoader;" to "<init>"), emptyList(), 40)
            method("base.apk:classes2.dex:Lcom/vendor/Keys;->make()V", listOf("Landroid/security/keystore/KeyGenParameterSpec\$Builder;" to "<init>"), emptyList(), 8)
        }.result(),
        traces = TraceCollector().apply {
            scan("https://api.contoh.id/v2/checkout", "base.apk/classes.dex")
            reference("https://api.contoh.id/v2/checkout", CHECKOUT)
            reference("https://api.contoh.id/v2/checkout", "base.apk:classes2.dex:Lokhttp3/internal/Probe;->run()V")
            scan("http://10.0.2.2:8080/debug", "base.apk/classes2.dex")
            scan("https://toko-contoh.firebaseio.com", "res: string/firebase_database_url")
            scan("AI" + "za" + "SyB_contohKunciPalsu_1234567890abcde", "res: string/google_api_key")
            scan("SB-Mid-" + "server-" + "ContohPalsu1234567890", "base.apk/assets/payment.json")
        }.result(),
    )
}
