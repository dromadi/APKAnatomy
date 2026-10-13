# APKAnatomy 0.1.0-alpha.3

Open the project in Android Studio with JDK 17 and Android SDK 35. The included wrapper pins Gradle 8.9 with a distribution checksum.

Build/check: `./gradlew :domain:test :data:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
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

Standalone manifest decoding delegates to Android PackageManager; no independent AXML decoder or intent-filter extraction yet. Some malformed/unsupported APKs may be rejected by the platform. Split archives (.apks/.xapk) are not supported. Imported files are not retained across process death. Installed-package visibility applies only to the current profile; Play distribution requires reviewing QUERY_ALL_PACKAGES eligibility.

Certificate fingerprints/signature scheme verification, app icons, size charts, and report exports remain follow-up milestones. An exported component does not by itself establish a vulnerability. Protection categories describe requested permissions, not runtime grants.

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
