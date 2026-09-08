[CmdletBinding()]
param([switch]$UpdateBackend)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$messages = @{
    BackendPortRejected = 'Port 7631 has an unexpected address or multiple listeners.'
    BackendOwnerRejected = 'Port 7631 is not owned by the exact Java executable and project JAR.'
    TunnelPortRejected = 'Port 13306 is occupied by an unrecognized listener or SSH command.'
    ExistingBackendUnhealthy = 'The existing backend is not healthy. Inspect its console; it was not restarted.'
    ExistingConfigurationMissing = 'Existing keys.dpapi or credentials.dpapi is missing. No credentials or keys were created.'
    StartupDependencyMissing = 'The configured Java, existing JAR, Windows OpenSSH, PowerShell, or backend script is missing. Nothing was built or installed.'
    StartupAlreadyInProgress = 'Another startup for this repository is in progress. Use its existing password console.'
    TunnelAuthenticationPending = 'The known SSH process exists without its listener. Check the existing SSH password console; no duplicate was opened.'
    BackendStartupPending = 'The known project Java process exists without its listener. Check the existing backend console; no duplicate was opened.'
    TunnelReadinessTimeout = 'SSH did not become ready in time. Inspect the SSH password console. Backend was not launched.'
    TunnelReadinessLost = 'The verified SSH tunnel disappeared during startup. Inspect the SSH console.'
    BackendReadinessTimeout = 'The backend did not become healthy in time. Inspect its retained console.'
    BackendRecoveryTimeout = 'SSH was restored, but the existing backend did not recover in time. Inspect its console or startup logs. It was not restarted.'
    UpdatePreflightFailed = 'The backend update preflight failed before the running backend was stopped.'
    BackendBackupFailed = 'The existing backend JAR could not be backed up; the running backend was not stopped.'
    BackendBackupVerificationFailed = 'The backend JAR backup could not be verified; the running backend was not stopped.'
    BackendIdentityUnavailable = 'The backend identity could not be pinned safely, so it was not stopped.'
    BackendIdentityChanged = 'The backend process identity changed before termination, so it was not stopped.'
    BackendTerminationUnavailable = 'Termination rights for the exact backend could not be acquired, so it was not stopped.'
    BackendTerminationFailed = 'The exact backend could not be terminated.'
    BackendStopTimeout = 'The exact backend did not exit within the bounded wait.'
    BackendWaitFailed = 'Windows could not verify that the exact backend exited; no build or relaunch was attempted.'
    BackendPortReleaseFailed = 'Port 7631 did not become safely free after the backend exited.'
    BackendPackageFailed = 'The offline Maven package phase failed. The verified backup was retained and no backend was relaunched.'
    PackagedBackendRejected = 'The packaged backend failed artifact validation. The verified backup was retained and no backend was relaunched.'
}
try {
    Import-Module (Join-Path $PSScriptRoot 'DevStartup.psm1') -Force -DisableNameChecking
    $root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
    $null = Invoke-DevStartup -RepoRoot $root -UpdateBackend:$UpdateBackend
    exit 0
} catch {
    # Do not print exception details, process command lines, or secret values.
    $category = $_.Exception.Message
    if ($messages.ContainsKey($category)) { Write-Host ('Development startup stopped: ' + $messages[$category]) }
    else { Write-Host 'Development startup stopped: a required inspection or console launch failed.' }
    if ($UpdateBackend) {
        if ($_.Exception.Data.Contains('BackendBackupPath')) { Write-Host ('Verified backend backup: ' + [string]$_.Exception.Data['BackendBackupPath']) }
        Write-Host 'No automatic rollback, stale-JAR relaunch, SSH termination, retry, or repair was attempted. Any verified backup and service consoles remain under your control.'
    } else {
        Write-Host 'No process was stopped and no automatic retry or repair was attempted. Any opened service consoles remain under your control.'
    }
    exit 1
}
