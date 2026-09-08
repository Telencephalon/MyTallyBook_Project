[CmdletBinding()]
param([switch]$UpdateBackend)

# Only copied into an isolated temporary repository by Test-DevLauncher.ps1.
Write-Output ('FIXTURE_ROOT=' + $PSScriptRoot)
Write-Output ('FIXTURE_UPDATE=' + [string][bool]$UpdateBackend)
if ($env:MTB_LAUNCHER_TEST_EXIT -eq '7') { exit 7 }
exit 0
