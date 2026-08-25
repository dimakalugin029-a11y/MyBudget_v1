# Install debug APK over the existing app (preserves budget data when signatures match).
# Usage:
#   .\tools\install-debug.ps1              # normal update
#   .\tools\install-debug.ps1 -ForceReinstall   # uninstall first (data is lost)

param(
    [switch]$ForceReinstall
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path $PSScriptRoot -Parent
$Pkg = "ru.mybudget.app"
$Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "C:\Android\Sdk" }
$Adb = Join-Path $Sdk "platform-tools\adb.exe"
$Keystore = Join-Path $ProjectRoot "keystore\debug.keystore"
$Apk = Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"

$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
$env:Path = "$Sdk\platform-tools;$Sdk\emulator;$env:Path"

Set-Location $ProjectRoot

function Normalize-CertSha256 {
    param([string]$Value)
    if (-not $Value) { return $null }
    return ($Value -replace ":", "").Trim().ToUpperInvariant()
}
function Get-ExpectedCertSha256 {
    if (-not (Test-Path $Keystore)) {
        throw "Missing project keystore: $Keystore`nPull the repo or copy keystore/debug.keystore from GitHub."
    }
    $line = keytool -list -v -keystore $Keystore -storepass android 2>$null |
        Select-String -Pattern "^\s*SHA256:" |
        Select-Object -First 1
    if (-not $line) {
        throw "Could not read SHA-256 from $Keystore"
    }
    return Normalize-CertSha256 (($line -replace "^\s*SHA256:\s*", "").Trim())
}

function Find-ApkSigner {
    $buildTools = Join-Path $Sdk "build-tools"
    if (-not (Test-Path $buildTools)) { return $null }
    $latest = Get-ChildItem $buildTools -Directory | Sort-Object Name -Descending | Select-Object -First 1
    if (-not $latest) { return $null }
    $signer = Join-Path $latest.FullName "apksigner.bat"
    if (Test-Path $signer) { return $signer }
    return $null
}

function Get-ApkCertSha256 {
    param([string]$ApkPath)
    $signer = Find-ApkSigner
    if (-not $signer) { return $null }
    $out = & $signer verify --print-certs $ApkPath 2>&1 | Out-String
    $line = $out | Select-String -Pattern "Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]+)" -AllMatches
    if ($line.Matches.Count -gt 0) {
        return $line.Matches[0].Groups[1].Value.ToUpperInvariant()
    }
    return $null
}

function Invoke-Adb {
    param([string[]]$Args)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $Adb @Args
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
    }
}

function Get-InstalledCertSha256 {
    param([string]$Package)
    $pathLine = & $Adb shell pm path $Package 2>$null
    if (-not $pathLine -or $pathLine -notmatch "base.apk") { return $null }
    $remote = ($pathLine -split ":", 2)[1].Trim()
    $tempApk = Join-Path $env:TEMP "mybudget-installed-check.apk"
    if (Test-Path $tempApk) { Remove-Item $tempApk -Force }
    $pullCode = Invoke-Adb -Args @("pull", $remote, $tempApk)
    if ($pullCode -ne 0 -or -not (Test-Path $tempApk)) { return $null }
    try {
        return Get-ApkCertSha256 -ApkPath $tempApk
    } finally {
        Remove-Item $tempApk -Force -ErrorAction SilentlyContinue
    }
}

if (-not (Test-Path $Adb)) {
    Write-Host "adb not found: $Adb" -ForegroundColor Red
    Write-Host "Set ANDROID_HOME or run tools\fix-emulator-paths.ps1"
    exit 1
}

$devices = & $Adb devices | Select-String "device$"
if (-not $devices) {
    Write-Host "No emulator/device online. Start emulator first:" -ForegroundColor Yellow
    Write-Host "  .\tools\launch-emulator.ps1"
    exit 1
}

try {
    $expectedSha = Get-ExpectedCertSha256
    Write-Host "Project debug cert SHA-256: $expectedSha"
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}

$installedSha = Get-InstalledCertSha256 -Package $Pkg
if ($installedSha) {
    $installedSha = Normalize-CertSha256 $installedSha
    Write-Host "Installed app cert SHA-256:   $installedSha"
    if ($installedSha -ne $expectedSha -and -not $ForceReinstall) {
        Write-Host ""
        Write-Host "Signature mismatch: update would fail or require uninstall (data loss)." -ForegroundColor Yellow
        Write-Host "Do NOT install from Android Studio with a different debug key."
        Write-Host "1. Export backup in the app (Settings -> Backup -> Export)"
        Write-Host "2. Run once: .\tools\install-debug.ps1 -ForceReinstall"
        Write-Host "3. Import backup"
        Write-Host ""
        Write-Host "Use only this script or 'gradlew installDebug' with keystore/debug.keystore."
        exit 1
    }
} else {
    Write-Host "App not installed yet (fresh install)."
}

if ($ForceReinstall) {
    Write-Host "Uninstalling $Pkg (all app data will be removed)..." -ForegroundColor Yellow
    & $Adb uninstall $Pkg | Out-Null
}

Write-Host "Building and installing debug APK..."
& .\gradlew.bat installDebug
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "Install failed." -ForegroundColor Red
    Write-Host ""
    Write-Host "If you see INSTALL_FAILED_UPDATE_INCOMPATIBLE:" -ForegroundColor Yellow
    Write-Host "  Export backup, then run: .\tools\install-debug.ps1 -ForceReinstall"
    exit $LASTEXITCODE
}

if (-not (Test-Path $Apk)) {
    Write-Host "APK not found after install: $Apk" -ForegroundColor Yellow
} else {
    $apkSha = Get-ApkCertSha256 -ApkPath $Apk
    $apkSha = Normalize-CertSha256 $apkSha
    if ($apkSha -and $apkSha -ne $expectedSha) {
        Write-Host "WARNING: Built APK cert does not match keystore/debug.keystore!" -ForegroundColor Red
        Write-Host "APK SHA-256: $apkSha"
        exit 1
    }
}

Write-Host "Done. App updated with project debug key; data kept when signatures matched." -ForegroundColor Green
Write-Host "If the home-screen widget shows an error, remove it and add again."
