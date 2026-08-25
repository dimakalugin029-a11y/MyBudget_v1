# Move Android SDK and AVD to ASCII paths (fix adb offline).
# Run:
#   powershell -ExecutionPolicy Bypass -File "M:\MyBudget\tools\fix-emulator-paths.ps1" -Force -CreateAvd
#
# Options:
#   -SkipSdkCopy   SDK already at C:\Android\Sdk
#   -Force         Stop emulator and remove old AVD
#   -CreateAvd     Create Pixel_6_API_34 via avdmanager

[CmdletBinding()]
param(
    [switch]$SkipSdkCopy,
    [switch]$Force,
    [switch]$CreateAvd
)

$ErrorActionPreference = "Stop"

$TargetSdk = "C:\Android\Sdk"
$TargetAvdHome = "M:\Android\avd"
$AvdName = "Pixel_6_API_34"
$SystemImage = "system-images;android-34;google_apis;x86_64"
$LogDir = "M:\MyBudget\.temp"
$LogFile = Join-Path $LogDir "fix-emulator-paths.log"

function Write-Log($Message, [ConsoleColor]$Color = [ConsoleColor]::Gray) {
    $line = "[$(Get-Date -Format 'HH:mm:ss')] $Message"
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
    Write-Host $line -ForegroundColor $Color
}

function Stop-EmulatorProcesses {
    $procs = Get-Process qemu-system*, emulator -ErrorAction SilentlyContinue
    if (-not $procs) {
        Write-Log "Emulator is not running."
        return
    }
    Write-Log "Stopping emulator (PID: $($procs.Id -join ', '))..." Yellow
    $procs | Stop-Process -Force
    Start-Sleep -Seconds 2
}

