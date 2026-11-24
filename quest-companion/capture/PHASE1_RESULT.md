# Phase 1 result — capture prototype VERIFIED (2026-11-24)

Built "Noa Capture" (package com.muse.questcompanion) with the manual
aapt2 + javac + d8 + zipalign + apksigner path (no Gradle).

- MainActivity: Start/Stop UI; Start requests MediaProjection consent.
- CaptureService: foreground service (mediaProjection type). Creates a
  VirtualDisplay mirrored to an ImageReader and saves a JPEG every 5 s via
  MediaStore into Pictures/NoaCapture. Capture width capped at 1024 px;
  files are ~60-70 KB each.
- Auto-prune (added same day at Biblia's request): only the newest 20 shots
  are kept; older ones are deleted automatically after each save.

Verification (2026-11-24 ~10:45 JST):
- APK (16,811 bytes) deployed over the Tailscale + File Manager + FTP bridge
  into /device/Download; Biblia installed it, approved the consent prompt,
  and started capture while inside VRChat (PocoPoco Farm).
- Shots appeared every ~5 s (37 files in the test run). Noa fetched
  cap_20261124_104539.jpg over FTP and read it clearly: the farm, Biblia's
  Lv 71 / 10,208,860 display, and the Muse chat panel itself.
- Capture is a single-eye portrait view but fully legible. No Spatial Data
  permission was needed on Horizon OS 2.10 (build v210).
- The 37 test shots were deleted afterwards over FTP (DELE); the folder is
  empty again.

Next: Phase 2 (delivery without the FM+ + Tailscale stack) and Phase 4
(load / battery measurement).
