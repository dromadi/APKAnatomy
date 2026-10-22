package com.apkanatomy.domain

/** A host the app's strings point to, with where it was seen. */
data class EndpointHost(
    val host: String, val service: String?, val cleartext: Boolean, val local: Boolean,
    /** Up to a few example URLs without query, fragment or credentials. */
    val examples: List<String>, val locations: List<String>, val count: Int,
    /** IDs of indexed methods that load a string pointing to this host (const-string). */
    val usedBy: List<String> = emptyList(),
) : java.io.Serializable

/** A string that looks like a key or token. Only a masked form is ever stored. */
data class SecretFinding(val type: String, val level: NoteLevel, val lesson: String, val masked: String, val locations: List<String>,
    /** IDs of indexed methods that load the value (const-string). */
    val usedBy: List<String> = emptyList()) : java.io.Serializable

data class TraceReport(
    val hosts: List<EndpointHost> = emptyList(),
    val secrets: List<SecretFinding> = emptyList(),
    val scannedStrings: Int = 0,
    val notes: List<String> = emptyList(),
) : java.io.Serializable

/** [hints]: cheap substrings, at least one of which every match contains; the regex only runs when one is present. */
private class SecretRule(val type: String, val level: NoteLevel, val lesson: String, val hints: List<String>, val pattern: Regex, val keep: Int = 6) {
    constructor(type: String, level: NoteLevel, lesson: String, hint: String, pattern: Regex, keep: Int = 6) : this(type, level, lesson, listOf(hint), pattern, keep)
}

private const val CLIENT = "Kunci sisi klien memang lazim disematkan di aplikasi, tetapi sebaiknya dibatasi (package name, SHA-1 sertifikat, atau domain) di konsol penyedianya."
private const val SERVER = "Kredensial sisi server seharusnya tidak ada di aplikasi: siapa pun yang membedah APK dapat memakainya. Bila asli, cabut dan ganti."

private val secretRules = listOf(
    SecretRule("Google API key", NoteLevel.INFO, CLIENT, "AIza", Regex("AIza[0-9A-Za-z_-]{35}")),
    SecretRule("Google OAuth client ID", NoteLevel.INFO, "ID klien OAuth bukan rahasia; menunjukkan aplikasi memakai login Google.", "googleusercontent", Regex("[0-9]{6,}-[0-9a-z]{32}\\.apps\\.googleusercontent\\.com"), keep = 8),
    SecretRule("AWS access key ID", NoteLevel.PERHATIAN, SERVER, listOf("AKIA", "ASIA"), Regex("(?<![A-Z0-9])(AKIA|ASIA)[0-9A-Z]{16}(?![A-Z0-9])"), keep = 4),
    SecretRule("Stripe secret key", NoteLevel.PERHATIAN, SERVER, "_live_", Regex("(sk|rk)_live_[0-9A-Za-z]{20,}"), keep = 8),
    SecretRule("Stripe publishable key", NoteLevel.INFO, "Kunci publishable Stripe memang untuk klien.", "pk_live_", Regex("pk_live_[0-9A-Za-z]{20,}"), keep = 8),
    SecretRule("Slack token", NoteLevel.PERHATIAN, SERVER, listOf("xoxa-", "xoxb-", "xoxp-", "xoxr-", "xoxs-"), Regex("xox[abprs]-[0-9A-Za-z-]{10,}"), keep = 5),
    SecretRule("Webhook Slack/Discord", NoteLevel.PERHATIAN, "URL webhook memberi siapa pun izin mengirim pesan ke kanal tersebut.", "hooks", Regex("https://(hooks\\.slack\\.com/services|(discord|discordapp)\\.com/api/webhooks)/[A-Za-z0-9/_-]{10,}"), keep = 30),
    SecretRule("GitHub token", NoteLevel.PERHATIAN, SERVER, listOf("ghp_", "gho_", "ghu_", "ghs_", "ghr_"), Regex("gh[pousr]_[0-9A-Za-z]{36}"), keep = 4),
    SecretRule("SendGrid API key", NoteLevel.PERHATIAN, SERVER, "SG.", Regex("SG\\.[A-Za-z0-9_-]{22}\\.[A-Za-z0-9_-]{43}"), keep = 4),
    SecretRule("Mapbox secret token", NoteLevel.PERHATIAN, SERVER, "sk.eyJ", Regex("sk\\.eyJ[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{10,}"), keep = 6),
    SecretRule("Midtrans server key", NoteLevel.PERHATIAN, SERVER, "Mid-server-", Regex("(SB-)?Mid-server-[A-Za-z0-9_-]{16,}"), keep = 12),
    SecretRule("Midtrans client key", NoteLevel.INFO, "Client key Midtrans memang untuk aplikasi.", "Mid-client-", Regex("(SB-)?Mid-client-[A-Za-z0-9_-]{12,}"), keep = 12),
    SecretRule("Private key", NoteLevel.PERHATIAN, "Kunci privat di dalam APK dapat dipakai siapa pun yang membedahnya.", "PRIVATE KEY", Regex("-----BEGIN ([A-Z]+ )?PRIVATE KEY-----"), keep = Int.MAX_VALUE),
    SecretRule("JSON Web Token", NoteLevel.INFO, "Token JWT tertanam; bila masih berlaku, dapat dipakai ulang. Isinya bisa didekode (bukan terenkripsi).", "eyJ", Regex("eyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"), keep = 10),
)

