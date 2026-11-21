param(
    [Parameter(Mandatory)] [ValidatePattern('^(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})$')] [string] $Version,
    [Parameter(Mandatory)] [string] $ArtifactDirectory,
    [Parameter(Mandatory)] [string] $ProtectedKey
)

$ErrorActionPreference = 'Stop'
$directory = (Resolve-Path -LiteralPath $ArtifactDirectory).Path
$keyPath = (Resolve-Path -LiteralPath $ProtectedKey).Path
$pinnedKey = (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'Update-Channel.txt'))[1].Trim()
$assets = [ordered]@{}
foreach ($name in 'CueRelay.apk', 'CueRelay-Setup-x64.exe', 'CueRelay-Setup-arm64.exe') {
    $file = Get-Item -LiteralPath (Join-Path $directory $name)
    if ($file.Extension -eq '.exe' -and $file.VersionInfo.ProductVersion.Trim() -ne $Version) {
        throw "Unexpected version in $name"
    }
    $assets[$name] = [ordered]@{ size = $file.Length; sha256 = (Get-FileHash -LiteralPath $file.FullName).Hash.ToLowerInvariant() }
}
$manifest = [Text.Encoding]::UTF8.GetBytes(([ordered]@{ version = $Version; assets = $assets } |
    ConvertTo-Json -Depth 4 -Compress) + [Environment]::NewLine)
$privateKey = [Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes($keyPath), $null,
    [Security.Cryptography.DataProtectionScope]::CurrentUser)
$rsa = [Security.Cryptography.RSA]::Create()
try {
    $read = 0
    $rsa.ImportPkcs8PrivateKey($privateKey, [ref] $read)
    if ([Convert]::ToBase64String($rsa.ExportSubjectPublicKeyInfo()) -ne $pinnedKey) {
        throw 'Release key does not match the key embedded in the update channel'
    }
    $signature = $rsa.SignData($manifest, [Security.Cryptography.HashAlgorithmName]::SHA256,
        [Security.Cryptography.RSASignaturePadding]::Pkcs1)
    [IO.File]::WriteAllBytes((Join-Path $directory 'CueRelay-Update.json'), $manifest)
    [IO.File]::WriteAllBytes((Join-Path $directory 'CueRelay-Update.sig'), $signature)
} finally {
    [Array]::Clear($privateKey, 0, $privateKey.Length)
    $rsa.Dispose()
}
