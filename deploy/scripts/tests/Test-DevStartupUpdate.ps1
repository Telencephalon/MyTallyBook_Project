[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$modulePath = Join-Path (Split-Path $PSScriptRoot -Parent) 'DevStartup.psm1'
Import-Module $modulePath -Force -DisableNameChecking
$script:passed = 0
$script:failed = 0

function Assert-True($Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Test-Case([string]$Name, [scriptblock]$Body) {
    try { & $Body; $script:passed++; Write-Host ('PASS ' + $Name) }
    catch { $script:failed++; Write-Host ('FAIL ' + $Name + ': ' + $_.Exception.Message) }
}

function New-UpdateState {
    [pscustomobject]@{
        Root = 'D:\offline fixtures\update repo'
        Update = $true
        InitialBackendPresent = $true
        InitialTunnelPresent = $true
        StartedTunnel = $false
        FailurePoint = 'None'
        Stopped = $false
        Started = $false
        Elapsed = 0
        HealthRequiresTunnel = $false
        HealthReadyAt = 0
        Events = [Collections.Generic.List[string]]::new()
        Launches = [Collections.Generic.List[string]]::new()
        Failure = $null
        Result = $null
        BackupPath = 'D:\offline fixtures\update repo\account-book-server\target\backups\account-book-server-20260906-010203-abcdef012345.jar'
        Backend = [pscustomobject]@{
            ProcessId = 1002
            ExecutablePath = 'D:/Work/Config/JDK/JDK/jdk21/bin/java.exe'
            CommandLine = '"D:/Work/Config/JDK/JDK/jdk21/bin/java.exe" -jar "D:\offline fixtures\update repo\account-book-server\target\account-book-server-0.0.1-SNAPSHOT.jar"'
            CreationTimeFileTime = [Int64]638927749230000000
        }
    }
}

function Invoke-UpdateFixture($State) {
    & (Get-Module DevStartup) {
        param($Fixture)
        function Get-DevServiceState([string]$RepoRoot) {
            $Fixture.Events.Add('state')
            $present = ($Fixture.InitialBackendPresent -and -not $Fixture.Stopped) -or ($Fixture.FailurePoint -eq 'PortStillOccupied') -or ($Fixture.Started -and $Fixture.Elapsed -ge 1000)
            [pscustomobject]@{
                TunnelPresent = ($Fixture.InitialTunnelPresent -or $Fixture.StartedTunnel); BackendPresent = $present
                PendingSsh = $false; PendingBackend = $false
                BackendProcess = if ($present) { $Fixture.Backend } else { $null }
            }
        }
        function Test-DevBackendHealth {
            $Fixture.Events.Add('health')
            return ((-not $Fixture.HealthRequiresTunnel -or $Fixture.InitialTunnelPresent -or $Fixture.StartedTunnel) -and $Fixture.Elapsed -ge $Fixture.HealthReadyAt)
        }
        function Assert-DevStartupFiles([string]$RepoRoot) { $Fixture.Events.Add('startup-files') }
        function Test-Path { param($LiteralPath, $PathType) return $true }
        function Assert-DevUpdatePreflight([string]$RepoRoot) {
            $Fixture.Events.Add('update-preflight')
            if ($Fixture.FailurePoint -eq 'Preflight') { throw 'UpdatePreflightFailed' }
        }
        function New-DevBackendBackup([string]$RepoRoot) {
            $Fixture.Events.Add('backup')
            if ($Fixture.FailurePoint -eq 'Backup') { throw 'BackendBackupFailed' }
            return $Fixture.BackupPath
        }
        function Stop-DevBackend($Process, [string]$RepoRoot, [int]$TimeoutSeconds) {
            $Fixture.Events.Add('stop')
            if ($Fixture.FailurePoint -eq 'OwnerMutation') { throw 'BackendIdentityChanged' }
            if ($Fixture.FailurePoint -eq 'CreationMutation') { throw 'BackendIdentityChanged' }
            if ($Fixture.FailurePoint -eq 'Terminate') { throw 'BackendTerminationFailed' }
            if ($Fixture.FailurePoint -eq 'StopTimeout') { throw 'BackendStopTimeout' }
            $Fixture.Stopped = $true
        }
        function Invoke-DevBackendPackage([string]$RepoRoot) {
            $Fixture.Events.Add('build')
            if ($Fixture.FailurePoint -eq 'Build') { throw 'BackendPackageFailed' }
        }
        function Assert-DevPackagedBackend([string]$RepoRoot) {
            $Fixture.Events.Add('artifact')
            if ($Fixture.FailurePoint -eq 'Artifact') { throw 'PackagedBackendRejected' }
        }
        function Start-DevConsole([string]$RepoRoot, [string]$Kind) {
            $Fixture.Events.Add('launch-' + $Kind)
            $Fixture.Launches.Add($Kind)
            if ($Kind -eq 'ssh') { $Fixture.StartedTunnel = $true } else { $Fixture.Started = $true }
        }
        function Get-Date { [datetime]::new(2026, 9, 6, 0, 0, 0, [DateTimeKind]::Utc).AddMilliseconds($Fixture.Elapsed) }
        function Start-Sleep { param($Milliseconds) $Fixture.Elapsed += $Milliseconds }
        function Write-Host { param($Object) }
        try {
            $Fixture.Result = Invoke-DevStartup -RepoRoot $Fixture.Root -UpdateBackend:$Fixture.Update -TunnelTimeoutSeconds 3 -BackendTimeoutSeconds 3 -StopTimeoutSeconds 2
        } catch { $Fixture.Failure = $_.Exception.Message }
    } $State
    return $State
}

Test-Case 'default startup reuses a healthy backend without backup stop or build' {
    $state = New-UpdateState
    $state.Update = $false
    $null = Invoke-UpdateFixture $state
    Assert-True ($null -eq $state.Failure) ('Default startup failed: ' + $state.Failure)
    Assert-True (($state.Events -join ',') -ceq 'state,health,startup-files,state,health') ('Unexpected default sequence: ' + ($state.Events -join ','))
    Assert-True ($state.Result.Backend -ceq 'Reused' -and $state.Launches.Count -eq 0) 'Default mode did not reuse without mutation.'
}

Test-Case 'update backs up then stops builds validates and relaunches only backend' {
    $state = Invoke-UpdateFixture (New-UpdateState)
    Assert-True ($null -eq $state.Failure) ('Update failed: ' + $state.Failure)
    Assert-True (($state.Events -join ',') -ceq 'state,health,startup-files,update-preflight,backup,state,health,stop,state,build,artifact,state,launch-backend,state,state,health') ('Wrong update order: ' + ($state.Events -join ','))
    Assert-True ($state.Result.Backend -ceq 'Updated' -and $state.Result.Tunnel -ceq 'Reused') 'Update status was not reported.'
    Assert-True ($state.Result.BackupPath -ceq $state.BackupPath -and ($state.Launches -join ',') -ceq 'backend') 'Backup path or backend-only relaunch was wrong.'
}

Test-Case 'precheck or backup failure leaves the backend running' {
    foreach ($point in @('Preflight', 'Backup')) {
        $state = New-UpdateState; $state.FailurePoint = $point; $null = Invoke-UpdateFixture $state
        $expected = if ($point -eq 'Preflight') { 'UpdatePreflightFailed' } else { 'BackendBackupFailed' }
        Assert-True ($state.Failure -ceq $expected -and -not $state.Stopped) ($point + ' failure stopped the backend or returned the wrong stage.')
        Assert-True ($state.Launches.Count -eq 0 -and $state.Events -notcontains 'build') ($point + ' failure built or launched.')
    }
}

Test-Case 'identity drift termination failure and stop timeout abort before build' {
    foreach ($point in @('OwnerMutation', 'CreationMutation', 'Terminate', 'StopTimeout')) {
        $state = New-UpdateState; $state.FailurePoint = $point; $null = Invoke-UpdateFixture $state
        $expected = if ($point -eq 'Terminate') { 'BackendTerminationFailed' } elseif ($point -eq 'StopTimeout') { 'BackendStopTimeout' } else { 'BackendIdentityChanged' }
        Assert-True ($state.Failure -ceq $expected -and $state.Events -notcontains 'build') ($point + ' did not abort at the expected stop stage.')
        Assert-True ($state.Launches.Count -eq 0) ($point + ' relaunched a backend.')
    }
}

Test-Case 'port remaining occupied after stop prevents build and launch' {
    $state = New-UpdateState; $state.FailurePoint = 'PortStillOccupied'; $null = Invoke-UpdateFixture $state
    Assert-True ($state.Failure -ceq 'BackendPortReleaseFailed') 'Occupied port was not rejected.'
    Assert-True ($state.Elapsed -eq 2000) 'Occupied port release wait was not bounded by the stop timeout.'
    Assert-True ($state.Events -notcontains 'build' -and $state.Launches.Count -eq 0) 'Occupied port reached build or launch.'
}

Test-Case 'build or packaged artifact failure never relaunches stale backend and retains backup' {
    foreach ($point in @('Build', 'Artifact')) {
        $state = New-UpdateState; $state.FailurePoint = $point; $null = Invoke-UpdateFixture $state
        $expected = if ($point -eq 'Build') { 'BackendPackageFailed' } else { 'PackagedBackendRejected' }
        Assert-True ($state.Failure -ceq $expected -and $state.Events -contains 'backup') ($point + ' failure occurred at the wrong stage or without retained backup.')
        Assert-True ($state.Launches.Count -eq 0) ($point + ' failure launched a backend.')
    }
}

Test-Case 'SSH is never stopped or relaunched during backend update' {
    $state = Invoke-UpdateFixture (New-UpdateState)
    Assert-True (@($state.Events | Where-Object { $_ -match 'ssh' }).Count -eq 0) 'Update mutated SSH.'
}
Test-Case 'explicit update restores missing SSH and waits for recovery before stopping backend' {
    $state = New-UpdateState
    $state.InitialTunnelPresent = $false
    $state.HealthRequiresTunnel = $true
    $state.HealthReadyAt = 1000
    $null = Invoke-UpdateFixture $state
    Assert-True ($null -eq $state.Failure) ('Update with missing tunnel failed: ' + $state.Failure)
    Assert-True ($state.Events.IndexOf('launch-ssh') -lt $state.Events.IndexOf('health') -and $state.Events.IndexOf('health') -lt $state.Events.IndexOf('stop')) 'Update attempted health or termination before restoring SSH.'
    Assert-True (($state.Launches -join ',') -ceq 'ssh,backend' -and $state.Result.Backend -ceq 'Updated') 'Explicit update did not complete its backend-only replacement.'
}
Test-Case 'explicit update recovery timeout keeps original backend and verified backup' {
    $state = New-UpdateState
    $state.InitialTunnelPresent = $false
    $state.HealthRequiresTunnel = $true
    $state.HealthReadyAt = 90000
    $null = Invoke-UpdateFixture $state
    Assert-True ($state.Failure -ceq 'BackendRecoveryTimeout' -and $state.Elapsed -eq 3000) 'Update recovery timeout was missing or unbounded.'
    Assert-True ($state.Events -contains 'backup' -and $state.Events -notcontains 'stop' -and $state.Events -notcontains 'build' -and ($state.Launches -join ',') -ceq 'ssh') 'Failed recovery stopped/built/relaunched backend.'
}

Test-Case 'cold update preflights and backs up before opening SSH then builds without a stop' {
    $state = New-UpdateState
    $state.InitialBackendPresent = $false
    $state.InitialTunnelPresent = $false
    $null = Invoke-UpdateFixture $state
    Assert-True ($null -eq $state.Failure) ('Cold update failed: ' + $state.Failure)
    Assert-True ($state.Events.IndexOf('update-preflight') -lt $state.Events.IndexOf('launch-ssh')) 'SSH opened before update preflight.'
    Assert-True ($state.Events.IndexOf('backup') -lt $state.Events.IndexOf('launch-ssh')) 'SSH opened before the old JAR backup.'
    Assert-True ($state.Events -notcontains 'stop' -and ($state.Launches -join ',') -ceq 'ssh,backend') 'Cold update stopped a process or launched the wrong consoles.'
    Assert-True ($state.Result.Backend -ceq 'Updated' -and $state.Result.Tunnel -ceq 'Started') 'Cold update returned the wrong status.'
}

Test-Case 'existing encrypted configuration is read and every secure value is disposed during preflight' {
    $state = [pscustomobject]@{ Events = [Collections.Generic.List[string]]::new(); Values = [Collections.Generic.List[Security.SecureString]]::new(); Failure = $null }
    & (Get-Module DevStartup) {
        param($Fixture)
        function New-SecureFixture([char]$Value) { $secure=[Security.SecureString]::new();$secure.AppendChar($Value);$Fixture.Values.Add($secure);return $secure }
        function Get-LocalBackendKeys([string]$RepoRoot) { $Fixture.Events.Add('read-keys'); [pscustomobject]@{ BootstrapKey=New-SecureFixture 'b'; TokenPepper=New-SecureFixture 'p' } }
        function Get-LocalBackendCredentials([string]$RepoRoot) { $Fixture.Events.Add('read-credentials'); [pscustomobject]@{ DatabasePassword=New-SecureFixture 'd'; WeChatAppSecret=New-SecureFixture 'w' } }
        function Initialize-LocalBackendKeys { $Fixture.Events.Add('initialize'); throw 'UnexpectedInitialization' }
        try { Assert-DevExistingConfigurationReadable 'D:\offline fixtures\update repo' }
        catch { $Fixture.Failure = $_.Exception.Message }
    } $state
    Assert-True ($null -eq $state.Failure -and ($state.Events -join ',') -ceq 'read-keys,read-credentials') 'Preflight did not read existing configuration only.'
    foreach ($value in $state.Values) {
        $disposed=$false; try{$copy=$value.Copy();$copy.Dispose()}catch{$disposed=$_.Exception.InnerException -is [ObjectDisposedException]}
        Assert-True $disposed 'A preflight secure value was not disposed.'
    }
}

Test-Case 'verified backup is durable outside target and preserves literal bytes' {
    $root = Join-Path ([IO.Path]::GetTempPath()) ('mtb-update-backup-' + [guid]::NewGuid().ToString('N'))
    try {
        $target = Join-Path $root 'account-book-server\target'
        $null = New-Item -ItemType Directory -Force -Path $target
        $bytes = [byte[]](0, 1, 2, 3, 254, 255)
        [IO.File]::WriteAllBytes((Join-Path $target 'account-book-server-0.0.1-SNAPSHOT.jar'), $bytes)
        $backup = & (Get-Module DevStartup) { param($RepoRoot) New-DevBackendBackup $RepoRoot } $root
        $expectedDirectory = [IO.Path]::GetFullPath((Join-Path $root 'backup\local-backend'))
        Assert-True ([IO.Path]::GetDirectoryName($backup) -ceq $expectedDirectory) 'Backup was placed under disposable Maven target output.'
        Assert-True ([Convert]::ToBase64String([IO.File]::ReadAllBytes($backup)) -ceq 'AAECA/7/') 'Backup bytes changed.'
    } finally {
        $full = [IO.Path]::GetFullPath($root); $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        if ($full.StartsWith($temp, [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $full)) { Remove-Item -LiteralPath $full -Recurse -Force }
    }
}

Test-Case 'offline Maven child has fixed package command and no DB test credentials' {
    foreach ($name in @('DB_TEST_URL', 'DB_TEST_USERNAME', 'DB_TEST_PASSWORD', 'DB_TEST_RESET_ALLOWED')) { [Environment]::SetEnvironmentVariable($name, 'must-not-leak', 'Process') }
    try {
        $info = & (Get-Module DevStartup) { param($Root) New-DevMavenStartInfo $Root } 'D:\offline fixtures\update repo'
        Assert-True ($info.FileName -ceq (Join-Path $env:SystemRoot 'System32/cmd.exe')) 'Build shell was not fixed.'
        Assert-True ($info.Arguments -ceq '/d /s /c ""D:\offline fixtures\update repo\account-book-server\mvnw.cmd" -o --no-transfer-progress package"') 'Build command was not the exact offline package phase.'
        Assert-True ($info.WorkingDirectory -ceq 'D:\offline fixtures\update repo\account-book-server') 'Build working directory changed.'
        foreach ($name in @('DB_TEST_URL', 'DB_TEST_USERNAME', 'DB_TEST_PASSWORD', 'DB_TEST_RESET_ALLOWED')) {
            Assert-True (-not $info.EnvironmentVariables.ContainsKey($name)) ($name + ' leaked into Maven.')
        }
    } finally {
        foreach ($name in @('DB_TEST_URL', 'DB_TEST_USERNAME', 'DB_TEST_PASSWORD', 'DB_TEST_RESET_ALLOWED')) { [Environment]::SetEnvironmentVariable($name, $null, 'Process') }
    }
}

Test-Case 'real backend stop revalidates through the native module without losing validator scope' {
    $state = New-UpdateState
    $state.Events.Clear()
    $nativeModule = @((Get-Module DevStartup).NestedModules | Where-Object { $_.Name -ceq 'NativeProcess' })[0]
    & $nativeModule {
        param($Fixture, $DevModule)
        function Open-NativeProcessHandle([int]$ProcessId, [bool]$Terminate) { $Fixture.Events.Add('native-open'); return [IntPtr]123 }
        function Read-NativeProcessHandle([IntPtr]$Handle, [int]$ProcessId) { $Fixture.Events.Add('native-read'); return $Fixture.Backend }
        function Terminate-NativeProcessHandle([IntPtr]$Handle) { $Fixture.Events.Add('native-terminate') }
        function Wait-NativeProcessHandle([IntPtr]$Handle, [int]$Milliseconds) { $Fixture.Events.Add('native-wait'); return $true }
        function Close-NativeProcessHandle([IntPtr]$Handle) { $Fixture.Events.Add('native-close') }
        try { & $DevModule { param($Value) Stop-DevBackend -Process $Value.Backend -RepoRoot $Value.Root -TimeoutSeconds 2 } $Fixture }
        catch { $Fixture.Failure = $_.Exception.Message }
    } $state (Get-Module DevStartup)
    Assert-True ($null -eq $state.Failure) ('Cross-module validator failed: ' + $state.Failure)
    Assert-True (($state.Events -join ',') -ceq 'native-open,native-read,native-terminate,native-wait,native-close') ('Unexpected exact-stop sequence: ' + ($state.Events -join ','))
}

Write-Host ('RESULT passed=' + $script:passed + ' failed=' + $script:failed)
if ($script:failed -gt 0) { exit 1 }
