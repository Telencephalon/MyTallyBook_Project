[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$python = 'D:\Work\Tools\MyTallyBookOps\venv\Scripts\python.exe'
$script = Join-Path $PSScriptRoot 'prepare-dev-backup.py'
if (-not (Test-Path -LiteralPath $python)) {
    throw 'The isolated MyTallyBookOps Python runtime is missing.'
}
$Host.UI.RawUI.WindowTitle = 'MyTallyBook - Development backup preparation'
& $python -u $script
$result = $LASTEXITCODE
Write-Host "Preparation exit code: $result. Keep this window open."
exit $result
