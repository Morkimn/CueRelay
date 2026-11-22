param(
    [Parameter(Mandatory)] [string] $PlatformJar,
    [Parameter(Mandatory)] [string] $BuildTools,
    [Parameter(Mandatory)] [string] $ZxingJar,
    [Parameter(Mandatory)] [string] $Output,
    [string] $Keystore,
    [ValidateSet('Dev','Prod')] [string] $Mode = 'Dev'
)

$ErrorActionPreference = 'Stop'
$root = Join-Path $PSScriptRoot 'android'
$build = Join-Path $PSScriptRoot ".android-build-$Mode"
$generated = Join-Path $build 'generated'
$classes = Join-Path $build 'classes'
$dex = Join-Path $build 'dex'
$assets = Join-Path $build 'assets'
New-Item -ItemType Directory -Force $build, $generated, $classes, $dex, (Join-Path $assets 'licenses') | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'locales\en.txt') -Destination $assets
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'licenses\ZXing-LICENSE.txt'),
    (Join-Path $PSScriptRoot 'licenses\GoogleSans-OFL.txt') -Destination (Join-Path $assets 'licenses')
$manifest = Join-Path $root 'AndroidManifest.xml'
$alias = 'pocket-player-dev'
$profile = 'false'
$port = 43198
$title = 'CueRelay'
if ($Mode -eq 'Dev') {
    if ($Keystore) { throw 'Dev builds use their own generated key. Omit -Keystore.' }
    if ([IO.Path]::GetFileName($Output) -ne 'CueRelay-Dev.apk') { throw 'Use CueRelay-Dev.apk for Dev output.' }
    $profile = 'true'
    $port = 43199
    $title = 'CueRelay Dev'
    $alias = 'cuerelay-local-dev'
    $Keystore = Join-Path $build 'dev-signing.p12'
    $manifestXml = (Get-Content -LiteralPath $manifest -Raw).Replace('package="com.codex.pocketplayer"', 'package="com.codex.pocketplayer.dev"').
        Replace('android:authorities="com.codex.pocketplayer.files"', 'android:authorities="com.codex.pocketplayer.dev.files"').
        Replace('android:name=".', 'android:name="com.codex.pocketplayer.').Replace('android:label="CueRelay', 'android:label="CueRelay Dev')
    $manifest = Join-Path $build 'AndroidManifest.xml'
    Set-Content -LiteralPath $manifest -Value $manifestXml -Encoding utf8
} else {
    if (!$Keystore -or !(Test-Path -LiteralPath $Keystore -PathType Leaf)) { throw 'Prod requires the existing release keystore.' }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Update-Channel.txt') -Destination $assets
}
@"
package com.codex.pocketplayer;
final class BuildProfile {
    static final boolean DEV = $profile;
    static final int PORT = $port;
    static final String TITLE = "$title";
}
"@ | Set-Content -LiteralPath (Join-Path $generated 'BuildProfile.java') -Encoding utf8
$buildToolsPath = (Resolve-Path -LiteralPath $BuildTools).Path
$jar = (Resolve-Path -LiteralPath $PlatformJar).Path
$zxing = (Resolve-Path -LiteralPath $ZxingJar).Path
$aapt = Join-Path $buildToolsPath 'aapt2.exe'
$compiled = Join-Path $build 'resources.zip'
$unsigned = Join-Path $build 'unsigned.apk'
$aligned = Join-Path $build 'aligned.apk'
$classJar = Join-Path $build 'classes.jar'
$jdk = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { (Get-Command javac).Source | Split-Path }

function Assert-Tool([string] $Name) {
    if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit $LASTEXITCODE" }
}

& $aapt compile --dir (Join-Path $root 'res') -o $compiled
Assert-Tool 'aapt2 compile'
& $aapt link -o $unsigned -I $jar -A $assets --manifest $manifest --custom-package com.codex.pocketplayer --java $generated $compiled
Assert-Tool 'aapt2 link'
$sources = @(Get-ChildItem (Join-Path $root 'src'), $generated -Recurse -Filter '*.java' -File | ForEach-Object FullName)
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 --release 8 -Xlint:-options -classpath "$jar;$zxing" -d $classes @sources
Assert-Tool 'javac'
& (Join-Path $jdk 'jar.exe') cf $classJar -C $classes .
Assert-Tool 'jar'
& (Join-Path $buildToolsPath 'd8.bat') --release --min-api 26 --lib $jar --output $dex $classJar $zxing
Assert-Tool 'd8'
& (Join-Path $jdk 'jar.exe') uMf $unsigned -C $dex classes.dex
Assert-Tool 'jar (dex)'
& (Join-Path $buildToolsPath 'zipalign.exe') -f -p 4 $unsigned $aligned
Assert-Tool 'zipalign'

if (!(Test-Path -LiteralPath $Keystore)) {
    $generate = @('-genkeypair','-keystore',$Keystore,'-storepass','android','-keypass','android','-alias',$alias,
        '-keyalg','RSA','-keysize','2048','-validity','3650','-dname','CN=Pocket Player Development')
    & (Join-Path $jdk 'keytool.exe') @generate
    Assert-Tool 'keytool'
}
$sign = @('sign','--ks',$Keystore,'--ks-key-alias',$alias,'--ks-pass','pass:android',
    '--key-pass','pass:android','--v4-signing-enabled','false','--out',$Output,$aligned)
& (Join-Path $buildToolsPath 'apksigner.bat') @sign
Assert-Tool 'apksigner'
& (Join-Path $buildToolsPath 'apksigner.bat') verify $Output
Assert-Tool 'apksigner verify'
