# CueRelay

CueRelay is a local media remote for Windows and Android. Control either device's active media sessions, choose a player, and put the Windows controls on the Android home screen.

## Install

Download the Windows x64 or ARM64 installer and the Android APK from [GitHub Releases](https://github.com/Morkimn/CueRelay/releases/latest). The Windows installer includes .NET. Android requires Android 8.0 or later. An update from the previous Темп release retains the Android package ID, Windows installer ID, and existing pairing data.

Open CueRelay on both devices on the same home network. On Android, scan the QR code displayed on Windows. Compare the six digits on both screens, then approve on each device. To control Android from the PC, enable «Разрешить управление с ПК» in the Android app and grant Android notification access so CueRelay can discover active media sessions. The Windows firewall rule is limited to the private profile, the local subnet, and the app's TCP port.

Each QR contains a short-lived invitation, local addresses, and a public certificate fingerprint. It does not contain a paired phone's credential. Use the QR directly when possible. A copied invitation is for manual entry on the intended phone. CueRelay keeps it out of Windows clipboard history and clears its clipboard entry when it expires or the pairing screen closes.

## Updates and diagnostics

Both apps check GitHub Releases when open and announce a newer version in the interface. Use **Настройки → Обновления** to check manually or install. Clients verify the signed release manifest and the downloaded file's SHA-256 digest. Installation proceeds through Windows Setup or the Android package installer.

Use **Настройки → Диагностика** to view or save a JSON Lines log. Each line contains a UTC timestamp, platform, version, architecture, component and an event code. The app writes to the Windows temporary directory or the Android app cache. Logs are size-limited and older files are discarded at startup. They omit pairing codes, secrets, IP addresses, device names, and track metadata. Export is initiated by the user.

## Build

On Windows, install the .NET 10 SDK and Inno Setup 6.7. Use PowerShell from the project directory.

```powershell
New-Item -ItemType Directory -Force artifacts | Out-Null
.\Build-Windows.ps1 -InnoCompiler 'C:\Program Files (x86)\Inno Setup 6\ISCC.exe' -OutputDirectory .\artifacts -Architecture x64
.\Build-Windows.ps1 -InnoCompiler 'C:\Program Files (x86)\Inno Setup 6\ISCC.exe' -OutputDirectory .\artifacts -Architecture arm64
```

Android needs a JDK, Android SDK Platform 35, Build Tools 35 and [ZXing Core 3.5.3](https://repo.maven.apache.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar).

```powershell
.\Build-Android.ps1 -PlatformJar <android.jar> -BuildTools <build-tools> -ZxingJar <core.jar> -Output .\artifacts\CueRelay.apk -Keystore <keystore.p12>
```

Android updates require the same package-signing key as the installed app. The official release manifest is signed with a separate key stored outside the repository. A maintainer can run `Sign-Release.ps1` with that Windows DPAPI-protected key, then attach both manifest files and the three installers to a GitHub Release tagged `v<version>`. Keep the APK signing key and the release signing key out of Git.
