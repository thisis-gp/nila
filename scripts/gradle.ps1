# Runs Gradle with the JDK, SDK and Gradle that build-install-test.ps1 uses.
# Example: .\scripts\gradle.ps1 :app:testDebugUnitTest --tests "com.nila.launcher.skills.*"
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $root '.tools\gradle-9.6.0\bin\gradle.bat'
if (-not (Test-Path $gradle)) {
    # First run in a fresh worktree: build-install-test.ps1 downloads Gradle into .tools.
    & (Join-Path $PSScriptRoot 'build-install-test.ps1') -BuildOnly
}
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    $env:JAVA_HOME = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
}
if (-not $env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
& $gradle -p $root @args
exit $LASTEXITCODE
