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

$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
$env:Path = "$Sdk\platform-tools;$Sdk\emulator;$env:Path"

Set-Location $ProjectRoot

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

if ($ForceReinstall) {
    Write-Host "Uninstalling $Pkg (all app data will be removed)..." -ForegroundColor Yellow
    & $Adb uninstall $Pkg | Out-Null
}

Write-Host "Installing debug build (update, data preserved if signature matches)..."
& .\gradlew.bat installDebug
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "Install failed." -ForegroundColor Red
    Write-Host ""
    Write-Host "If you see INSTALL_FAILED_UPDATE_INCOMPATIBLE:" -ForegroundColor Yellow
    Write-Host "  The old APK was signed with a different key (original release or old debug)."
    Write-Host "  1. Export backup: Settings -> Backup -> Export"
    Write-Host "  2. Run once: .\tools\install-debug.ps1 -ForceReinstall"
    Write-Host "  3. Import backup in the app"
    Write-Host "  After that, use .\tools\install-debug.ps1 without -ForceReinstall."
    Write-Host "  All future updates will keep your budget."
    exit $LASTEXITCODE
}

Write-Host "Done. App updated, data kept." -ForegroundColor Green
