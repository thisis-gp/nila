# Runs the App Builder eval (spec §9.1) on a connected phone and prints the summary.
# Needs gemma-4-E2B-it.litertlm in the app's files/models. installDebug keeps app data; connectedAndroidTest would not.
[CmdletBinding()]
param([string]$Serial)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$adbArgs = if ($Serial) { @('-s', $Serial) } else { @() }

& (Join-Path $PSScriptRoot 'gradle.ps1') :app:installDebug :app:installDebugAndroidTest
if ($LASTEXITCODE) { exit $LASTEXITCODE }

adb @adbArgs shell am instrument -w -e class com.nila.launcher.skills.RecipeEvalTest com.nila.launcher.test/androidx.test.runner.AndroidJUnitRunner
$out = Join-Path $root 'build'
New-Item -ItemType Directory -Force $out | Out-Null
adb @adbArgs exec-out run-as com.nila.launcher cat files/recipe_eval.jsonl | Set-Content (Join-Path $out 'recipe_eval.jsonl')
adb @adbArgs exec-out run-as com.nila.launcher cat files/recipe_eval_summary.txt