private val URL = Regex("(?i)\\b(https?|wss?|ftp)://[^\\s\"'<>()\\[\\]{}\\\\,;`^|]+")
private val IGNORED_HOSTS = setOf("schemas.android.com", "www.w3.org", "w3.org", "ns.adobe.com", "xmlpull.org", "purl.org", "schemas.xmlsoap.org",
    "schemas.microsoft.com", "xml.org", "www.apache.org", "apache.org", "json-schema.org", "xmlns.jcp.org", "java.sun.com",
    // Documentation placeholders (RFC 2606) rather than real endpoints.
    "example.com", "www.example.com", "example.net", "www.example.net", "example.org", "www.example.org")
private val SERVICES = listOf(
    "googleapis.com" to "Google APIs", "firebaseio.com" to "Firebase Realtime Database", "firebasedatabase.app" to "Firebase Realtime Database",
    "firebaseapp.com" to "Firebase Hosting", "crashlytics.com" to "Crashlytics", "app-measurement.com" to "Google/Firebase Analytics",
    "google-analytics.com" to "Google Analytics", "doubleclick.net" to "Iklan Google", "googlesyndication.com" to "Iklan Google",
    "googleadservices.com" to "Iklan Google", "facebook.com" to "Meta", "applovin.com" to "AppLovin", "unity3d.com" to "Unity",
    "appsflyer.com" to "AppsFlyer", "adjust.com" to "Adjust", "onesignal.com" to "OneSignal", "sentry.io" to "Sentry",
    "amazonaws.com" to "AWS", "cloudfront.net" to "AWS CloudFront", "github.com" to "GitHub", "githubusercontent.com" to "GitHub",
    "play.google.com" to "Google Play", "midtrans.com" to "Midtrans", "xendit.co" to "Xendit", "gstatic.com" to "Google (statis)",
)

fun maskSecret(value: String, keep: Int): String = when {
    keep == Int.MAX_VALUE -> "$value (isi kunci disembunyikan)"
    value.length <= keep + 4 -> value.take(2) + "…"
    else -> value.take(keep) + "…" + value.takeLast(4)
}

/** Drops query, fragment and user:password so URLs never carry keys into the report. */
fun sanitizeUrl(url: String): String {
    val noQuery = url.substringBefore('?').substringBefore('#').trimEnd('.', ':', '/')
    val scheme = noQuery.substringBefore("://")
    val rest = noQuery.substringAfter("://")
    val authority = rest.substringBefore('/')
    val cleanAuthority = if ('@' in authority) "…@" + authority.substringAfterLast('@') else authority
    return "$scheme://$cleanAuthority" + rest.removePrefix(authority)
}

/**
 * Collects endpoints and secret candidates from strings. Bounded, so a huge APK cannot grow it
 * without limit. Matches are hints for study: a key may be inactive, restricted or a test value.
 */
