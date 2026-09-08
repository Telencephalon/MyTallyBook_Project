[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$exitCode = 1
try {
    Import-Module (Join-Path $PSScriptRoot 'LocalBootstrapKey.psm1') -Force -DisableNameChecking -ErrorAction Stop
    $repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
    $exitCode = Invoke-LocalBootstrapKeyDisplay -RepoRoot $repoRoot
} catch {
    try { [Console]::Error.WriteLine('Bootstrap key display failed or was refused. No automatic changes were made.') } catch { }
}
exit $exitCode
