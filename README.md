# Mica Gallery

A local-first Android gallery for the Motorola Edge 70 Pro+ with a dark, translucent Mica-inspired interface.

## Included
- Chronological photo and video grid
- Album grouping
- Search by filename and album
- Persistent favourites
- Multi-select (long-press): share, favourite and delete (moves to bin) several items at once
- Requests the display's highest refresh rate (120 Hz)
- Full-screen media viewer with in-app video playback (Media3 ExoPlayer) and Android share sheet
- Android 13+ media permissions, including Android 14 selected-photo access
- No account, analytics, cloud upload, or network permission

## Build an APK

This workspace did not have the Android SDK or Gradle installed, so an APK could not be compiled here. The source project is ready to build:

1. Install Android Studio on a computer.
2. Open this folder (`MicaGallery`) in Android Studio.
3. Install Android SDK Platform 35 when prompted.
4. Let Gradle sync, then choose **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

You can also push this folder to a GitHub repository. The included workflow at `.github/workflows/build-apk.yml` builds a debug APK and uploads it as a workflow artifact.

## Install on your Motorola
Transfer `app-debug.apk` to your phone, open it, allow installation from that source if Android asks, then grant Mica Gallery access to photos and videos. On Android 14+, you can choose limited photo access.

## Package details
- Application ID: `com.sooraj.micagallery`
- Minimum Android: 11 (API 30)
- Target SDK: 35
- Version: 1.0
