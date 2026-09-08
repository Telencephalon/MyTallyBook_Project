[CmdletBinding()]
param([switch]$ExistingConfigurationOnly)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$databasePassword = $null
$weChatAppSecret = $null
$keys = $null
$startInfo = $null
$child = $null
$exitCode = 1
$stage = 'preflight'
try {
    Import-Module (Join-Path $PSScriptRoot 'LocalBackend.psm1') -Force -DisableNameChecking
    Import-Module (Join-Path $PSScriptRoot 'LocalBackendCredentials.psm1') -Force -DisableNameChecking
    Write-Host 'Checking local Java, packaged migrations, Git exclusions and listeners...'
    Assert-LocalBackendPreflight -RepoRoot $repoRoot
    $stage = 'encrypted key storage'
    if (-not $ExistingConfigurationOnly) {
        $metadata = Initialize-LocalBackendKeys -RepoRoot $repoRoot
        if ($metadata.Created) { Write-Host 'Created current-user encrypted local keys.' }
        else { Write-Host 'Reusing existing current-user encrypted local keys.' }
    }
    $keys = Get-LocalBackendKeys -RepoRoot $repoRoot
    $stage = 'encrypted credential storage'
    $credentials = if ($ExistingConfigurationOnly) {
        Get-LocalBackendCredentials -RepoRoot $repoRoot
    } else {
        Get-LocalBackendStartupCredentials -RepoRoot $repoRoot
    }
    $databasePassword = $credentials.DatabasePassword
    $weChatAppSecret = $credentials.WeChatAppSecret
    $credentials = $null
    $stage = 'child configuration'
    $startInfo = New-LocalBackendStartInfo -RepoRoot $repoRoot -Keys $keys -DatabasePassword $databasePassword -WeChatAppSecret $weChatAppSecret
    $stage = 'final listener check'
    Assert-LocalBackendPorts
    $stage = 'Java launch or execution'
    Write-Host 'Starting local backend at 127.0.0.1:7631. Java output follows in this console.'
    $child = [Diagnostics.Process]::Start($startInfo)
    # The child has its own environment copy; release our plaintext references now.
    $startInfo.EnvironmentVariables.Clear()
    $databasePassword.Dispose(); $databasePassword = $null
    $weChatAppSecret.Dispose(); $weChatAppSecret = $null
    $keys.BootstrapKey.Dispose(); $keys.TokenPepper.Dispose(); $keys = $null
    $child.WaitForExit()
    $exitCode = $child.ExitCode
    Write-Host ('Backend exit code: ' + $exitCode)
} catch {
    # Never print exceptions, credentials, process metadata or environment contents.
    Write-Host ('Local backend startup failed during ' + $stage + '. No automatic repair was attempted.')
} finally {
    if ($null -ne $startInfo) { $startInfo.EnvironmentVariables.Clear() }
    if ($null -ne $databasePassword) { $databasePassword.Dispose() }
    if ($null -ne $weChatAppSecret) { $weChatAppSecret.Dispose() }
    if ($null -ne $keys) { $keys.BootstrapKey.Dispose(); $keys.TokenPepper.Dispose() }
    if ($null -ne $child) { $child.Dispose() }
}
exit $exitCode
