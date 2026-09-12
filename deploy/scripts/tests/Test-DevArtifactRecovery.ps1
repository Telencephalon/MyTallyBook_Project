[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '../DevStartup.psm1') -Force -DisableNameChecking
$failed = 0
$passed = 0

function Check($Condition, $Message) { if (-not $Condition) { throw $Message } }
function Run-Case($Name, [scriptblock]$Test) {
    try { & $Test; $script:passed++; Write-Host "PASS $Name" }
    catch { $script:failed++; Write-Host "FAIL ${Name}: $($_.Exception.Message)" }
}
function Invoke-Fixture([string]$Mode) {
    $fixture = @{ Mode = $Mode; Built = $false; Failure = ''; Events = [Collections.Generic.List[string]]::new() }
    & (Get-Module DevStartup) {
        param($f)
        function Assert-LocalBackendArtifacts { param($RepoRoot) $f.Events.Add('environment') }
        function Assert-LocalBackendSourceMigrations {
            param($RepoRoot)
            $f.Events.Add('source')
            if ($f.Mode -eq 'SourceChanged') { throw 'SourceMigrationHashRejected' }
        }
        function Assert-DevPackagedBackend {
            param($RepoRoot)
            $f.Events.Add('validate')
            if ($f.Mode -ne 'Valid' -and (-not $f.Built -or $f.Mode -eq 'BadOutput')) { throw 'PackagedBackendRejected' }
        }
        function Assert-DevUpdatePreflight { param($RepoRoot) $f.Events.Add('build-preflight') }
        function Get-DevServiceState {
            param($RepoRoot)
            $f.Events.Add('state')
            return [pscustomobject]@{ BackendPresent = ($f.Mode -eq 'Running'); PendingBackend = ($f.Mode -eq 'Pending') }
        }
        function Test-Path { param($LiteralPath, $PathType) return $f.Mode -ne 'Missing' }
        function New-DevBackendBackup { param($RepoRoot) $f.Events.Add('backup'); return 'D:\fixture\backup.jar' }
        function Invoke-DevBackendPackage {
            param($RepoRoot)
            $f.Events.Add('build')
            if ($f.Mode -eq 'BuildFailed') { throw 'BackendPackageFailed' }
            $f.Built = $true
        }
        function Write-Host { param($Object) }
        try { Repair-DevBackendArtifact 'D:\fixture' }
        catch { $f.Failure = $_.Exception.Message }
    } $fixture
    return $fixture
}
Run-Case 'valid startup artifact is reused without building' {
    $f = Invoke-Fixture Valid
    Check ($f.Failure -eq '') $f.Failure
    Check (-not $f.Built) 'Valid artifact was rebuilt.'
}
Run-Case 'incomplete artifact is backed up and rebuilt exactly once' {
    $f = Invoke-Fixture Incomplete
    Check ($f.Failure -eq '') $f.Failure
    Check (@($f.Events | Where-Object { $_ -eq 'build' }).Count -eq 1) 'Expected exactly one build.'
    Check ($f.Events.IndexOf('backup') -lt $f.Events.IndexOf('build')) 'Build preceded backup.'
    Check ($f.Events[$f.Events.Count - 1] -eq 'validate') 'Rebuilt artifact was not validated.'
}
Run-Case 'missing artifact can be built without a nonexistent backup' {
    $f = Invoke-Fixture Missing
    Check ($f.Failure -eq '' -and $f.Built) $f.Failure
    Check (-not $f.Events.Contains('backup')) 'Missing file was backed up.'
}
Run-Case 'changed migration source is never repaired or bypassed' {
    $f = Invoke-Fixture SourceChanged
    Check ($f.Failure -eq 'SourceMigrationHashRejected' -and -not $f.Built) 'Source validation was bypassed.'
}
foreach ($mode in @('Running', 'Pending')) {
    Run-Case "$mode Java prevents overwriting its artifact" {
        $f = Invoke-Fixture $mode
        Check ($f.Failure -eq 'BackendStartupPending' -and -not $f.Built) 'Running Java was overwritten.'
    }
}
Run-Case 'build failure does not retry or launch a stale artifact' {
    $f = Invoke-Fixture BuildFailed
    Check ($f.Failure -eq 'BackendPackageFailed') $f.Failure
    Check (@($f.Events | Where-Object { $_ -eq 'build' }).Count -eq 1) 'Build retried.'
}
Run-Case 'invalid output from successful Maven is rejected' {
    $f = Invoke-Fixture BadOutput
    Check ($f.Failure -eq 'PackagedBackendRejected') $f.Failure
}
Write-Host "RESULT passed=$passed failed=$failed"
if ($failed) { exit 1 }
