# Building Ping and getting it onto your phone

There are two ways to get an APK: let GitHub build it for you (nothing to install), or build it
on your own computer.

---

## 1. Let GitHub build it (recommended)

1. Enable the pipeline once — copy [`ci/android-workflow.yml.template`](ci/android-workflow.yml.template)
   to `.github/workflows/android.yml` (GitHub → **Actions → New workflow → set up a workflow
   yourself**, paste the file, commit). It cannot be pushed by an automated tool that lacks the
   `workflow` permission, which is why it ships as a template.
2. Push anything, or run the workflow by hand from the **Actions** tab.
3. When it finishes, either
   * download the **ping-apks** artifact (contains `Ping-2.0.0.apk`), or
   * open **Releases → Ping 2.0.0 (latest build)** on the phone itself and tap the APK.

The release APK built without a keystore is signed with the *debug* key so that it stays
installable. That is fine for personal use; if you ever want to publish to a store, add a real
keystore (below).

### Installing on the phone

1. Move `Ping-2.0.0.apk` to the phone (download it directly, or send it to yourself).
2. Open it. Android asks you to allow installing unknown apps for the app you opened it from —
   allow it once.
3. Play Protect may warn that the app was not scanned. Choose **Install anyway**; the build is
   signed but not distributed through Play.
4. First launch: enter a display name, optionally the relay URL from
   [`DEPLOY.md`](DEPLOY.md), and tap through onboarding. The QR code under **Add contact** is
   what the other person scans.

Ping needs Android 8.0 (API 26) or newer.

---

## 2. Build it yourself

Requirements: JDK 17, Android SDK (platform 34 + build-tools 34), Gradle 8.7 (or use the
included wrapper `./gradlew`).

```bash
git clone https://github.com/Joercat/Ping-Chat.git
cd Ping-Chat

# crypto + protocol self-tests (fast, no device needed)
./gradlew testDebugUnitTest

# APKs land in app/build/outputs/apk/
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk

# install straight onto a USB-connected phone
adb install -r app/build/outputs/apk/release/app-release.apk
```

If the SDK is somewhere unusual, point Gradle at it with a `local.properties` file
(`sdk.dir=/path/to/Android/sdk`) or the `ANDROID_HOME` variable.

### Signing a release build properly

Create `keystore.properties` in the repository root (it is git-ignored — never commit it) and a
keystore once:

```bash
keytool -genkeypair -v -keystore ping-release.jks -alias ping -keyalg RSA -keysize 4096 -validity 10000
```

```properties
# keystore.properties
storeFile=/absolute/path/to/ping-release.jks
storePassword=…
keyAlias=ping
keyPassword=…
```

`./gradlew assembleRelease` now signs with that key. The same KEYSTORE_* values can be supplied
as environment variables in CI (`KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`) — the file wins if both exist.

> Signature changes: Android refuses to update an installed app with one signed by a different
> key. If you install a debug-signed build and later switch to a real keystore (or vice versa),
> uninstall first — which also deletes the message database, because Ping never backs it up.

---

## 3. Troubleshooting the build

| Symptom | Cause / fix |
| --- | --- |
| `Unsupported class file major version` | wrong JDK — use JDK 17 |
| `SDK location not found` | set `local.properties` or `ANDROID_HOME` |
| `Execution failed for task ':app:checkDebugAarMetadata'` | run `./gradlew --refresh-dependencies` |
| App installs but exits on start | read `adb logcat -s Ping:V AndroidRuntime:E` — storage failures print the SQLCipher reason |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | an older build signed with another key is installed: uninstall it first |
