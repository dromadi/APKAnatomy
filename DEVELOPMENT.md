# APKAnatomy 0.1.0-alpha.43

Open the project in Android Studio with JDK 17 and Android SDK platform 37 (compile SDK 37, target SDK 35). The build uses AGP 9.4 with its built-in Kotlin support (Kotlin 2.4), and the included wrapper pins Gradle 9.8.2 with a distribution checksum.

Build/check: `./gradlew :core:axml:test :domain:test :data:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
CI uploads the debug APK and verification reports on pushes and pull requests.

## Implemented

- Android 8+ / target Android 15, Compose Material 3, app/data/domain modules.
- Installed packages including disabled components, name/package search, system labels.
- Component names/type/exported/enabled and component access permissions; providers expose authorities and read/write permissions.
- Requested permissions with platform protection categories. Unknown custom permissions stay unknown.
- APK import through Storage Access Framework, 512 MiB copy limit, manifest size check, temporary file cleanup on success/failure.
- Analysis on IO dispatcher; loading/error states, refresh, Android back navigation, component search/exported filter.
- No APK installation/execution, Internet or broad storage permission.

## Current limits

Component and permission lists come from Android PackageManager; the raw manifest view uses the independent AXML decoder (alpha.4), and since alpha.6 intent-filters are linked to their components. Some malformed/unsupported APKs may be rejected by the platform. Split bundles (.apks/.xapk/.apkm) are supported since alpha.11; encrypted APKM files are not. Imported files are kept only while their report is open (for previews) and are not retained across process death. Installed-package visibility applies only to the current profile; Play distribution requires reviewing QUERY_ALL_PACKAGES eligibility.

Signature digest verification (Android does it at install) remains out of scope. An exported component does not by itself establish a vulnerability. Protection categories describe requested permissions, not runtime grants.

## Device smoke test

1. Launch on API 26 and API 35/36; search name/package and inspect one system and one user app.
2. Check component counts and exported flags against the app's source manifest.
3. Import a valid standalone APK, a plain ZIP without manifest, and a corrupt APK; errors should keep the UI usable.
4. Toggle exported filter, search components, inspect permissions, return to list, refresh.
5. Cancel the document picker; the previous screen must remain usable.

## Alpha.2: learn how applications are built

The product direction is an educational APK explorer. New screens: overview, technology evidence, component lessons, and ZIP directory browser with per-file roles and compressed/uncompressed sizes. Installed packages inspect both base APK and accessible splits. Standalone input still accepts one APK only.

Technology rules identify Flutter, React Native markers, Hermes, Unity, Godot, Mono/Xamarin, Capacitor, and Kotlin metadata. They are filename/path heuristics, not verified provenance or a reconstruction of the whole application. No recognized marker means unknown, not pure native.

DEX metrics read at most 112 header bytes per classes*.dex file, support versions 035/037/038/039/040, check endian/layout and method/class table bounds, and distinguish method references from class definitions. Header checks are not full DEX validation; no code disassembly is included. DEX 041 and compact DEX are not supported.

ZIP enumeration is capped at 50,000 entries across archives; duplicate paths are skipped with a warning. Errors/limits produce explicit partial-result warnings. Entries are displayed without extraction, including unusual paths. The browser displays up to 250 file matches at a time; search narrows the selection. Runtime tracing, call graphs, independent AXML decoding, full file previews, and source reconstruction remain future work.

Additional tests cover DEX table bounds/truncation/version, evidence false positives, installed split scanning, entry caps, and corrupt-archive isolation.

Sources for implementation: https://source.android.com/docs/core/runtime/dex-format and https://docs.flutter.dev/deployment/android

Additional smoke tests: inspect an installed Flutter app with ABI splits, navigate archive folders/root/up, search a nested file, check per-file metadata, switch component types, and check an app with no recognized framework.

## Alpha.3: DEX class/method exploration and Find usage

Uses org.smali:dexlib2 2.5.2 (BSD-style license) for decoding DEX definitions and instructions. Tests assemble real DEX fixtures with smali 2.5.2. No JADX integration or Java decompilation is claimed. The Code tab searches class names and method signatures, shows a method instruction summary, and navigates direct invoke references in both directions.

Reads accessible base/split DEX files, with bounds of 16 MiB per DEX / 48 MiB cumulative, 20k classes, 30k defined methods, 500k scanned instructions, 30k displayed instruction lines (at most 120 per method), and 40k call sites. Limits and parser errors report partial coverage. The displayed disassembly is not complete compilable Smali: exception regions, payload details and annotations are not rendered. Searches do not include full code bodies or strings. Find usage records static MethodReference invoke instructions; it does not resolve reflection, invoke-custom call sites, runtime dispatch or dynamic loading. Multiple matching method definitions are treated as ambiguous.

APK import is still temporary and scans happen on IO before showing the report. Large APKs can take time; low-memory-device runtime verification remains pending. UI shows 250 search/call-site results at a time.

Sources: https://github.com/JesusFreke/smali and https://github.com/skylot/jadx/wiki/Use-jadx-as-a-library

Smoke test: open APKAnatomy itself, switch to Code, search AnatomyRepository, open a method, inspect instructions/calls/Find usage, follow one resolved call and return. Inspect a small app with a native or abstract method. Confirm that an oversized DEX produces a warning.

Within each DEX, matching package-namespace classes are scanned first so framework libraries are less likely to consume the entire indexing budget. Package namespace is a priority hint only; other classes remain eligible.

## Alpha.4: independent manifest decoding

New `:core:axml` module (pure Kotlin, no Android dependency) decodes Android binary XML: UTF-8 and UTF-16 string pools, namespaces, the attribute resource map (unnamed attributes fall back to their resource ID), and typed values (references, booleans, integers, floats, dimensions, fractions, colors). Chunk and string bounds are checked; truncated or tampered input fails with `AxmlException` instead of over-reading. Resource references are shown as IDs (`@0x7f…`) because resources.arsc is not resolved yet.

The Manifest tab shows the decoded base APK manifest with line numbers and a copy action. Manifests over 8 MiB (declared or inflated) are rejected with a note; a manifest that fails to decode does not fail the rest of the report. Split APK manifests are not shown.

Components that are enabled, exported and reachable without a permission (for providers: read or write unguarded) are counted on the overview and marked in the component list. This is a review list; a launcher activity is expected to be open.

Tests use real aapt2 output (`core/axml/src/test/resources`, regeneration steps in `AxmlDecoderTest`) covering both string encodings, typed values, and truncated/corrupt input, plus `ManifestReader` behaviour for missing, non-binary and non-ZIP inputs.

Smoke test: open the Manifest tab for APKAnatomy itself and for a system app, compare with `aapt2 dump xmltree`, copy the XML, and import a corrupt APK to confirm the other tabs still work.

## Alpha.5: DNA (signing and security flags)

The DNA tab reads which signature schemes an APK carries and who signed it. v1 comes from `META-INF/*.SF` and the PKCS#7 blocks (`.RSA/.DSA/.EC`, up to 1 MiB each); v2, v3 and v3.1 from the APK Signing Block before the ZIP central directory (block capped at 16 MiB, every length checked); v4 from a `.idsig` file next to an installed APK. A single imported APK cannot carry v4, so it is shown as not checkable rather than absent. Source stamps, AGP dependency metadata and Play frosting blocks are named; other block IDs are listed in hex. ZIP64 archives are reported as unsupported.

Certificates are de-duplicated by SHA-256 and list where they appear, including v3/v3.1 SDK ranges; a v3 proof-of-rotation attribute marks key rotation. Digests are not verified: the tab answers "which schemes and keys", not "is this APK intact".

Security notes (in `domain`, unit tested) explain: unsigned or v1-only APKs (v1-only with target SDK 30+ fails to install), the Android debug certificate, expired certificates (not enforced by Android), SHA-1/MD5 certificate algorithms, and the manifest flags debuggable, allowBackup, usesCleartextTraffic (package-manager value, which applies the target-SDK default), testOnly and the presence of a Network Security Config. They are observations to study, not vulnerability findings.

Fixtures in `data/src/test/resources/signing` are real apksigner output (v1 only, v1+v2+v3 with v4 .idsig, v3+v3.1 key rotation, unsigned); `make-fixtures.sh` regenerates them. Expected fingerprints come from `apksigner verify --print-certs`. The scanner was also cross-checked against apksigner on APKAnatomy's own debug build and the F-Droid client APK.

Smoke test: open DNA for APKAnatomy itself (debug certificate, v2), for a Play-installed app (v2/v3, possibly source stamp and Play metadata), and for an imported APK (v4 not checkable).

## Alpha.6: intent-filters, component roles and sensitive permissions

Intent-filters from the decoded manifest are attached to components by fully qualified name (".Main" and "Main" are resolved against the package like the package manager does; activity-alias included). Attributes are looked up by name and, when obfuscation strips names, by framework resource ID (IDs verified with aapt2). All <data> elements of one filter are merged, as Android matches them together (a scheme in one element and a host in another), into schemes, hosts (with port), paths (path, prefix, suffix, pattern, advanced pattern) and MIME types.

Lessons explain well-known filters: launcher entry, verified App Links, web and custom-scheme deep links, file openers, share targets, boot/package/SMS broadcasts and FCM. Components bound through system permissions (accessibility, notification listener, device admin, input method, VPN, autofill, job, tile, NFC, wallpaper) get a role description. Requested permissions with special reach (overlay, all-files access, install packages, usage stats, SMS, background location, ...) are marked and listed first. These are explanations of what a declaration enables, not evidence that the app misuses it.

`intent_manifest.axml` (aapt2 output of `intent_manifest.xml`) covers relative names, aliases, App Links, split <data> elements, MIME filters and bound services. The parser was cross-checked on the F-Droid client manifest (36 components, 23 filters, two verified App Links).

Smoke test: open Komponen for a browser or social app and check deep links; open an app with an accessibility service; confirm ⚠ permissions appear first in Izin.

## Alpha.7: third-party SDKs and library versions

While a DEX is loaded for the code index, the package of every class definition is recorded, including DEX files read after the class/method index is full (the 16/48 MiB byte budgets still apply). This keeps SDK detection independent of the indexing limits: on the F-Droid client, 859 packages from 24,528 classes were seen while 5,606 classes were indexed.

About 60 rules in `domain/Sdk.kt` match whole package segments (`okhttp3` matches `okhttp3.internal`, not `okhttp3x`) across networking, data, media, UI, architecture, Firebase, Google services, analytics, ads, login, payments (including Midtrans and Xendit) and device integrity. Overlaps are separated (Hilt is not counted as plain Dagger, Play Integrity not as Play Core). A match means the library is bundled, not that it runs; R8 can rename packages, so a missing library is not proof of absence.

`META-INF/<group>_<artifact>.version` files (up to 2,000 per APK, 128 bytes each, inflated size capped) give exact versions for many Google and AndroidX libraries; they are listed in full and attached to matching SDKs. A share of one- or two-letter class names (at least 50 classes) gives an obfuscation hint.

Smoke test: open Teknologi for an app with ads or Firebase and check categories, versions and evidence; open an obfuscated release app and check the hint.

## Alpha.8: skin, report export and icons

The Kulit tab breaks the uncompressed size of all readable archives into DEX, native libraries, res/, resources.arsc, assets, metadata and other files, largest first. Each category is one labelled bar with its share, size, stored (ZIP) size and file count printed next to it; the bars use a single theme color because the label, not the color, identifies the category. Notable files are grouped: fonts, on-device ML models, web bundles in assets, bundled databases, certificates/keystores outside META-INF (often certificate pinning), media and JSON configuration.

"Ekspor" saves a Markdown or JSON report through the Storage Access Framework, or shares the Markdown summary (capped at 100,000 characters for the share intent; saved files are not capped). Both cover identity, signing and security notes, components with intent-filters, permissions, technologies, SDKs, library versions, size breakdown and partial-result warnings; code listings and the raw manifest are left out. The JSON writer escapes all control characters; a report generated from the F-Droid client parsed with Python's strict json module.

App icons appear in the installed-app list (loaded off the main thread, LRU cached) and in the report header. For reports the icon is rendered to a 144 px PNG during analysis, because an imported APK is deleted when analysis ends.

Smoke test: export Markdown and JSON for one installed app and one imported APK and open both files; share the summary to a notes app; compare the Kulit bars with the Isi APK sizes for a Flutter or game app.

## Alpha.9: resources.arsc and Network Security Config

`core/axml` now also decodes resources.arsc (`ResourceTable`): global and per-package string pools, type chunks with 32-bit, 16-bit (FLAG_OFFSET16) and sparse (FLAG_SPARSE) entry offsets, standard, complex (bag) and compact entries, and the common configuration qualifiers (language, region, night, density, SDK). Every offset is bounds-checked; a file shorter than its header declares is rejected. The table is capped at 32 MiB. String pool and Res_value formatting are shared with the binary XML decoder.

Decoded XML now shows names instead of IDs (`@string/app_name`, `@style/Theme.FDroid`); attributes keep the referenced ID. The Network Security Config file is located through the table, because R8's resource shrinker can rename it (the F-Droid client ships it as `res/8G.xml`), then parsed into base-config, nested domain-config, debug-overrides, trust anchors, pin sets and certificateTransparency. With a config present, android:usesCleartextTraffic is ignored and the base-config (default: target SDK < 28) decides cleartext; trusting user CAs, per-domain cleartext, pins (and expired pins), bundled CAs, disabled Certificate Transparency and debug-overrides get notes. Without a config, target SDK < 24 is noted as trusting user CAs. Kulit lists resource counts per type and the languages the table covers.

Fixtures (`core/axml/src/test/resources/arsc`, README there) are aapt2 output in dense and offset16/compact form, plus a sparse table produced by `make_sparse.py` because aapt2 36 would not emit one for this input; `aapt2 dump resources` prints identical output for the dense and sparse APKs. On the F-Droid client: 4,836 resources and 45 locales in 132 ms, and the decoded `res/8G.xml` matches `aapt2 dump xmltree`.

Smoke test: open Manifest for a Play Store app and check resolved names; open DNA for an app with pinning or a custom CA; check Resource & bahasa in Kulit.

## Alpha.10: file previews

Tapping a file in Isi APK now also shows its content, read on demand from the archive (at most 8 MiB per read, nothing extracted to disk or executed):

- Binary XML (layouts, drawables, xml/) is decoded with resource names from base.apk's table, which is parsed once per report.
- PNG, JPEG, GIF, WebP and BMP are decoded with a power-of-two sample size so the longer side stays near 1024 px.
- Strict UTF-8 without NUL or many control characters is shown as text, up to 256 KiB read and 32,000 characters laid out; a code point split by the limit is dropped rather than rejecting the file.
- Anything else gets a 512-byte hex/ASCII dump, with the format named when its magic number is known (Java class, DEX, ELF, ZIP, resources.arsc, gzip, SQLite, TensorFlow Lite).

To make previews possible for imported APKs, the copied file is now kept while its report is open and deleted on back, on the next analysis, when the ViewModel is cleared, or at the next app start (for a process that died with a report open). Installed apps are previewed from their base and split APKs directly.

Tested with unit tests on the resource demo APK and synthetic inputs (truncated binary XML, multi-byte text at the limit, image magic numbers), and on the F-Droid client: a shrunk resource file (`res/2R.xml`) decodes to a readable selector, a PNG asset previews, and `DebugProbesKt.bin` is recognised as a Java class file.

Smoke test: open a layout under res/, a PNG/WebP drawable, a JSON asset and classes.dex in Isi APK; import an APK, preview a file, press back, and confirm the cache copy is gone.

## Alpha.11: split bundles

"Buka APK / bundle" now accepts .apks (bundletool, SAI), .xapk (APKPure) and .apkm (APKMirror) as well as single APKs. A picked ZIP with an AndroidManifest.xml is a single APK; otherwise its .apk entries are unpacked into a per-import cache folder (at most 64 APKs, 512 MiB each and 1 GiB in total). Output files use only a sanitised base name of each entry, so an entry such as `../../x.apk` cannot write outside the folder. bundletool standalone variants and OBB files are skipped with a note.

The base APK is the one whose manifest has no `split` attribute, whatever it is named; a bundle with no base or with several is rejected, and splits declaring another package are skipped. The report then covers base and splits like an installed split app: structure, DEX code and SDKs across all of them, the base manifest and resources. Every split's signer is compared with the base's (certificates sharing no SHA-256): mismatching or unsigned splits get a DNA note, because Android refuses to install such a set; the same check now runs for installed split apps.

The whole import folder stays while the report is open (previews work for splits) and is deleted on back, the next analysis, ViewModel clearing or next app start.

Fixtures (`data/src/test/resources/bundle`) are bundles assembled from aapt2-built, apksigner-signed APKs: a bundletool-layout .apks with toc.pb and a standalone variant, and an APKPure-layout .xapk with an OBB and one split signed by a different key. A zip-slip attempt stays inside the output folder. Real store bundles were not available in this environment; a device smoke test with exported .apks/.xapk/.apkm files is still needed.

Smoke test: export an installed split app with SAI as .apks and import it; import an .xapk and an .apkm; check Isi APK lists every split and that previews open files from splits.

## Alpha.12: comparing two reports

"Aksi → Jadikan pembanding" keeps the open report as a baseline; any report opened afterwards gets a Banding tab listing what changed from the baseline. The baseline is slimmed first (`asBaseline`: no code listing or raw manifest, package list and class counts kept), so holding it costs little memory. "Ekspor" moved into the same Aksi menu.

`compareReports` in domain (unit tested) covers identity (package, name, version, min/target SDK); signing (a completely different signer is flagged, since Android refuses such an update unless the key was rotated with v3; certificates and schemes added or removed; audit notes that appear or disappear); requested permissions, flagging new sensitive ones; components added or removed, exported and permission changes and deep links, flagging components that become open to all apps; SDKs (new ad or analytics SDKs are flagged) and library version changes; and size per category (changes under 1% are ignored) and native ABIs. Changes are marked +, − and ~, and ⚑ for ones worth a closer look, so meaning never relies on color alone. The comparison can be copied as Markdown.

Smoke test: keep an installed app as the baseline, import an older APK of the same app, and read the Banding tab; compare two unrelated apps and check the package and signer changes are flagged.

## Alpha.13: UI tests and screenshots on the JVM

No emulator is needed to see the app run: `:app:testDebugUnitTest` uses Robolectric 4.17 (SDK 35, a 411×891 dp xxhdpi phone), Compose UI tests and Roborazzi 1.76 with native graphics.

- `RealAnalysisScreenTest` runs the real `AnatomyRepository` (package manager, every scanner, bundle unpacking) on the data module's fixture APKs, asserts on the result and renders it: resolved resource names, the Network Security Config card, a binary XML preview dialog, certificates of the apksigner fixture, an .xapk with a mismatched split, and the Banding tab between two real analyses.
- `RichReportScreenTest` renders every tab of a fully populated report, in light and dark themes.
- `AppFlowTest` starts the real MainActivity with its ViewModel and checks the installed-app list.

Screenshots are written to `app/build/outputs/roborazzi/` on every run (record mode, no golden comparison) and uploaded by CI with the other reports, so a change's look can be checked from the artifact. Reviewing them led to two fixes: the preview dialog was too short to read code (now up to 560 dp tall), and a missing versionName is shown as "versi tidak tercantum" instead of "v—".

Robolectric fetches `android-all-instrumented` from Maven Central at test time (cached in `~/.m2`); environments that cannot reach Maven Central need that jar pre-seeded. Robolectric is not a device: rendering, package-manager behaviour and permissions are emulated, so the device smoke tests below still matter, especially for icons (Robolectric supplies blank ones) and the file pickers.

## Alpha.14: Jejak (endpoints and secret candidates)

A new Jejak tab lists the hosts an app's strings point to and strings shaped like keys or tokens. Sources: every DEX string (fed while the DEX is loaded for the code index, including DEX files read after the index is full), every string value in resources.arsc, manifest meta-data values, and files under assets/ and res/raw/ read as Latin-1 so binary bundles still yield ASCII strings (media, fonts and archives skipped; 8 MiB per file, 32 MiB total).

URLs are grouped by host and stored without query, fragment or credentials, so a key in a query string never reaches the report. Known services (Firebase, Google APIs, ad and analytics networks, AWS, payment gateways) are named, local and development addresses and http:// or ws:// use are marked, and XML namespaces and RFC 2606 placeholder domains are ignored. About fifteen patterns recognise client keys that are normally embedded (Google API keys, OAuth client IDs, Stripe publishable and Midtrans client keys: INFO, with advice to restrict them) and server-side credentials that should never ship (AWS access keys, Stripe secret keys, Slack and GitHub tokens, SendGrid, Mapbox secret, Midtrans server keys, webhooks, private keys: PERHATIAN, also raised as an audit note). Only masked values are stored, shown or exported; matches are hints (a key can be inactive, restricted or a test value). Hosts and secrets are part of exports and the Banding diff.

Every regex runs only after a cheap substring check, and strings shorter than ten characters or shaped like type descriptors are skipped. On the F-Droid client 161,210 strings are scanned in about 0.6 s on a warm JVM (59 hosts, no secret candidates). An earlier one-letter prefilter made the AWS pattern run on nearly every string; the specific prefixes made the whole scan four times faster.

Test sources never contain a complete secret-shaped literal: fake keys are concatenated at runtime so secret scanners do not flag the repository.

Smoke test: open Jejak for a large app with analytics or payments and check hosts, services and masked values; confirm exports never contain an unmasked key.

## Alpha.15: native libraries and 16 KB page size

Every `lib/<abi>/<name>.so` in base and split APKs is read (64 MiB per library, 512 MiB in total) by a bounds-checked ELF reader for 32- and 64-bit little-endian files: machine, LOAD segment alignment, SONAME and DT_NEEDED dependencies (through the dynamic section and the LOAD mapping), exported `Java_*` symbols and `JNI_OnLoad` from .dynsym, and whether .symtab or .debug_* sections ship. JNI symbols are demangled (`_1`, `_2`, `_3`, `_0xxxx` escapes and the `__signature` suffix). The data offset of each entry comes from the ZIP central directory and local headers.

Readiness for 16 KB page-size devices is judged for 64-bit libraries only: LOAD segments must be aligned to at least 16 KB, and when the package manager reports extractNativeLibs=false (libraries loaded straight from the APK) the stored entry must start at a 16 KB multiple. The Teknologi tab shows the verdict naming each failing library and the reason, a JNI card (native methods seen in the code index versus statically exported functions; the rest are registered through RegisterNatives in JNI_OnLoad), and a card per library. The verdict is part of exports and the Banding diff.

Fixtures (`data/src/test/resources/elf`): native.c built with clang 18 and lld for android26 as arm64 with max-page-size 4096 and 16384, armv7, and an unstripped arm64 build, plus APKs aligned with `zipalign -P 16` and `zipalign -p`. Libraries are stored as `.so.bin` because AGP drops `*.so` from unit-test resources. Expected values match readelf and `zipalign -c -P 16`; the reader also matched readelf on a 32 MB real-world library (libLLVM_android.so from build-tools).

Smoke test: open Teknologi for a Flutter or game app and check the 16 KB verdict against `zipalign -c -P 16` and `readelf -l`.

## Alpha.16: capabilities beyond permissions

A Kemampuan tab reads what the manifest asks of the device and platform: uses-feature (required or optional, with lessons for common hardware) and the OpenGL ES version, `<queries>` package visibility (packages, intents, providers), uses-library, foreground services with their types, backup rules, and providers with grantUriPermissions. Attribute values and IDs were taken from aapt2 output; foregroundServiceType bits were verified one by one (fileManagement is not part of the public SDK and is not listed). Backup rule files (dataExtractionRules, fullBackupContent) are located through resources.arsc with the same reader as the Network Security Config, so files renamed by R8 are still found (the F-Droid client ships them as `res/Lz.xml` and `res/Qq.xml`).

DNA notes gain: an exported activity whose taskAffinity points to another package together with allowTaskReparenting (the StrandHogg task-hijacking pattern, PERHATIAN), other foreign task affinities, sharedUserId (deprecated, shares the Linux user ID) and requestLegacyExternalStorage. Capabilities are exported and part of the Banding diff (new location, camera, microphone or screen-capture foreground services are flagged).

Fixture `data/src/test/resources/extras/extras.apk` is aapt2 output of the manifest and resources next to it.

## Alpha.17: privacy profile

A Privasi tab, placed right after the overview, answers "what could this app learn about me?" in plain language, in the spirit of Exodus Privacy:

- Trackers: detected SDKs whose purpose is ads, marketing attribution, analytics, crash reporting or notification segmentation, with their evidence.
- Data access: requested permissions grouped into fifteen categories (location and background location, camera, microphone, contacts and accounts, calendar, SMS, phone and call log, media, all files, health and activity, nearby devices, installed apps, usage statistics, advertising ID), each noting whether the user sees a runtime prompt.
- Tracker endpoints: hosts of known ad and analytics services found by Jejak.
- A one-paragraph summary, also on the overview, in exports and as a Banding section (new data categories and trackers are flagged).

Everything comes from the APK: a bundled SDK may never be called and runtime permissions may be refused, so the tab says so and points to the Play Store data-safety section for comparison. `privacyProfile()` is a pure domain function with unit tests.

## Alpha.18: xref from strings to code

Every host and key candidate in Jejak now lists the methods that load it with `const-string` ("Dipakai di"). Tapping one opens the method in the Kode tab; methods outside the code index are listed without a link.

- Indexed methods record their string literals (`StringUse`, capped by `CodeLimits.strings`).
- Classes outside the index get a separate pass that only looks at `const-string` instructions, bounded by `CodeLimits.xrefInstructions` and skipped for DEX files whose string pool had no finding. `TraceCollector.reference` is a hash lookup against the strings that produced findings, so it never adds new ones.
- The Kode tab gains a String mode that searches string literals (minimum two characters) and shows the method that loads each one.

On the F-Droid client all 32 hosts link to at least one method; the extra pass costs about 0.5 s warm on that APK.

## Alpha.19: analysis history, staged progress and cancel

Every finished analysis is saved automatically to an on-device history (app-private storage; backups are disabled for the app). The home screen switches between "Aplikasi terinstal" and "Riwayat", where each entry can be opened again without re-analysing, used as the Banding baseline, or deleted.

- `HistoryStore` (data) writes one gzip file per report: a small header for the list, then the Java-serialized report. Reading only resolves `com.apkanatomy.domain`, `java.lang`, `java.util` and `kotlin` classes. At most 20 entries are kept, a newer analysis of the same package, version and source replaces the older one, and files above 8 MiB are not saved. Unreadable files (corrupt, or from an incompatible older build) are deleted.
- `forHistory()` (domain) drops the code listing, including the raw string literals that may hold unmasked secrets; the manifest and every other section stay. A report opened from history says so under its header and in the Kode tab, and its "Dipakai di" links are shown as outside the code index.
- Analyses report seven steps (`ANALYSIS_STEPS`), shown as "Langkah n/7" with a determinate bar and a Batal button. Cancelling stops at the next check: between steps, inside the DEX, asset and native scans, and about once per MiB while copying a picked file. An import folder of a cancelled analysis is deleted.

`HistoryScreenTest` round-trips the full sample report through the store, which fails if any class reachable from `AnatomyReport` is not serializable.

## Alpha.20: Perilaku (behaviours in code)

A Perilaku tab, right after Privasi, shows what the code calls rather than what the manifest allows. Every DEX instruction read (the index plus the extra pass from alpha.18, now always run) is checked against about 40 sensitive APIs in eight groups: secure connections (TLS), WebView, cryptography, private data, location/camera/microphone, device identity, dynamic code and app protection.

- Call rules match the invoked type and method name. Some also need a string loaded by the same method: `Settings.Secure.getString` only counts with `"android_id"`, `Cipher.getInstance` counts as ECB only for `"AES/ECB..."` or a bare `"AES"` without any `"AES/..."` transformation (so `RSA/ECB/PKCS1Padding` and an `"AES"` key spec next to `AES/GCM` do not match), and content-provider queries only for SMS, contacts or call log URIs.
- Whole-method patterns: an `X509TrustManager.checkServerTrusted` that only returns, and a `HostnameVerifier.verify` that only returns true. These and `SslErrorHandler.proceed()`, file-URL access in WebView, ECB/DES and sending SMS are PERHATIAN and also appear as DNA notes.
- Positive signals (Android Keystore, certificate pinning, Play Integrity) are marked BAIK.
- Each finding shows the APIs, how many call sites there are and how many sit in the app's own package, and up to five example methods (the app's own first). Indexed ones open in the Kode tab.
- Included in the overview, Markdown/JSON export and Banding (new PERHATIAN behaviours are flagged).

On the F-Droid client the scan reads 1.9 M instructions (about 2 s warm for the whole DEX step). It finds, among others, `getInstalledPackages` in its own nearby-swap code, `TelephonyManager.getDeviceId` in ACRA, zxing opening the camera, and shell commands in `FileCompat`. A first version flagged `RSA/ECB/PKCS1Padding` in the bundled zipsigner as ECB, which led to the string rule above.

## Alpha.21: components open their code

Each card in the Komponen tab now has a "Buka kode …" button when the component's class is in the code index. It opens the class in the Kode tab, where its methods (`onCreate`, `onReceive`, `onStartCommand`, …) can be read and followed with Find usage. A component whose class is not indexed says why: the class is not in the DEX files read (another split or the framework), or the index is partial or came from the history. The code index already indexes the app's own package first, so declared components are normally covered.

## Alpha.22: Kamus (glossary)

The home screen has a third section, **Kamus**: 48 terms a learner meets in the report, from APK, DEX and split APKs to StrandHogg, certificate pinning, 16 KB page size and Advertising ID. Terms are grouped (package & build, components & manifest, code, security & signing, network, native, privacy) and each one names the report tab where it shows up. Search matches the term and its aliases first (e.g. "obfuscation" finds R8 / ProGuard), then explanations. `GLOSSARY` is plain domain data; a unit test checks that terms are unique and every tab they name exists.

## Alpha.23: call paths, and components first in the code index

The method view in Kode gains a **Jalur** tab: the shortest chains of direct calls from a manifest component (an entry point the system runs) down to the method, with each step tappable. `CallGraph` (domain) searches the indexed calls backwards, breadth first, up to 5,000 methods and three paths.

- A call through a supertype's declaration counts for its overrides (`Runnable.run` reaches `Task.run`), except through supertypes with more than 20 indexed subtypes. Following Kotlin's `Function1` let any lambda "call" any other and invented paths on F-Droid.
- Inherited methods of a component's indexed superclasses are entry points too (`AppCompatActivity.attachBaseContext` runs for every activity extending it), labelled "activity lewat MainActivity".
- When no component is reached, the partial chains up to the last caller are shown; these usually end at a framework callback, a listener or a coroutine lambda.

**Index priority fix.** Testing this on F-Droid showed that none of its 36 components were in the code index. The index preferred classes under the application ID (`org.fdroid.fdroid`) while the code lives in `org.fdroid.*`, and the order only applied per DEX file, so androidx in classes.dex filled the index first. The scanner now loads every DEX first and indexes across all of them in three ranks: component classes, the app's namespaces (the package name, plus its first two segments when components live there), then libraries. F-Droid now has 33/36 components indexed (the other three are activity-aliases, which have no class). Component → code (alpha.21) and Jalur work on real apps as a result. A call path found there: `LocalRepoService.runProcess → LocalRepoManager.generateIndex → IndexV1Creator… → IndexUtils.md5`.

## Alpha.24: authenticity against the installed app

When an APK file or bundle is analysed and the same package is installed, the report compares the two (DNA notes, overview count, exports):

- **Signer.** If any certificate matches the installed app's current signers, that is BAIK. If it matches a certificate in the installed app's key-rotation history, that is INFO (likely a genuine older build). Otherwise it is PERHATIAN: the file could be a mod, another distribution of the app (F-Droid vs Play), or fake, and Android refuses it as an update.
- **Version.** versionCode lower than, equal to, or higher than the installed one, explaining downgrades and updates.
- **Install source.** For both file and installed-app analyses, the installer package is named: Google Play, F-Droid, Galaxy Store, AppGallery, the package installer for sideloads, or "not recorded" for adb/system apps.

Signers come from `SigningInfo` (API 28+, `apkContentsSigners` for multi-signer apps, `signingCertificateHistory` otherwise) or `GET_SIGNATURES` below 28, hashed as SHA-256 like the DNA tab. `AnatomyReport` gains `versionCode`, `installer` and `installedCopy`; the comparison logic is pure domain code with unit tests. History entries from earlier builds cannot be read after this model change and are removed with a message, as designed in alpha.19.

## Alpha.25: resource browser

The "Resource & bahasa" card in Kulit opens a browser for `resources.arsc`: type chips (string, color, dimen, bool, …, ordered by count), a search over names, IDs and values, and one card per resource with its ID and its value in every configuration (default, `in`, `night`, `sw600dp`, …). The repository builds the listing on demand from the base APK's table, which it shares with the file previews, and caps it at 50,000 resources and 500 characters per value; the screen shows up to 300 matches. For a report opened from history the archive is gone, and the browser says so. On the demo fixture the values match `aapt2 dump resources` (`string/app_name`: "Anatomi Demo", `in`: "Demo Anatomi").

## Alpha.26: search the whole report

A **Cari** tab, right after Ringkasan, searches every section at once: trackers and data categories, behaviours, technologies, SDKs, library versions and native libraries, components and their intent filters, permissions, capabilities, classes, methods and string literals, manifest lines, DNA notes and certificates, hosts and key candidates, and file paths. Results are grouped by section (30 per section). Tapping a class or method opens it in Kode; anything else opens its tab. `searchAll()` is a domain function with unit tests; the sections are lazy sequences, so each keystroke only scans until it has enough matches.

## Alpha.27: packers and protections

Teknologi starts with a **Proteksi & pengemasan** section (also in exports, search and, for packers, as DNA notes), because a packed app hides its real code from every code-based tab:

- **Packers / protectors** recognised by native library or asset names and DEX packages: Qihoo 360 Jiagu, Bangcle/SecNeo, Tencent Legu, Ijiami, Baidu Protect, Alibaba Security Guard, DexProtector, AppSealing and LIAPP. Packages match whole segments, so `com.stubborn` is not Jiagu's `com.stub`.
- **Hidden code**: DEX/JAR/APK files in `assets/` or `res/raw/`, and a primary DEX with at most 40 classes next to native libraries, the typical shape of a packer stub.
- **Name obfuscation** (R8/ProGuard), using the existing short-name hint, which this section replaces in Teknologi.
- **Runtime checks** from the Perilaku tab: root and debugger detection, Play Integrity/SafetyNet and in-memory DEX loading.

Detection is by evidence only and lists the files or packages it used. Commercial protectors that leave no stable file or package name (Promon, Arxan/Digital.ai, DexGuard) are not claimed.

## Alpha.28: HTML export

"Aksi" → "Simpan HTML (buka di browser)" writes one self-contained page: a header with the app icon (embedded as a data URI), six summary cards (components, permissions, SDKs, behaviours, hosts and notes needing attention) and every section of the Markdown export rendered as headings, lists and tables, in light and dark mode.

Everything in an APK is untrusted, and a report is likely to be opened in a browser, so:
- all text is HTML-escaped before the small Markdown subset (`code`, **bold**, _italic_, lists, pipe tables with escaped pipes) is turned into tags;
- the page carries `Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; img-src data:`, so even a missed escape could not run a script or load anything remote.

Unit tests feed a `<script>` payload through the label and a component name. The rich sample report is written to `app/build/outputs/roborazzi/rich_report.html` on every test run, so CI uploads it with the screenshots.

## Alpha.29: pseudo-code

The method view in Kode opens on **Kode semu**, a Java-like reading of the instructions, with **Instruksi DEX** one tap away. `PseudoJava` (data) translates one statement per instruction:

- Registers keep their names (`v0`, parameters `p0…`, `this` for instance methods; a long/double parameter takes two registers). Branch targets become `L_<offset>:` labels with `if (…) goto` and `goto`.
- A call's result is folded into the `move-result` that follows it (`v0 = v1.toString();`), and `new-instance` is folded into its constructor (`v1 = new StringBuilder(v0);`). `filled-new-array` becomes `new String[] { v0, v1 }`.
- Fields, arrays, casts, `instanceof`, arithmetic (including `/2addr` and `/lit` forms), conversions, comparisons, `throw`, `synchronized`, and `switch` are covered. Anything else stays as a `// opcode` comment, and an unexpected instruction shape only costs its own line.

dexlib2 names opcodes as smali does (`const/4`, `add-int/2addr`), which the first version did not expect: on F-Droid nearly every line fell back to a comment. After that fix, 215 of 80,000 lines do. Pseudo-code is rendered at scan time for indexed methods, up to 80,000 lines in total (`CodeLimits.pseudoLines`). The app's own code is indexed first, so it is covered first; on F-Droid that is 7,111 methods, at about 1.5 s extra on a cold JVM. Example from F-Droid's `FileCompat.symlinkRuntime`: `p1 = Runtime.getRuntime(); p0 = p1.exec(p0);`.

## Alpha.30: audit every installed app

A fourth home section, **Audit**, looks at all user apps (system apps optional) in one pass and ranks them. For each app it reads from the package manager the flags (debuggable, backup, cleartext), targetSdk, requested permissions, open components and installer. It also gets tracker SDKs from the DEX class packages: `dexPackages()` reads only class definitions, about 0.3 s for F-Droid's 24k classes.

`AppAudit.reasons()` (domain) explains what stands out, each with points used only for ordering:
- debuggable: 3 points;
- targetSdk below 29: 2 points;
- one point per tracker, grouped by purpose;
- one point per personal-data category, up to 5 (same categories as Privasi);
- cleartext allowed, more than one open component, or an installer that is not a known store: 1 point each.

The screen says the points are not a security rating, shows per-filter counts (Pelacak, Data pribadi, Target lama, Debuggable, Bukan dari toko, Komponen terbuka) as chips, and opens the full analysis on tap. The audit shows "Memeriksa n/N: app" with a Batal button and runs under the same cancellable job as an analysis.

## Alpha.31: what changed since the last analysis

When an app or APK has been analysed before, the new report is compared with the last history entry of the same package automatically. This happens before the new report replaces that entry.

- The earlier report becomes the Banding baseline, and a message says when it was analysed and how many changes there are, or that nothing changed.
- The overview starts with a **Perubahan dari <versi>** card: the number of changes, how many need a closer look, and changes per section. Tapping it opens Banding.
- A baseline the user picked with "Jadikan pembanding" (from a report or the history) is never replaced. An automatic one is dropped when the next app has no history.

`AutoCompareTest` drives the real ViewModel under Robolectric: two analyses of the same fixture, then a user-chosen baseline that a third analysis keeps.

## Alpha.32: native imports and strings

Native libraries now show what they call and what text they carry:

- **Imports.** `ElfReader` collects undefined global/weak symbols from `.dynsym`, i.e. functions taken from libc, libdl and others. Ten groups worth explaining are marked: ptrace (anti-debug), exec*/fork/system/popen (running programs), dlopen/dlsym (dynamic loading), mprotect, system properties, native sockets, getifaddrs, a bundled OpenSSL/BoringSSL, inotify and raw syscalls. They appear per library in Teknologi, grouped under "Dari library native" in Perilaku, in exports and in search.
- **Strings.** Printable runs of 8+ characters in each library (like `strings`, at most 50,000 per library) go through the Jejak scanner, so hosts and key candidates inside `.so` files are found with their library as location.

