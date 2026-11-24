# Quest companion-app project — next-session plan

Prepared 2026-11-21 (Sat) evening, after Biblia closed the day's Quest 2 expedition.
Goal set by Biblia at the close of that session: next time, build Muse's own
lightweight Quest companion app (periodic-screenshot capture via MediaProjection),
deployed over the verified FTP bridge — so screen sharing works with only Muse
running in the background, instead of Muse + File Manager + + Tailscale + game.

## Toolchain — INSTALLED and verified 2026-11-21

- JDK: Temurin 17.0.21.1 at `~/opt/jdk17` (`JAVA_HOME=/home/hatch/opt/jdk17`)
- Android SDK: `~/android-sdk` (`ANDROID_SDK_ROOT` / `ANDROID_HOME`)
  - cmdline-tools (latest), platform-tools 37.0.3 (`adb` verified: `adb version` → 1.0.41)
  - platforms;android-34 (android.jar, API 34)
  - build-tools 34.0.0 (`aapt2` 2.19 verified)
  - licenses accepted (android-sdk-license, android-sdk-preview-license)
- Gradle 8.5 at `~/opt/gradle-8.5` (binary verified with JAVA_HOME set)

## Proof build — DONE 2026-11-21

A minimal app `com.muse.questcompanion` ("Muse Quest Companion", minSdk 29,
target/compileSdk 34) was built and signed:
`~/workspace/quest-plugin/hello/manual/app-debug.apk` (8,538 bytes, apksigner
verify: 1 signer, OK; `aapt2 dump badging` confirms package/label).
Gradle project skeleton is at `~/workspace/quest-plugin/hello/` (AGP 8.3.2).

## Known limits (do not re-discover)

- `sdkmanager` cannot fetch remote packages from this VM: its JVM proxy
  handling fails against the egress proxy (NoSuchElementException in
  HttpURLConnection tunneling). Workaround used: download SDK zips directly
  with `curl` (which handles the proxy fine) and unzip into the SDK layout.
  Note some zips nest one level deeper than expected (build-tools binaries
  were under `android-14/`, platform files under `android-34-ext12/`) —
  flatten after unzipping.
- Full Gradle/AGP build is NOT yet proven: the Gradle daemon would not start
  (VM memory was ~160 MB free / ~800 MB available at the time). The manual
  aapt2 + javac + d8 + zipalign + apksigner path works and is light. Next
  session: either retry Gradle when the VM is less loaded, or keep building
  with the manual path and add AGP later.
- `/tmp` is a 512 MB tmpfs — download large files to `~/opt` or the workspace,
  never `/tmp`.

## First steps next session (in order)

1. Ask Biblia for the Quest 2 Horizon OS / firmware version (MediaProjection
   capture was reportedly re-enabled in v68+; a Spatial Data permission may
   also be needed). This decides whether the capture-app design is viable.
2. Build the prototype: a small app that, after one user-consented
   MediaProjection prompt, captures a screenshot every ~10 s at low
   resolution and saves it to a folder on the Quest.
3. Deploy: FTP the APK into `/device/Download` over the verified bridge
   (Tailscale quest-2 100.108.36.18, File Manager + FTP port 4481 — Biblia
   shares the FTP user/pass in chat when needed; do NOT store them), and
   Biblia installs it from File Manager +.
4. Test in stages: capture works while a game is in the foreground? Does the
   FTP server survive while an immersive game runs (untested 2026-11-21)?
   Battery drain over ~15 min with everything on (Biblia's load concern).
5. Only then design the delivery leg (app → Muse) to replace Tailscale + FTP.

## Still-open threads from 2026-11-21 (do not lose)

- Whether Muse's "Run in background" changes anything on Quest.
- Whether the FTP server survives while an immersive game is in the foreground.
- Battery-drain measurement with the full stack on.
- FTP bridge scripts: `~/workspace/ftp_bridge/` (ftp_test.py, ftp_explore.py,
  ftp_get.py, ftp_put.py; credentials via FTP_USER/FTP_PASS env only).
- Screen sharing today = screenshot-file sharing, not live video; the game is
  only needed at the moment a new screenshot is taken.
