# Launch Pixel_6_API_34 with ASCII paths (after fix-emulator-paths.ps1).
$ErrorActionPreference = "Stop"

$TargetSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "C:\Android\Sdk" }
$TargetAvdHome = if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } else { "M:\Android\avd" }

$env:ANDROID_HOME = $TargetSdk
$env:ANDROID_SDK_ROOT = $TargetSdk
$env:ANDROID_AVD_HOME = $TargetAvdHome
$env:ANDROID_SDK_HOME = $TargetAvdHome
$env:Path = "$TargetSdk\emulator;$TargetSdk\platform-tools;$env:Path"

$AvdName = "Pixel_6_API_34"
$avdDir = Join-Path $TargetAvdHome "$AvdName.avd"
$logFile = "M:\MyBudget\.temp\emulator-launch.log"

New-Item -ItemType Directory -Path (Split-Path $logFile -Parent) -Force | Out-Null

if (-not (Test-Path (Join-Path $TargetSdk "emulator\emulator.exe"))) {
    Write-Host "SDK not found: $TargetSdk" -ForegroundColor Red
    Write-Host "Run first: M:\MyBudget\tools\fix-emulator-paths.ps1"
    exit 1
}

if (-not (Test-Path $avdDir)) {
    Write-Host "AVD not found: $avdDir" -ForegroundColor Red
    Write-Host "Create AVD in Android Studio or run:"
    Write-Host "  M:\MyBudget\tools\fix-emulator-paths.ps1 -Force -CreateAvd"
    exit 1
}

Remove-Item (Join-Path $avdDir "snapshots") -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $avdDir "multiinstance.lock"), (Join-Path $avdDir "read-snapshot.txt") -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $avdDir "hardware-qemu.ini.lock") -Recurse -Force -ErrorAction SilentlyContinue

$running = Get-Process qemu-system*, emulator -ErrorAction SilentlyContinue
if ($running) {
    Write-Host "Emulator already running (PID $($running.Id -join ', '))."
    & adb devices -l
    exit 0
}

Write-Host "SDK:  $TargetSdk"
Write-Host "AVD:  $avdDir"
Write-Host "Log:  $logFile"

$emu = Join-Path $TargetSdk "emulator\emulator.exe"
$args = @(
    "-avd", $AvdName,
    "-gpu", "host",
    "-cores", "2",
    "-memory", "2048",
    "-no-snapshot-load",
    "-no-snapshot-save"
)

Start-Process -FilePath $emu -ArgumentList $args -RedirectStandardOutput $logFile -RedirectStandardError "$logFile.err" -WindowStyle Normal
Write-Host "Started. Cold boot usually takes 2-5 minutes."
Write-Host "Check:"
Write-Host "  adb wait-for-device"
Write-Host "  adb shell getprop sys.boot_completed"
