# AGENTS.md

## Cursor Cloud specific instructions

QMME is a single Android app (package `rj.qmme`) — there is no local backend, database, or web frontend. It embeds Tencent's proprietary Watch QQ runtime and talks to Tencent's live servers over the internet. "Running the app" means building/installing the APK; standard build/run commands live in `README.md` and `.github/workflows/android.yml`.

### Environment (already provisioned in the VM snapshot)
- JDK 17 at `/usr/lib/jvm/java-17-openjdk-amd64` and JDK 21 (system default) are both installed. `JAVA_HOME`/`ANDROID_HOME` are exported in `~/.bashrc`.
- Android SDK at `~/android-sdk` (`platforms;android-37.0`, `build-tools;37.0.0`, `platform-tools`), with licenses accepted. `local.properties` (gitignored) points `sdk.dir` there.
- The build works whether launched with JDK 17 or the default JDK 21: `gradle/gradle-daemon-jvm.properties` pins the Gradle daemon toolchain to 21, so a non-login/non-interactive shell (which does not source `~/.bashrc`) still builds correctly. The SDK is found via `local.properties`, so no env vars are strictly required to build.

### Build / test (see README for the full list)
- Build debug APK: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` (signed with the in-repo `app/testkey.jks`). This is the primary artifact and what CI produces.
- Unit tests: `./gradlew :app:testDebugUnitTest`.

### Gotchas
- Lint (`./gradlew :app:lintDebug`) currently fails with ~15 **pre-existing** `NewApi` errors (e.g. `app/src/main/java/rj/qmme/fix/HiddenApiAccess.java`) that are guarded at runtime. Lint is NOT part of CI (CI only runs `assembleDebug`); do not treat these lint errors as an environment problem or "fix" them without being asked.
- The app cannot be run end-to-end in this VM: there is no `/dev/kvm` (no emulator hardware acceleration) and the APK only packages `armeabi-v7a`, so an emulator is impractical. Functional login/messaging also requires a real QQ account and reaches Tencent's servers. Verify changes via build + APK inspection instead (`build-tools/37.0.0/aapt2 dump badging`, `apksigner verify`, `cmdline-tools/latest/bin/apkanalyzer dex packages`).
- `gradle.properties` disables the Gradle daemon and enables the configuration cache; the wrapper downloads Gradle from a Tencent mirror pinned in `gradle/wrapper/gradle-wrapper.properties`.
