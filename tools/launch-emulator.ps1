# Запуск Pixel_6_API_34 (AVD на C:, без snapshots).
$ErrorActionPreference = "Stop"
Remove-Item Env:ANDROID_AVD_HOME -ErrorAction SilentlyContinue
$env:Path = "$env:LOCALAPPDATA\Android\Sdk\emulator;$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:Path"

$avdDir = "$env:USERPROFILE\.android\avd\Pixel_6_API_34.avd"
$logFile = "M:\MyBudget\.temp\emulator-launch.log"

Remove-Item "$avdDir\snapshots" -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item "$avdDir\multiinstance.lock","$avdDir\read-snapshot.txt" -Force -ErrorAction SilentlyContinue
Remove-Item "$avdDir\hardware-qemu.ini.lock" -Recurse -Force -ErrorAction SilentlyContinue

$running = Get-Process qemu-system*, emulator -ErrorAction SilentlyContinue
if ($running) {
    Write-Host "Эмулятор уже запущен (PID $($running.Id -join ', '))."
    adb devices -l
    exit 0
}

Write-Host "AVD: $avdDir"
Write-Host "Лог: $logFile"

$emu = "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe"
$args = @(
    "-avd", "Pixel_6_API_34",
    "-gpu", "host",
    "-cores", "2",
    "-memory", "2048",
    "-no-snapshot-load",
    "-no-snapshot-save"
)

Start-Process -FilePath $emu -ArgumentList $args -RedirectStandardOutput $logFile -RedirectStandardError "$logFile.err" -WindowStyle Normal
Write-Host "Запущен. Cold boot на C: — обычно 2–5 минут."
Write-Host "Проверка: adb wait-for-device; adb shell getprop sys.boot_completed"
