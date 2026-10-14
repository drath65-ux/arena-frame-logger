# Arena Frame Logger

Small Android app that captures a frame of the screen every 2 seconds while you play MTG Arena,
keeps only frames that changed, and zips them (720p JPEGs) into `Downloads/ArenaLogger/` when you stop.
A 15-minute game is roughly 15–25 MB instead of a 200+ MB screen recording.

## Install
1. Open the **Releases** page of this repo on your phone and download `arena-frame-logger.apk`.
2. Open it and allow "install unknown apps" for your browser when Android asks.

## Use
1. Open **Arena Logger**, tap **Start**, allow screen capture (pick MTG Arena if offered a single app).
2. Play.
3. Tap **Stop** in the notification.
4. Tap the "Saved…" notification (or **Share last session**) and send the zip to Claude.

## Build
Every push to `main` builds the APK with GitHub Actions and publishes it to the `latest` release.