New fixture `elf/imports.c` → `imports-arm64.so.bin` (clang + lld, `-nostdlib`, never run) and `imports.apk`. Its UND symbols match `readelf --dyn-syms`. On the real `libLLVM_android.so` from build-tools, the notable imports (dlopen, dlsym, execv, execve, fork, mprotect, posix_spawn) are exactly what readelf lists among its 247 imports. On F-Droid, androidx's path library reads system properties and contains an android.googlesource.com URL.

## Alpha.33: code map

Teknologi opens with a **Peta kode**: a treemap where each box is a group of packages and its area is the number of classes in it, over every DEX file (not just the index). The scanner now counts classes per package (`CodeIndex.packageClasses`); history keeps these counts.

- Grouping: reverse-domain names by organisation (`com.squareup`, `id.contoh`), big vendors by product (`com.google.firebase`, `com.google.android`), bare names by their first segment (`okhttp3`, `kotlin`).
- R8's one- and two-letter top packages (`a`, `b`, …) are gathered into "kode tersamar", because they usually mix app and library code. The 24 largest groups are shown; the rest are merged.
- Colours: app code (the package name's and the components' namespaces), AndroidX/Kotlin/Google platform libraries, third-party libraries (tracker SDKs always count as third party) and obfuscated code. Detected SDK names label their boxes.
- Below the map: the app's own share of classes and the five largest groups.

