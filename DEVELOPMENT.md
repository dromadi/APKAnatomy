# MVP 0.1.0-alpha.1

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

Certificate fingerprints/signature scheme verification, app icons, framework/ABI detection, size charts, and report exports remain follow-up milestones. An exported component does not by itself establish a vulnerability. Protection categories describe requested permissions, not runtime grants.

## Device smoke test

1. Launch on API 26 and API 35/36; search name/package and inspect one system and one user app.
2. Check component counts and exported flags against the app's source manifest.
3. Import a valid standalone APK, a plain ZIP without manifest, and a corrupt APK; errors should keep the UI usable.
4. Toggle exported filter, search components, inspect permissions, return to list, refresh.
5. Cancel the document picker; the previous screen must remain usable.
