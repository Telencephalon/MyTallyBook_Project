[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$modulePath = Join-Path (Split-Path $PSScriptRoot -Parent) 'NativeProcess.psm1'
Import-Module $modulePath -Force -DisableNameChecking
$script:passed = 0
$script:failed = 0

function Assert-True($Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Test-Case([string]$Name, [scriptblock]$Body) {
    try { & $Body; $script:passed++; Write-Host ('PASS ' + $Name) }
    catch { $script:failed++; Write-Host ('FAIL ' + $Name + ': ' + $_.Exception.Message) }
}

function Invoke-StopFixture([string]$Mutation = 'None') {
    $fixture = [pscustomobject]@{ Mutation = $Mutation; Events = [Collections.Generic.List[string]]::new(); Failure = $null }
    & (Get-Module NativeProcess) {
        param($State)
        function Open-NativeProcessHandle([int]$ProcessId, [bool]$Terminate) {
            $State.Events.Add(('open-' + $ProcessId + '-' + $Terminate))
            if ($State.Mutation -eq 'Open') { throw 'SyntheticOpenFailure' }
            return [IntPtr]123
        }
        function Read-NativeProcessHandle([IntPtr]$Handle, [int]$ProcessId) {
            $State.Events.Add('read')
            $created = if ($State.Mutation -eq 'Creation') { 222L } else { 111L }
            $command = if ($State.Mutation -eq 'Command') { 'java.exe -jar foreign.jar' } else { 'java.exe -jar expected.jar' }
            [pscustomobject]@{ ProcessId = $ProcessId; ExecutablePath = 'java.exe'; CommandLine = $command; CreationTimeFileTime = $created }
        }
        function Terminate-NativeProcessHandle([IntPtr]$Handle) {
            $State.Events.Add('terminate')
            if ($State.Mutation -eq 'Terminate') { throw 'SyntheticTerminateFailure' }
        }
        function Wait-NativeProcessHandle([IntPtr]$Handle, [int]$Milliseconds) {
            $State.Events.Add('wait-' + $Milliseconds)
            if ($State.Mutation -eq 'WaitFailed') { throw 'SyntheticWaitFailure' }
            return ($State.Mutation -ne 'Timeout')
        }
        function Close-NativeProcessHandle([IntPtr]$Handle) { $State.Events.Add('close') }
        $expected = [pscustomobject]@{ ProcessId = 424242; ExecutablePath = 'java.exe'; CommandLine = 'java.exe -jar expected.jar'; CreationTimeFileTime = 111L }
        $validator = { param($Current) $Current.ExecutablePath -ceq 'java.exe' -and $Current.CommandLine -ceq 'java.exe -jar expected.jar' }
        try { Stop-ExactNativeProcess -Expected $expected -IdentityValidator $validator -TimeoutSeconds 2 }
        catch { $State.Failure = $_.Exception.Message }
    } $fixture
    return $fixture
}

Test-Case 'pinned handle is opened with termination rights revalidated terminated waited and closed' {
    $state = Invoke-StopFixture
    Assert-True ($null -eq $state.Failure) ('Exact stop failed: ' + $state.Failure)
    Assert-True (($state.Events -join ',') -ceq 'open-424242-True,read,terminate,wait-2000,close') ('Wrong pinned stop order: ' + ($state.Events -join ','))
}

Test-Case 'creation or command drift closes handle without termination' {
    foreach ($mutation in @('Creation', 'Command')) {
        $state = Invoke-StopFixture $mutation
        Assert-True ($state.Failure -ceq 'BackendIdentityChanged') ($mutation + ' drift returned the wrong failure.')
        Assert-True (($state.Events -join ',') -ceq 'open-424242-True,read,close') ($mutation + ' drift reached termination or leaked the handle.')
    }
}

Test-Case 'open termination and timeout failures are sanitized and never retried' {
    $cases = @{ Open = 'BackendTerminationUnavailable'; Terminate = 'BackendTerminationFailed'; Timeout = 'BackendStopTimeout'; WaitFailed = 'BackendWaitFailed' }
    foreach ($mutation in $cases.Keys) {
        $state = Invoke-StopFixture $mutation
        Assert-True ($state.Failure -ceq $cases[$mutation]) ($mutation + ' returned the wrong sanitized failure.')
        Assert-True (@($state.Events | Where-Object { $_ -match '^open-' }).Count -eq 1) ($mutation + ' retried by PID.')
        Assert-True (@($state.Events | Where-Object { $_ -eq 'terminate' }).Count -le 1) ($mutation + ' retried termination.')
    }
}

Test-Case 'precise creation FILETIME is read natively when CIM exposes DateTime and full strings' {
    $state = [pscustomobject]@{ Events = [Collections.Generic.List[string]]::new(); Result = $null }
    & (Get-Module NativeProcess) {
        param($Fixture)
        function Open-NativeProcessHandle([int]$ProcessId, [bool]$Terminate) { $Fixture.Events.Add('open'); return [IntPtr]123 }
        function Read-NativeProcessHandle([IntPtr]$Handle, [int]$ProcessId) {
            $Fixture.Events.Add('read')
            [pscustomobject]@{ ProcessId = $ProcessId; ExecutablePath = 'C:\fixture\java.exe'; CommandLine = 'java fixture'; CreationTimeFileTime = 638927749231234567L }
        }
        function Close-NativeProcessHandle([IntPtr]$Handle) { $Fixture.Events.Add('close') }
        $cim = [pscustomobject]@{
            ProcessId = 777
            ExecutablePath = 'C:\fixture\java.exe'
            CommandLine = 'java fixture'
            CreationDate = [datetime]::new(2026, 9, 6, 1, 2, 3, [DateTimeKind]::Local)
        }
        $Fixture.Result = Get-LimitedProcessMetadata -Process $cim -RequirePreciseCreationTime
    } $state
    Assert-True (($state.Events -join ',') -ceq 'open,read,close') 'CIM DateTime took the lossy fast path.'
    Assert-True ($state.Result.CreationTimeFileTime -eq 638927749231234567L) 'Native FILETIME precision was not retained.'
}

Test-Case 'real synchronize-capable handle waits for one owned hidden child to exit' {
    $shell = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
    $child = Start-Process -FilePath $shell -ArgumentList @('-NoProfile', '-NonInteractive', '-Command', 'Start-Sleep -Milliseconds 750') -WindowStyle Hidden -PassThru
    try {
        $observation = & (Get-Module NativeProcess) {
            param($ProcessId)
            $handle = [IntPtr]::Zero
            try {
                $handle = Open-NativeProcessHandle -ProcessId $ProcessId -Terminate $true
                $metadata = Read-NativeProcessHandle -Handle $handle -ProcessId $ProcessId
                $waited = Wait-NativeProcessHandle -Handle $handle -Milliseconds 5000
                return [pscustomobject]@{ Metadata = $metadata; Waited = $waited }
            } finally { Close-NativeProcessHandle -Handle $handle }
        } $child.Id
        Assert-True $observation.Waited 'WaitForSingleObject did not observe the owned child exit.'
        Assert-True ($observation.Metadata.ProcessId -eq $child.Id -and $observation.Metadata.ExecutablePath -ieq $shell) 'Unicode native image inspection returned the wrong child identity.'
        Assert-True ($observation.Metadata.CreationTimeFileTime -gt 0 -and $observation.Metadata.CommandLine -match 'Start-Sleep') 'Native creation time or command-line inspection was unavailable.'
    } finally {
        if (-not $child.WaitForExit(5000)) { $child.Kill(); $child.WaitForExit() }
        $child.Dispose()
    }
}

Write-Host ('RESULT passed=' + $script:passed + ' failed=' + $script:failed)
if ($script:failed -gt 0) { exit 1 }
