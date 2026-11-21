param(
    [Parameter(Mandatory)] [string] $PlatformJar,
    [Parameter(Mandatory)] [string] $BuildTools,
    [Parameter(Mandatory)] [string] $ZxingJar,
    [Parameter(Mandatory)] [string] $Output,
    [Parameter(Mandatory)] [string] $Keystore
)

$ErrorActionPreference = 'Stop'
$root = Join-Path $PSScriptRoot 'android'
$build = Join-Path $PSScriptRoot '.android-build'
$generated = Join-Path $build 'generated'
$classes = Join-Path $build 'classes'
$dex = Join-Path $build 'dex'
$assets = Join-Path $build 'assets'
New-Item -ItemType Directory -Force $build, $generated, $classes, $dex, (Join-Path $assets 'licenses') | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'licenses\ZXing-LICENSE.txt'),
    (Join-Path $PSScriptRoot 'licenses\GoogleSans-OFL.txt') -Destination (Join-Path $assets 'licenses')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Update-Channel.txt') -Destination $assets
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
& $aapt link -o $unsigned -I $jar -A $assets --manifest (Join-Path $root 'AndroidManifest.xml') --java $generated $compiled
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
    $generate = @('-genkeypair','-keystore',$Keystore,'-storepass','android','-keypass','android','-alias','pocket-player-dev',
        '-keyalg','RSA','-keysize','2048','-validity','3650','-dname','CN=Pocket Player Development')
    & (Join-Path $jdk 'keytool.exe') @generate
    Assert-Tool 'keytool'
}
$sign = @('sign','--ks',$Keystore,'--ks-key-alias','pocket-player-dev','--ks-pass','pass:android',
    '--key-pass','pass:android','--v4-signing-enabled','false','--out',$Output,$aligned)
& (Join-Path $buildToolsPath 'apksigner.bat') @sign
Assert-Tool 'apksigner'
& (Join-Path $buildToolsPath 'apksigner.bat') verify $Output
Assert-Tool 'apksigner verify'
