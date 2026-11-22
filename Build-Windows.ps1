param(
    [string] $InnoCompiler,
    [Parameter(Mandatory)] [string] $OutputDirectory,
    [string] $DotnetExe = (Get-Command dotnet).Source,
    [ValidateSet('x64','arm64')] [string] $Architecture = 'x64',
    [ValidateSet('Dev','Prod')] [string] $Mode = 'Dev'
)

$ErrorActionPreference = 'Stop'
$output = (Resolve-Path -LiteralPath $OutputDirectory).Path
$project = Join-Path $PSScriptRoot 'windows\PocketPlayer.csproj'
if ($Mode -eq 'Dev') {
    & $DotnetExe publish $project -c Release -r "win-$Architecture" -p:CueRelayDevelopment=true -o (Join-Path $output "CueRelay-Dev-$Architecture")
    if ($LASTEXITCODE -ne 0) { throw 'Dev build failed' }
    return
}
if (!$InnoCompiler) { throw 'Prod requires -InnoCompiler' }
$compiler = (Resolve-Path -LiteralPath $InnoCompiler).Path
$publish = Join-Path $PSScriptRoot ".windows-publish-$Architecture"

& $DotnetExe publish $project -c Release -r "win-$Architecture" -p:CueRelayDevelopment=false -o $publish
if ($LASTEXITCODE -ne 0) { throw 'dotnet publish failed' }

$assets = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'windows\obj\project.assets.json') -Raw | ConvertFrom-Json
$packageFolders = @($assets.packageFolders.PSObject.Properties.Name)
$published = Get-Content -LiteralPath (Join-Path $publish 'CueRelay.deps.json') -Raw | ConvertFrom-Json
$runtimeLibraries = $published.libraries.PSObject.Properties.Name | Where-Object {
    $_ -match "^runtimepack\.Microsoft\.(NETCore|WindowsDesktop|AspNetCore)\.App\.Runtime\.win-$Architecture/"
}
$licenses = Join-Path $publish 'licenses'
foreach ($library in $runtimeLibraries) {
    $packagePath = $library.Substring('runtimepack.'.Length)
    $runtime = ($packagePath -split '/')[0]
    foreach ($folder in $packageFolders) {
        $package = Join-Path $folder $packagePath.ToLowerInvariant()
        if (!(Test-Path -LiteralPath $package)) { continue }
        Get-ChildItem -LiteralPath $package -File | Where-Object Name -Match 'LICENSE|NOTICE' | ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $licenses "$runtime-$($_.Name)")
        }
        break
    }
}

& $compiler "/DPublishDir=$publish" "/DArtifactDir=$output" "/DBuildArchitecture=$Architecture" /Qp (Join-Path $PSScriptRoot 'installer\PocketPlayer.iss')
if ($LASTEXITCODE -ne 0) { throw 'Inno Setup build failed' }
