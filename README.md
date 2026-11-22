# CueRelay

CueRelay is a local media remote for Windows and Android. Control either device's active media sessions, choose a player, and put the Windows controls on the Android home screen.

## Install

Download the Windows x64 or ARM64 installer and the Android APK from [GitHub Releases](https://github.com/Morkimn/CueRelay/releases/latest). The Windows installer includes .NET. Android requires Android 8.0 or later. An update from the previous Темп release retains the Android package ID, Windows installer ID, and existing pairing data.
Windows Setup lets you choose a folder on a fresh install and reuses that folder when updating.

Open CueRelay on both devices on the same home network. On Android, scan the QR code displayed on Windows. Compare the six digits on both screens, then approve on each device. To control Android from the PC, enable «Разрешить управление с ПК» in the Android app and grant Android notification access so CueRelay can discover active media sessions. Windows Setup permits inbound TCP 43198 for the installed CueRelay executable on the local subnet on Public and Private profiles.

If scanning cannot reach the PC, check that CueRelay was installed with Windows Setup and is running. Connect the phone to the main Wi-Fi network. Guest Wi-Fi and client isolation can prevent devices on the same router from reaching each other. When reporting an issue, export the diagnostic log separately from each device.

Each QR contains a short-lived invitation, local addresses, and a public certificate fingerprint. It does not contain a paired phone's credential. Use the QR directly when possible. A copied invitation is for manual entry on the intended phone. CueRelay keeps it out of Windows clipboard history and clears its clipboard entry when it expires or the pairing screen closes.

## Updates and diagnostics

Both apps check GitHub Releases when open and announce a newer version in the interface. Use **Настройки → Обновления** to check manually or install. Clients verify the signed release manifest and the downloaded file's SHA-256 digest. Installation proceeds through Windows Setup or the Android package installer.

Use **Настройки → Диагностика** to view or save a JSON Lines log. Each line contains a UTC timestamp, platform, version, architecture, component and an event code. The app writes to the Windows temporary directory or the Android app cache. Logs are size-limited and older files are discarded at startup. They omit pairing codes, secrets, IP addresses, device names, and track metadata. Export is initiated by the user.

## How it works

- **Windows playback.** The .NET service reads playback sessions from Windows Global System Media Transport Controls. It uses Core Audio for the default output's volume. The WPF window and tray icon share this service. Closing the window keeps the local connection working. Enable **Settings → Save memory in the system tray** to release the window when you close it and recreate it when you reopen CueRelay.
- **Android playback.** The Android app uses notification access to discover the phone's media sessions. It can forward transport commands, media volume and the rating actions advertised by a player. You can choose which Android apps to share with the PC. The volume buttons can optionally adjust PC volume while the CueRelay screen is in the foreground.
- **Connection.** Android discovers Windows on the local network and connects using HTTPS with a pinned certificate. QR pairing grants a device-specific credential after confirmation. The phone receives Windows state through a long poll and reports its own state over the same authenticated connection. Media stays in the original player.
- **Background operation.** Android uses a foreground service for widgets and remote control when the app is closed, with the system's required status notification. The optional media controls can be turned off in **Appearance → Player and widgets**. The language and appearance-sync preferences apply per device.

## TVs

On Android, open **Settings → TV → Android TV remote** for a shortcut to Google's remote. Google TV pairs directly with the television using the code displayed on its screen, so the PC can be off. [Google TV remote setup](https://support.google.com/googletv/answer/11136134)

The common design for both controllers is to treat the TV as a third, explicitly paired playback device. For Google Cast, an Android sender and a sender in Chrome can control the same compatible receiver when the session is available to them. Cast Connect can launch a compatible installed Android TV receiver on demand. This is limited to the media and commands the receiver supports. For media already opened in another TV app, use a manufacturer-supported control protocol where available. Which integration is feasible depends on the TV model and OS. [Google Cast overview](https://developers.google.com/cast/docs/overview) · [Android TV receivers](https://developers.google.com/cast/docs/android_tv_receiver/)

CueRelay still controls Windows and Android directly. You can cast through a compatible player and control that player from CueRelay if it exposes the cast playback as a normal media session. Native control inside CueRelay for media already playing in arbitrary Android TV or Google TV apps would require a TV companion with one-time pairing and access to the TV's media sessions. The phone and PC could each connect directly on the local network. Background operation depends on notification access and the firmware's startup and background policies.

## Local development and releases

On Windows, install the .NET 10 SDK and Inno Setup 6.7. Use PowerShell from the project directory.

```powershell
New-Item -ItemType Directory -Force artifacts | Out-Null
.\Build-Windows.ps1 -OutputDirectory .\artifacts
.\artifacts\CueRelay-Dev-x64\CueRelay.exe
.\Build-Windows.ps1 -Mode Prod -InnoCompiler 'C:\Program Files (x86)\Inno Setup 6\ISCC.exe' -OutputDirectory .\artifacts -Architecture x64
.\Build-Windows.ps1 -Mode Prod -InnoCompiler 'C:\Program Files (x86)\Inno Setup 6\ISCC.exe' -OutputDirectory .\artifacts -Architecture arm64
```

Android needs a JDK, Android SDK Platform 35, Build Tools 35 and [ZXing Core 3.5.3](https://repo.maven.apache.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar).

```powershell
.\Build-Android.ps1 -PlatformJar <android.jar> -BuildTools <build-tools> -ZxingJar <core.jar> -Output .\artifacts\CueRelay-Dev.apk
.\Build-Android.ps1 -Mode Prod -PlatformJar <android.jar> -BuildTools <build-tools> -ZxingJar <core.jar> -Output .\artifacts\CueRelay.apk -Keystore <keystore.p12>
```

Android updates require the same package-signing key as the installed app. The official release manifest is signed with a separate key stored outside the repository. A maintainer can run `Sign-Release.ps1` with that Windows DPAPI-protected key, then attach both manifest files and the three installers to a GitHub Release tagged `v<version>`. Keep the APK signing key and the release signing key out of Git.

Dev is the default in both build scripts. Use a local development branch for iterations and publish from main when preparing a release. CueRelay Dev uses a separate Windows profile, an Android application ID with its own local signing key, and TCP port 43199. Pair Dev with Dev. The Dev apps run alongside Prod and skip GitHub update checks. A physical phone test requires Windows to allow the Dev executable on the trusted private network.
