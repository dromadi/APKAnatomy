# APKAnatomy 0.1.0-alpha.11

Open the project in Android Studio with JDK 17 and Android SDK platform 37 (compile SDK 37, target SDK 35). The build uses AGP 9.4 with its built-in Kotlin support (Kotlin 2.4), and the included wrapper pins Gradle 9.8.2 with a distribution checksum.

Build/check: `./gradlew :core:axml:test :domain:test :data:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
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
