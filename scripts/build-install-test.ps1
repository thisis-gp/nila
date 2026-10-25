[CmdletBinding()]
param(
    [switch]$BuildOnly,
    [string]$Serial,
    [string]$AssistantModelPath,
    [string]$RouterModelPath
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$gradleVersion = '9.6.0'
$gradleHome = Join-Path $root ".tools\gradle-$gradleVersion"
$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'

function Find-Command([string]$Name) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    return $null
}

$java = Find-Command 'java'
if (-not $java) {
    $javaCandidates = @(
        $(if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' }),
        (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr\bin\java.exe')
    ) | Where-Object { $_ -and (Test-Path $_) }
    $java = $javaCandidates | Select-Object -First 1
}
if (-not $java) { throw 'JDK 17 is required. Install Android Studio or a JDK.' }
$env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $java)
$env:Path = "$(Split-Path -Parent $java);$env:Path"

if (-not $env:ANDROID_SDK_ROOT) {
    $defaultSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path $defaultSdk) { $env:ANDROID_SDK_ROOT = $defaultSdk }
}

$gradlew = Join-Path $root 'gradlew.bat'
if (Test-Path $gradlew) {
    $gradle = $gradlew
} elseif ($systemGradle = Find-Command 'gradle') {
    $gradle = $systemGradle
} else {
    $archive = Join-Path $root ".tools\gradle-$gradleVersion-bin.zip"
    if (-not (Test-Path (Join-Path $gradleHome 'bin\gradle.bat'))) {
        New-Item -ItemType Directory -Force (Split-Path $archive) | Out-Null
        Invoke-WebRequest "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip" -OutFile $archive
        Expand-Archive $archive (Split-Path $gradleHome) -Force
    }
    $gradle = Join-Path $gradleHome 'bin\gradle.bat'
}

Push-Location $root
try {
    & $gradle testDebugUnitTest assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE." }
} finally {
    Pop-Location
}

if (-not (Test-Path $apk)) { throw "APK was not produced at $apk" }
Write-Host "APK: $apk"
if ($BuildOnly) { exit 0 }

$adb = Find-Command 'adb'
if (-not $adb) {
    $sdk = $env:ANDROID_SDK_ROOT
    if (-not $sdk) { $sdk = $env:ANDROID_HOME }
    if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $candidate = Join-Path $sdk 'platform-tools\adb.exe'
    if (Test-Path $candidate) { $adb = $candidate }
}
if (-not $adb) { throw 'ADB was not found. Install Android SDK Platform-Tools or add adb to PATH.' }

$adbArgs = @()
if ($Serial) { $adbArgs += @('-s', $Serial) }
$devices = & $adb @adbArgs devices
if (-not ($devices -match "\sdevice$")) { throw 'No authorized Android device found. Enable USB debugging and accept the device prompt.' }

& $adb @adbArgs install -r -t $apk
if ($LASTEXITCODE -ne 0) { throw "ADB install failed with exit code $LASTEXITCODE." }

function Copy-ModelIntoApp([string]$Source, [string]$Name) {
    if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) { throw "Model file not found: $Source" }
    $stage = "/data/local/tmp/$Name"
    $expectedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $Source).Hash.ToLowerInvariant()
    $stagedHash = (& $adb @adbArgs shell sha256sum $stage) -split '\s+' | Select-Object -First 1
    if ($stagedHash -ne $expectedHash) {
        & $adb @adbArgs push $Source $stage
        if ($LASTEXITCODE -ne 0) { throw "Could not copy $Name to the device." }
        $stagedHash = (& $adb @adbArgs shell sha256sum $stage) -split '\s+' | Select-Object -First 1
        if ($LASTEXITCODE -ne 0 -or $stagedHash -ne $expectedHash) { throw "Staged SHA-256 verification failed for $Name." }
    }
    & $adb @adbArgs shell run-as com.nila.launcher mkdir -p files/models
    if ($LASTEXITCODE -ne 0) { throw "Could not create NILA's private model directory." }
    & $adb @adbArgs shell run-as com.nila.launcher cp $stage "files/models/$Name"
    if ($LASTEXITCODE -ne 0) { throw "Could not install $Name into NILA's private model directory." }
    $copyHash = (& $adb @adbArgs shell run-as com.nila.launcher sha256sum "files/models/$Name")
    if ($LASTEXITCODE -ne 0 -or $copyHash -notmatch $expectedHash) { throw "SHA-256 verification failed for $Name." }
    Write-Host "Verified model: $Name ($expectedHash)"
}

if ($AssistantModelPath) {
    $assistantName = switch ([System.IO.Path]::GetFileName($AssistantModelPath)) {
        'gemma-4-E2B-it.litertlm' { 'gemma-4-E2B-it.litertlm' }
        default { throw "Unsupported assistant filename: $([System.IO.Path]::GetFileName($AssistantModelPath))" }
    }
    Copy-ModelIntoApp $AssistantModelPath $assistantName
}
if ($RouterModelPath) {
    Copy-ModelIntoApp $RouterModelPath 'nila-router-functiongemma-run8-q8-ekv4096.litertlm'
}

& $adb @adbArgs shell am force-stop com.nila.launcher
& $adb @adbArgs shell am start -n com.nila.launcher/.MainActivity
if ($LASTEXITCODE -ne 0) { throw "NILA failed to launch with exit code $LASTEXITCODE." }

$resumed = & $adb @adbArgs shell dumpsys activity activities
if (-not ($resumed -match 'com\.nila\.launcher')) { throw 'NILA installed but the activity smoke check did not find it in the activity stack.' }
Write-Host 'NILA built, installed and launched successfully.'
