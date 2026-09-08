[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$migrationPython = 'D:\Work\Tools\MyTallyBookOps\venv\Scripts\python.exe'
$migrationScript = Join-Path $PSScriptRoot 'run-dev-v2.py'
$Host.UI.RawUI.WindowTitle = 'MyTallyBook - Development V2 migration'
& $migrationPython -u $migrationScript
$migrationResult = $LASTEXITCODE
Write-Host "Migration exit code: $migrationResult. Keep this window open."
exit $migrationResult