The layout is a squarified treemap (Bruls, Huizing & van Wijk) in domain code. A unit test checks that areas are proportional and match the paper's example; labels are drawn only where they fit.

## Alpha.34: permissions ↔ code

The Izin tab starts with **Izin ↔ kode**, which joins the manifest with the Perilaku scan. Seven permission groups have APIs the scanner recognises: location, camera, microphone, phone state, sending SMS, QUERY_ALL_PACKAGES and AD_ID.

- For each such permission the app requests, the tab shows "dipakai di kode" (with how many call sites are in the app's own package and example methods that open in Kode) or "pemakaian tidak terlihat".
- The reverse case is shown too: an API group called without its permission. That is usually library code that checks first; unchecked, the call would throw SecurityException. AD_ID and installed-app listing are left out, because Play services merges the former and `<queries>` covers the latter.
- The text explains why "not seen" is not proof of non-use: libraries, native code, WebView, intents to other apps and code loaded at runtime are all invisible to the scan.

The matching is domain code (`permissionUse()`, `undeclaredUse()`) with unit tests, and is part of the Markdown/HTML export.

## Alpha.35: how the UI is built

Teknologi and the overview now say how the screens are drawn (`uiStack()`, domain), with evidence for every toolkit found:

- **Engines that draw their own UI**: Flutter (libflutter/libapp, flutter_assets), React Native (bridge libraries, Hermes, the JS bundle, `com.facebook.react`), Unity (IL2CPP vs Mono), Unreal, Cocos, Godot, .NET MAUI/Xamarin (Mono runtime, assemblies), web apps in a WebView (Cordova, Capacitor, Ionic) and Compose Multiplatform.
- **Android toolkits**: Jetpack Compose (class count in `androidx.compose.ui`), Views with layout XML (number of `res/layout*` files), and NativeActivity.

The primary toolkit is the first engine found, because an engine draws the screens even when AndroidX is bundled. Without an engine it is Compose when there are at least 200 Compose UI classes and fewer than 40 layouts, otherwise Views. Library layouts and Compose classes from dependencies are why both are listed with their counts rather than decided silently.

## Alpha.36: guided tour

A **Belajar** tab, right after Ringkasan, walks a learner through the report in eight steps:
1. who signed the app;
2. its entry points;
3. what it could learn about the user;
4. what it is built from;
5. what its code does;
6. where it connects;
7. how tidy its security is;
8. how to read its code.

Each step shows an observation written from this report's own findings (signer, launcher activity, privacy summary, UI toolkit, share of own code, top behaviour, hosts, notes needing attention, the launcher class to open), a button to the tab where it shows, and a question whose answer appears only on request. Progress ("n selesai") is kept per report while the screen lives. The overview's first card now points to the tour. `tour()` is domain code; a test checks every step names a real tab and asks a question.

## Alpha.37: try components and deep links

Cards in the Komponen tab can now be tried out, the way another app could:

- **Coba buka** starts an exported, enabled activity by its class name.
- **Coba <uri>** opens each deep link of the component with `ACTION_VIEW` + `BROWSABLE`, limited to the app's package. The example URIs come from `IntentFilterInfo.exampleUris()` (domain): each scheme with each host (a `*.` wildcard dropped) and the first literal path (`/produk*` → `/produk`; regex paths are skipped).
- The outcome is explained under the buttons: opened, refused by the system (with the protecting permission when known; "this is the protection working"), or not found (the app is not installed, e.g. for a report of an APK file).

Starting goes through `LocalIntentLauncher`, so the UI test captures the exact intents (action, URI, package, class) instead of leaving the screen. Smoke test on a device: open an installed app with an App Link, tap its "Coba https://…" button, and the app (or the link chooser, if the domain is not verified) should open.

## Alpha.38: constant-aware security rules

The code scanner now records the literal constants each method loads (`const*` instructions, both passes), so a rule can depend on the value passed and not only on the API called. `BehaviorRule` gained `ints` (a predicate on those constants) and `alsoCalls` (another owner prefix that must be called in the same method). New rules:

- **PendingIntent yang dapat diubah** (INFO): `PendingIntent.getActivity/getBroadcast/getService/…` in a method that loads a pure flag word with `FLAG_MUTABLE` (0x02000000) set, `FLAG_IMMUTABLE` clear and nothing in the low 24 bits (so `-1` or a colour does not count).
- **Debugging WebView diaktifkan** (INFO): `WebView.setWebContentsDebuggingEnabled` with the constant `1`.
- **Random biasa dipakai di kode kriptografi** (PERHATIAN): `java.util.Random` created or used in a method that also calls `javax.crypto`.
- **Klien HTTP Apache lama** (INFO): `DefaultHttpClient` constructed.
- **Memakai penyimpanan bersama** (INFO): `Environment.getExternalStorageDirectory/getExternalStoragePublicDirectory`.

A planned rule for dynamically registered receivers without an export flag was dropped: `registerReceiver(…, flags)` overloads cannot be told apart from the flags alone without data-flow analysis. Checked on F-Droid: the mutable PendingIntents found are in `SessionInstallManager` (PackageInstaller needs a mutable sender) and AppCompat's `SearchView`, both correct.

## Alpha.39: changes on the phone since the last audit

A finished audit is now kept on the device (`AuditStore`, `filesDir/audit/last.audit.gz`). It is written field by field with `DataOutputStream`, with no Java serialization, so reading never creates objects from the file. Changing a model class therefore does not invalidate it. A truncated or foreign file is deleted. The Audit tab shows the last result straight away, with its date, without scanning again.

When an audit finishes, it is compared with the one before (`auditChanges`, domain):

- **Baru dipasang**: version, install source, data access and trackers.
- **Dihapus**.
- **Diperbarui / Berubah**: version, targetSdk, permissions added or dropped, new data categories, trackers added or gone, now debuggable, a different install source.

New data access, new trackers, debuggable and a changed install source are marked "perlu dilihat" and listed first. System apps are compared only when both audits included them, so ticking or unticking the checkbox does not make them appear installed or removed. Trackers are compared only when the code of both versions was readable. The card lists 8 changes with "Tampilkan semua". Tapping a change opens the full analysis, except for removed apps.

## Alpha.40: export the audit

The Audit tab has **Simpan CSV** and **Simpan HTML** next to "Audit ulang", saved through the system file picker as `apkanatomy-audit-<date>.csv/.html`.

- **CSV** (`toAuditCsv`, domain) has one row per app, highest points first. Columns: label, package, version, system, targetSdk, points, debuggable, allowBackup, cleartext, installer, open components, data categories, trackers with purpose, permissions, reasons and note. Every cell is quoted (RFC 4180, CRLF), and the file starts with a UTF-8 BOM so Excel reads it correctly. Labels come from the apps themselves, so a cell starting with `= + - @` gets a leading `'` and a spreadsheet does not run it as a formula.
- **HTML** (`toAuditHtml`) is self-contained and uses the report export's stylesheet (light and dark). It has the summary cards per filter, the changes since the previous audit when there is a diff, and a table of apps with their reasons. All app text is escaped, and the CSP forbids scripts and remote loads. Package names in a table's first column no longer break mid-word.

## Alpha.41: control-flow diagram of a method

A method in the Kode tab has a new **Alur** tab: its control-flow graph, read on demand from the DEX.

- `ControlFlowReader` (data) reads the method again from its `classesN.dex`. The code index keeps only 120 listed instructions per method, and the switch payloads and try blocks are needed here. It caches the last DEX it parsed, so opening several methods of one file costs about 3 ms each instead of 70 ms on F-Droid. The cache is cleared with the report.
- `controlFlow()` (domain) splits the instructions into basic blocks. A block starts at the first instruction, at every jump and case target, after every jump, switch, return and throw, and at try boundaries and handlers. Edges are typed: next, goto, true, false, case (keys sharing a target are merged), default, and catch (with the exception type). Limits: 300 blocks and 40 lines per block.
- The diagram stacks the blocks in code order. Jumps are lines in a left gutter, each in the innermost free lane, and loops are dashed. Each block lists its exits in words, such as "jika benar → B3" or "bila exception IOException → B8".
- The header gives the number of blocks, cyclomatic complexity (decisions + 1, catch edges excluded) and loops, with a short explanation. Two new glossary terms cover basic blocks/CFG and cyclomatic complexity.
- Reports opened from history have no APK, so the tab explains that the app must be analysed again.

Checked on F-Droid: 300 of the app's methods read without failure. The most complex is `LocalHTTPD.serveFile`: 55 blocks, complexity 23, 2 loops, with its `NumberFormatException` and `IOException` handlers on the right blocks. A smali test assembles a method with a loop, a packed switch whose two keys share a target, and a try/catch, and checks every edge.

## Alpha.42: component map

The Komponen tab opens with a **Peta komponen** card showing which activity, service or receiver is started from where in the code.

- While scanning (index and last pass), `ScreenCollector` records every method that loads a manifest component's class literal (`const-class`) and also builds an explicit `Intent` (`<init>`, `setClass`, `setComponent`) or `ComponentName`. That is the shape of `startActivity(Intent(this, X::class.java))`, `startService`, and notification `PendingIntent`s. A class literal without an Intent (a log tag, for example) does not count.
- `screenMap()` (domain) folds inner and lambda classes into their outer class (`Main$onCreate$1` → `Main`) and drops self-links such as `Sync$Companion` → `Sync`. It lays the graph out in rows by breadth-first distance from the roots: launcher activities first, then classes nothing else starts. Links where neither side is in the app's namespace (the first two parts of the package name) are hidden behind "Sertakan tautan antar-library".
- The diagram draws boxes coloured by kind (activity, service, receiver, other code), with the launcher outlined. Arrows go down, and dashed arrows go back up. The list below is complete. A link whose method is in the code index opens that method.
- `AnatomyReport` gained `screenLinks`, so history entries saved by earlier versions can no longer be read; they are removed with the usual message.

Checked on F-Droid, which is mostly Compose with few activities. Links found include `SwapService` → `SwapWorkflowActivity` (its notification), `NotificationManager` → `MainActivity`, `ExtraSettingsKt` → `PanicActivity` and `IpfsGatewaySettingsActivity` (Compose settings screens), and `AppInstallManager` → `AppInstallService`. WorkManager's internal starts are hidden as library links.

## Alpha.43: instruction guide and original file names

**Tap an instruction to learn it.** In the "Instruksi DEX" view of a method, each line can be tapped to show what its opcode does, in Indonesian. `explainInstruction()` (domain) knows about 45 opcode families: moves and results, constants (with what `/4`, `/16`, `/high16` mean), strings and class literals, objects and arrays, fields (instance and static), the five invoke kinds and `/range`, branches (including `if-eqz` as a null check), switches (packed and sparse), comparisons, conversions and arithmetic (`/2addr`, `/lit8`, division by zero), `synchronized`, throw and catch. The header also explains register names: v0… are locals, and p0… are parameters, with p0 being `this` in an instance method.

**Original file names.** `CodeClass` now keeps the DEX `SourceFile` attribute. R8 renames classes, but apps often keep this attribute so crash stack traces stay readable, so an obfuscated `a.b` can still say it came from `CartRepository.kt`.
- The class card shows its source file, or explains that R8 removed or replaced it (`SourceFile`, `r8-map-id-…`).
- An obfuscated class (one- or two-letter name) whose file name differs gets a hint in the search results.
- Searching classes also matches source file names.
- The search page counts how many obfuscated classes still carry their file name.

F-Droid keeps a real source file on 20,099 of its 24,528 classes. It is not obfuscated, so no hints appear there. `CodeClass` gained a field, so older history entries can no longer be read.
