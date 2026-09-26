# Releasing FixMeAwesome

Short checklist. FIRST_RUN.md explains the one-time keystore setup; PRIVACY.md and
`store/listing.md` are the texts a store listing needs.

## Blockers before the first public release

- **License.** The roadmap's "Open decisions" still has MIT vs GPL-3.0 unresolved. Pick one,
  make the LICENSE file and CLAUDE.md agree, and only then publish or push.
- **Keystore.** A release keystore must exist outside the repo and be wired in
  `local.properties` (FIRST_RUN.md). The same key signs every future update; losing it means
  a new package id.
- **Screenshots.** `store/screenshots/README.md` lists the six to capture on a device.
- **F-Droid vs Play Services** and the **package id** are the other open decisions; they do
  not block a Play Store release under `com.surveyme`.

## Every release

1. **Version.** In `app/build.gradle.kts` bump `versionCode` by one and set `versionName`
   (drop any `-dev`). `CHANGELOG.md` gets a `## Version X.Y.Z (date)` entry at the top.
2. **Tests.** `.\gradlew.bat testDebugUnitTest` must be green.
3. **Build.** `.\gradlew.bat assembleDebug assembleRelease`. Release is minified (R8); read the
   build output for new `Missing class` warnings and fix them in `app/proguard-rules.pro`
   rather than with a blanket `-ignorewarnings`.
4. **Sign.** With the four `RELEASE_*` keys in `local.properties` (or the environment) the same
   command produces `app/build/outputs/apk/release/app-release.apk`, signed. Without them it
   produces `app-release-unsigned.apk`; that is fine for CI, not for a store. For Play use
   `bundleRelease` and upload the `.aab`.
   Check the signature: `apksigner verify --print-certs app-release.apk`
   (apksigner is in `<sdk>/build-tools/<version>/`).
5. **Archive the mapping.** Copy `app/build/outputs/mapping/release/mapping.txt` next to the
   APK (or upload it with the bundle). Without it, crash stack traces from that build are
   obfuscated and useless.
6. **Smoke test the release build on a device.** Install the signed APK, log in, fetch, get a
   ping, open a note, post a comment, add a photo. Minification bugs only show up here.
7. **Tag and push.** `git tag vX.Y.Z` and `git push --tags`. Attach the APK and `mapping.txt`
   to the GitHub release; paste the changelog entry as the release notes.
8. **Store.** Update the listing text from `store/listing.md` if it changed, and the
   screenshots if the UI did. Play Console asks for the privacy policy URL:
   https://github.com/skfd/FixMeAwesome/blob/main/PRIVACY.md
9. **Open the next dev version.** Bump `versionCode` and set `versionName` to the next patch
   with a `-dev` suffix as the last commit of the release.

## Environment

PowerShell, with the Android Studio JBR:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat testDebugUnitTest assembleRelease
```