class TraceCollector(private val maxHosts: Int = 1_000, private val maxSecrets: Int = 200, private val maxLocations: Int = 5) {
    private class HostAcc(val host: String) { var cleartext = false; val examples = linkedSetOf<String>(); val locations = linkedSetOf<String>(); var count = 0; val usedBy = linkedSetOf<String>() }
    private class SecretAcc(val rule: SecretRule, val masked: String) { val locations = linkedSetOf<String>(); val usedBy = linkedSetOf<String>() }
    private val hosts = LinkedHashMap<String, HostAcc>()
    private val secrets = LinkedHashMap<Pair<String, String>, SecretAcc>()
    private var scanned = 0
    private val notes = linkedSetOf<String>()
    // Strings that yielded a host or secret; reference() only looks at these, so the xref pass is a hash lookup per const-string.
    private val interesting = HashSet<String>()

    fun scan(text: String, location: String) {
        scanned++
        // Nothing we look for is shorter than "http://a.b"; type descriptors (Lcom/x/Y;) are a large share of DEX strings.
        if (text.length < 10 || text[0] == 'L' && text.endsWith(';') && ' ' !in text) return
        if ("://" in text) for (match in URL.findAll(text)) if (addUrl(match.value, location)) interesting += text
        for (rule in secretRules) {
            if (rule.hints.none { it in text }) continue
            for (match in rule.pattern.findAll(text)) {
                if (secrets.size >= maxSecrets) { notes += "Batas $maxSecrets kandidat rahasia tercapai."; return }
                interesting += text
                val acc = secrets.getOrPut(rule.type to match.value) { SecretAcc(rule, maskSecret(match.value, rule.keep)) }
                if (acc.locations.size < maxLocations) acc.locations += location
            }
        }
    }

    /**
     * Records that [methodId] loads [text] (a const-string). Only hosts and secrets already found
     * in the string pools are linked; nothing new is added here.
     */
    fun reference(text: String, methodId: String) {
        if (text !in interesting) return
        if ("://" in text) for (match in URL.findAll(text)) hostOf(sanitizeUrl(match.value))?.let { hosts[it] }?.let { if (it.usedBy.size < maxLocations) it.usedBy += methodId }
        for (rule in secretRules) {
            if (rule.hints.none { it in text }) continue
            for (match in rule.pattern.findAll(text)) secrets[rule.type to match.value]?.let { if (it.usedBy.size < maxLocations) it.usedBy += methodId }
        }
    }

    private fun hostOf(clean: String): String? {
        val host = clean.substringAfter("://").substringBefore('/').substringAfterLast('@').substringBefore(':').lowercase()
        return if (host.length < 3 || '.' !in host && host != "localhost" || host in IGNORED_HOSTS) null else host
    }

    private fun addUrl(url: String, location: String): Boolean {
        val clean = sanitizeUrl(url)
        val host = hostOf(clean) ?: return false
        if (host !in hosts && hosts.size >= maxHosts) { notes += "Batas $maxHosts host tercapai."; return false }
        val acc = hosts.getOrPut(host) { HostAcc(host) }
        acc.count++
        if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("ws://", ignoreCase = true)) acc.cleartext = true
        if (acc.examples.size < 3) acc.examples += clean
        if (acc.locations.size < maxLocations) acc.locations += location
        return true
    }

    /** True when [text] yielded a host or secret during [scan]. */
    fun isInteresting(text: String): Boolean = text in interesting

    /** Records a limitation of the scan (e.g. a size cap) to show with the results. */
    fun note(text: String) { notes += text }

    fun result(): TraceReport = TraceReport(
        hosts = hosts.values.map { h ->
            EndpointHost(h.host, SERVICES.firstOrNull { (suffix, _) -> h.host == suffix || h.host.endsWith(".$suffix") }?.second,
                h.cleartext, isLocal(h.host), h.examples.toList(), h.locations.toList(), h.count, h.usedBy.toList())
        }.sortedWith(compareBy({ it.service == null }, { it.host })),
        secrets = secrets.values.map { SecretFinding(it.rule.type, it.rule.level, it.rule.lesson, it.masked, it.locations.toList(), it.usedBy.toList()) }
            .sortedWith(compareBy({ it.level != NoteLevel.PERHATIAN }, { it.type })),
        scannedStrings = scanned,
        notes = notes.toList(),
    )

    private fun isLocal(host: String) = host == "localhost" || host == "10.0.2.2" || host.startsWith("127.") || host.startsWith("192.168.") ||
        host.startsWith("10.") || Regex("172\\.(1[6-9]|2[0-9]|3[01])\\..*").matches(host) || host.endsWith(".local")
}