function Get-SourceSdkPath {
    foreach ($name in @("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
        $value = [Environment]::GetEnvironmentVariable($name, "User")
        if (-not $value) { $value = [Environment]::GetEnvironmentVariable($name, "Machine") }
        if ($value -and (Test-Path (Join-Path $value "platform-tools\adb.exe"))) {
            return $value.TrimEnd('\')
        }
    }
    $default = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path (Join-Path $default "platform-tools\adb.exe")) {
        return $default
    }
    throw "Android SDK not found. Install SDK via Android Studio."
}

function Test-AsciiPath([string]$Path) {
    foreach ($ch in $Path.ToCharArray()) {
        if ([int][char]$ch -gt 127) {
            return $false
        }
    }
    return $true
}

function Copy-AndroidSdk([string]$Source, [string]$Destination) {
    if (Test-Path (Join-Path $Destination "platform-tools\adb.exe")) {
        Write-Log "SDK already exists: $Destination" Green
        return
    }
    Write-Log "Copying SDK (may take 10-30 min)..." Yellow
    Write-Log "  from: $Source"
    Write-Log "  to:   $Destination"
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    $robolog = Join-Path $LogDir "robocopy-sdk.log"
    & robocopy.exe $Source $Destination /E /R:2 /W:5 /NFL /NDL /NP /LOG:$robolog | Out-Null
    $code = $LASTEXITCODE
    if ($code -ge 8) {
        throw "robocopy failed (exit code $code). See $robolog"
    }
    if (-not (Test-Path (Join-Path $Destination "platform-tools\adb.exe"))) {
        throw "adb.exe not found in $Destination after copy"
    }
    Write-Log "SDK copy finished." Green
}

function Set-AndroidEnvVars {
    param([string]$Sdk, [string]$AvdHome)
    Write-Log "Setting user environment variables..." Yellow
    $vars = @{
        ANDROID_HOME     = $Sdk
        ANDROID_SDK_ROOT = $Sdk
        ANDROID_AVD_HOME = $AvdHome
        ANDROID_SDK_HOME = $AvdHome
    }
    foreach ($key in $vars.Keys) {
        $value = $vars[$key]
        [Environment]::SetEnvironmentVariable($key, $value, "User")
        Set-Item -Path "Env:$key" -Value $value
        Write-Log "  $key = $value"
    }
}

function Remove-OldAvdArtifacts {
    $oldAvdHome = Join-Path $env:USERPROFILE ".android\avd"
    $oldAvdDir = Join-Path $oldAvdHome "$AvdName.avd"
    $oldIni = Join-Path $oldAvdHome "$AvdName.ini"
    if (Test-Path $oldAvdDir) {
        Write-Log "Removing old AVD: $oldAvdDir" Yellow
        Remove-Item $oldAvdDir -Recurse -Force
    }
    if (Test-Path $oldIni) {
        Remove-Item $oldIni -Force
    }
    $newAvdDir = Join-Path $TargetAvdHome "$AvdName.avd"
    $newIni = Join-Path $TargetAvdHome "$AvdName.ini"
    if (Test-Path $newAvdDir) {
        Write-Log "Removing AVD in new location: $newAvdDir" Yellow
        Remove-Item $newAvdDir -Recurse -Force
    }
    if (Test-Path $newIni) {
        Remove-Item $newIni -Force
    }
}

function New-AndroidAvd([string]$Sdk) {
    $avdManager = Join-Path $Sdk "cmdline-tools\latest\bin\avdmanager.bat"
    if (-not (Test-Path $avdManager)) {
        $avdManager = Get-ChildItem (Join-Path $Sdk "cmdline-tools") -Recurse -Filter "avdmanager.bat" -ErrorAction SilentlyContinue |
            Select-Object -First 1 -ExpandProperty FullName
    }
    if (-not $avdManager) {
        Write-Log "avdmanager not found. Create AVD manually in Android Studio." Yellow
        return
    }
    $sdkManager = Join-Path (Split-Path $avdManager -Parent) "sdkmanager.bat"
    if (Test-Path $sdkManager) {
        Write-Log "Installing system-image android-34 if needed..." Yellow
        & $sdkManager --install $SystemImage --channel=0 | Out-Host
    }
    Write-Log "Creating AVD $AvdName..." Yellow
    echo "no" | & $avdManager create avd -f -n $AvdName -k $SystemImage -d pixel_6
    if ($LASTEXITCODE -ne 0) {
        Write-Log "avdmanager exit code $LASTEXITCODE. Create AVD manually." Yellow
        return
    }
    Write-Log "AVD created." Green
}

function Update-ProjectLocalProperties([string]$Sdk) {
    $props = Join-Path (Split-Path $PSScriptRoot -Parent) "local.properties"
    $content = "sdk.dir=$($Sdk.Replace('\', '/'))`n"
    Set-Content -Path $props -Value $content -Encoding ASCII
    Write-Log "Updated local.properties -> $props" Green
}

function Show-NextSteps {
    Write-Host ""
    Write-Host "=== Done. Next steps ===" -ForegroundColor Cyan
    Write-Host "1. Restart Android Studio and this terminal."
    Write-Host "2. Android Studio -> Settings -> Android SDK -> SDK Location = $TargetSdk"
    Write-Host "3. Device Manager -> Create Device -> Pixel 6 -> API 34 -> $AvdName"
    Write-Host "4. Cold boot emulator, then:"
    Write-Host "   adb devices"
    Write-Host "   adb shell getprop sys.boot_completed"
    Write-Host "5. Install MyBudget:"
    Write-Host "   adb install -r M:\MyBudget\app\build\outputs\apk\debug\app-debug.apk"
    Write-Host ""
    Write-Host "Launch emulator: M:\MyBudget\tools\launch-emulator.ps1" -ForegroundColor Green
    Write-Host "Log: $LogFile"
}

New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
Set-Content -Path $LogFile -Value "fix-emulator-paths $(Get-Date -Format o)" -Encoding UTF8

Write-Log "=== Fix Android Emulator (ASCII paths) ===" Cyan

$userProfile = $env:USERPROFILE
if (-not (Test-AsciiPath $userProfile)) {
    Write-Log "Windows profile has non-ASCII chars: $userProfile" Yellow
    Write-Log "SDK and AVD will move to C:\Android and M:\Android." Yellow
}

if (-not (Test-Path "M:\")) {
    throw "Drive M: is not available. Change TargetAvdHome in the script, e.g. C:\Android\avd."
}

Stop-EmulatorProcesses

$adbCandidates = @(
    (Join-Path $TargetSdk "platform-tools\adb.exe"),
    (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
)
foreach ($adb in $adbCandidates) {
    if (Test-Path $adb) {
        & $adb kill-server 2>$null | Out-Null
        break
    }
}

$sourceSdk = Get-SourceSdkPath
Write-Log "Source SDK: $sourceSdk"

if (-not $SkipSdkCopy -and ($sourceSdk -ne $TargetSdk)) {
    Copy-AndroidSdk -Source $sourceSdk -Destination $TargetSdk
} else {
    Write-Log "Skipping SDK copy (-SkipSdkCopy or SDK already in place)." Gray
}

New-Item -ItemType Directory -Path $TargetAvdHome -Force | Out-Null
Set-AndroidEnvVars -Sdk $TargetSdk -AvdHome $TargetAvdHome
Update-ProjectLocalProperties -Sdk $TargetSdk

if ($Force) {
    Remove-OldAvdArtifacts
}

if ($CreateAvd -or $Force) {
    New-AndroidAvd -Sdk $TargetSdk
}

Show-NextSteps
