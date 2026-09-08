[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$modulePath = Join-Path (Split-Path $PSScriptRoot -Parent) 'DevStartup.psm1'
$available = Test-Path -LiteralPath $modulePath
if ($available) { Import-Module $modulePath -Force -DisableNameChecking }
$script:passed = 0
$script:failed = 0

function Assert-True($Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Test-Case([string]$Name, [scriptblock]$Body) {
    try {
        Assert-True $available 'One-command startup state machine is not implemented.'
        & $Body
        $script:passed++
        Write-Host ('PASS ' + $Name)
    } catch {
        $script:failed++
        Write-Host ('FAIL ' + $Name + ': ' + $_.Exception.Message)
    }
}
function New-Listener([int]$Port, [int]$OwnerId, [string]$Address = '127.0.0.1') {
    [pscustomobject]@{ LocalAddress = $Address; LocalPort = $Port; OwningProcess = $OwnerId; State = 'Listen' }
}
function New-State {
    $root = 'D:\offline fixtures\owner''s $book'
    $ssh = Join-Path $env:SystemRoot 'System32/OpenSSH/ssh.exe'
    $java = 'D:/Work/Config/JDK/JDK/jdk21/bin/java.exe'
    [pscustomobject]@{
        Root = $root
        Ssh = [pscustomobject]@{ Name = 'ssh.exe'; ProcessId = 1001; ExecutablePath = $ssh; CommandLine = ('"' + $ssh + '" -N -T -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -L 127.0.0.1:13306:127.0.0.1:3306 root@117.72.101.42') }
        Java = [pscustomobject]@{ Name = 'java.exe'; ProcessId = 1002; ExecutablePath = $java; CommandLine = ('"' + $java + '" -jar "' + (Join-Path $root 'account-book-server/target/account-book-server-0.0.1-SNAPSHOT.jar') + '"'); CreationTimeFileTime = 638927749231234567L }
        InitialListeners = @(); InitialProcesses = @()
        Elapsed = 0; TunnelReadyAt = 1000; BackendReadyAt = 2000
        StartedSsh = $false; StartedBackend = $false
        HealthCode = 200; HealthContent = '{"status":"UP"}'; HealthThrows = $false
        HealthRequiresTunnel = $false; HealthReadyAt = 0
        TunnelLostAt = [int]::MaxValue; BackendLostAt = [int]::MaxValue
        MissingPath = ''; Failure = $null; Result = $null
        Launches = [Collections.Generic.List[object]]::new()
        Events = [Collections.Generic.List[string]]::new()
        Messages = [Collections.Generic.List[string]]::new()
    }
}
function Invoke-Fixture($State) {
    # OS-only doubles: ports, process metadata, HTTP, clock/sleep, file existence,
    # and child console launch. The production state machine and validators run.
    & (Get-Module DevStartup) {
        param($State)
        function Get-NetTCPConnection {
            param($State, $ErrorAction)
            $fixture = $testState
            $fixture.Events.Add('listeners')
            $fixture.InitialListeners | Where-Object { $_.LocalPort -ne 7631 -or $fixture.Elapsed -lt $fixture.BackendLostAt }
            if ($fixture.StartedSsh -and $fixture.Elapsed -ge $fixture.TunnelReadyAt -and $fixture.Elapsed -lt $fixture.TunnelLostAt) {
                [pscustomobject]@{ LocalAddress = '127.0.0.1'; LocalPort = 13306; OwningProcess = 1001; State = 'Listen' }
            }
            if ($fixture.StartedBackend -and $fixture.Elapsed -ge $fixture.BackendReadyAt) {
                [pscustomobject]@{ LocalAddress = '127.0.0.1'; LocalPort = 7631; OwningProcess = 1002; State = 'Listen' }
            }
        }
        function Get-CimInstance {
            param($ClassName, $Filter, $ErrorAction)
            $testState.InitialProcesses
            if ($testState.StartedSsh) { $testState.Ssh }
            if ($testState.StartedBackend) { $testState.Java }
        }
        function Invoke-WebRequest {
            param($Uri, $TimeoutSec, [switch]$UseBasicParsing, $MaximumRedirection, $ErrorAction)
            if ($Uri -cne 'http://127.0.0.1:7631/actuator/health' -or $TimeoutSec -gt 3 -or $MaximumRedirection -ne 0) { throw 'UnboundedOrWrongHealthRequest' }
            $testState.Events.Add('health')
            if ($testState.HealthThrows) { throw 'SyntheticHttpFailure' }
            $tunnelReady = @($testState.InitialListeners | Where-Object LocalPort -eq 13306).Count -gt 0 -or ($testState.StartedSsh -and $testState.Elapsed -ge $testState.TunnelReadyAt -and $testState.Elapsed -lt $testState.TunnelLostAt)
            if (($testState.HealthRequiresTunnel -and -not $tunnelReady) -or $testState.Elapsed -lt $testState.HealthReadyAt) {
                return [pscustomobject]@{ StatusCode = 503; Content = '{"status":"DOWN"}' }
            }
            [pscustomobject]@{ StatusCode = $testState.HealthCode; Content = $testState.HealthContent }
        }
        function Test-Path {
            param($LiteralPath, $PathType)
            return (-not $testState.MissingPath -or -not $LiteralPath.EndsWith($testState.MissingPath))
        }
        function Get-Date { return [datetime]::new(2026, 9, 6, 0, 0, 0, [DateTimeKind]::Utc).AddMilliseconds($testState.Elapsed) }
        function Start-Sleep { param($Milliseconds) $testState.Elapsed += $Milliseconds }
        function Start-Process {
            param($FilePath, $ArgumentList, $WorkingDirectory, $WindowStyle, [switch]$PassThru)
            $encoded = ($ArgumentList -join ' ') -split ' '
            $command = [Text.Encoding]::Unicode.GetString([Convert]::FromBase64String($encoded[-1]))
            $tokens = $null; $parseErrors = $null
            $ast = [Management.Automation.Language.Parser]::ParseInput($command, [ref]$tokens, [ref]$parseErrors)
            if ($parseErrors.Count -ne 0) { throw 'ChildCommandDoesNotParse' }
            $calls = @($ast.FindAll({ param($node) $node -is [Management.Automation.Language.CommandAst] -and $node.InvocationOperator -eq 'Ampersand' }, $true))
            if ($calls.Count -ne 1) { throw 'UnexpectedChildCommandCount' }
            $values = @($calls[0].CommandElements | ForEach-Object {
                if ($_ -is [Management.Automation.Language.StringConstantExpressionAst]) { $_.Value }
                elseif ($_ -is [Management.Automation.Language.CommandParameterAst]) { '-' + $_.ParameterName }
                else { throw 'UnexpectedExpandableLaunchArgument' }
            })
            $kind = if ($values[0].EndsWith('ssh.exe')) { 'ssh' } else { 'backend' }
            $testState.Events.Add('launch-' + $kind)
            $testState.Launches.Add([pscustomobject]@{ Kind = $kind; FilePath = $FilePath; Arguments = $ArgumentList; Values = $values; WorkingDirectory = $WorkingDirectory; WindowStyle = $WindowStyle })
            if ($kind -eq 'ssh') { $testState.StartedSsh = $true } else { $testState.StartedBackend = $true }
        }
        function Write-Host { param($Object) $testState.Messages.Add([string]$Object) }
        $testState = $State
        try { $State.Result = Invoke-DevStartup -RepoRoot $State.Root -TunnelTimeoutSeconds 3 -BackendTimeoutSeconds 3 }
        catch { $State.Failure = $_.Exception.Message }
    } $State
    return $State
}
function Assert-Failure($State, [string]$Category) {
    Assert-True ($State.Failure -ceq $Category) ('Expected ' + $Category + ', got ' + $State.Failure)
}

# Break caught: reversing startup order or launching backend before SSH is ready.
Test-Case 'cold startup opens SSH then backend only after tunnel readiness' {
    $state = Invoke-Fixture (New-State)
    Assert-True ($null -eq $state.Failure) ('Cold startup rejected: ' + $state.Failure)
    Assert-True (($state.Launches.Kind -join ',') -ceq 'ssh,backend') 'Launch order was not SSH then backend.'
    Assert-True ($state.Elapsed -ge 2000 -and $state.Result.Backend -ceq 'Started' -and $state.Result.Tunnel -ceq 'Started') 'Startup did not wait for both services.'
}
Test-Case 'known healthy services are reused without a child launch' {
    $state = New-State
    $state.InitialListeners = @((New-Listener 13306 1001), (New-Listener 7631 1002))
    $state.InitialProcesses = @($state.Ssh, $state.Java)
    $null = Invoke-Fixture $state
    Assert-True ($null -eq $state.Failure -and $state.Launches.Count -eq 0) 'Existing services were relaunched or rejected.'
    Assert-True ($state.Result.Tunnel -ceq 'Reused' -and $state.Result.Backend -ceq 'Reused') 'Reuse was not reported.'
}
Test-Case 'UTF8 byte array health payload can prove existing backend is UP' {
    $state = New-State
    $state.InitialListeners = @((New-Listener 13306 1001), (New-Listener 7631 1002))
    $state.InitialProcesses = @($state.Ssh, $state.Java)
    $state.HealthContent = [Text.Encoding]::UTF8.GetBytes('{"status":"UP"}')
    $null = Invoke-Fixture $state
    Assert-True ($null -eq $state.Failure -and $state.Launches.Count -eq 0) ('UTF8 health response was not accepted: ' + $state.Failure)
    Assert-True ($state.Result.Backend -ceq 'Reused') 'Healthy byte response was not reused.'
}
Test-Case 'malformed byte array health payload cannot prove health' {
    $state = New-State
    $state.InitialListeners = @((New-Listener 13306 1001), (New-Listener 7631 1002))
    $state.InitialProcesses = @($state.Ssh, $state.Java)
    $state.HealthContent = [byte[]](255, 254, 0, 123)
    $null = Invoke-Fixture $state
    Assert-Failure $state 'ExistingBackendUnhealthy'
    Assert-True ($state.Launches.Count -eq 0) 'Malformed bytes were accepted as health.'
}
Test-Case 'unknown backend executable is rejected before opening SSH' {
    $state = New-State
    $state.InitialListeners = @((New-Listener 7631 1002))
    $state.Java.ExecutablePath = 'D:\foreign\java.exe'
    $state.InitialProcesses = @($state.Java)
    $null = Invoke-Fixture $state
    Assert-Failure $state 'BackendOwnerRejected'
    Assert-True ($state.Launches.Count -eq 0) 'Foreign backend caused a launch.'
}
Test-Case 'same Java with another jar or added options is not treated as our backend' {
    foreach ($command in @('"D:/Work/Config/JDK/JDK/jdk21/bin/java.exe" -jar "D:\other\backend.jar"', '"D:/Work/Config/JDK/JDK/jdk21/bin/java.exe" -jar "D:\offline fixtures\owner''s $book\account-book-server\target\account-book-server-0.0.1-SNAPSHOT.jar" --server.port=7631')) {
        $state = New-State
        $state.InitialListeners = @((New-Listener 7631 1002))
        $state.Java.CommandLine = $command
        $state.InitialProcesses = @($state.Java)
        $null = Invoke-Fixture $state
        Assert-Failure $state 'BackendOwnerRejected'
        Assert-True ($state.Launches.Count -eq 0) 'Unrecognized Java command caused a launch.'
    }
}
Test-Case 'relative JAR command cannot impersonate this repository from an unknown process cwd' {
    $state = New-State
    $state.Root = (Get-Location).Path
    $state.Java.CommandLine = '"D:/Work/Config/JDK/JDK/jdk21/bin/java.exe" -jar "account-book-server/target/account-book-server-0.0.1-SNAPSHOT.jar"'
    $state.InitialListeners = @((New-Listener 7631 1002))
    $state.InitialProcesses = @($state.Java)
    $null = Invoke-Fixture $state
    Assert-Failure $state 'BackendOwnerRejected'
    Assert-True ($state.Launches.Count -eq 0) 'Relative process path was resolved using the wrapper cwd.'
}
Test-Case 'drive-relative and drive-unspecified JAR commands are also rejected' {
    foreach ($style in @('driveRelative', 'unspecifiedDrive')) {
        $state = New-State
        $state.Root = (Get-Location).Path
        $jar = if ($style -eq 'driveRelative') { $state.Root.Substring(0, 2) + 'account-book-server/target/account-book-server-0.0.1-SNAPSHOT.jar' }
            else { (Join-Path $state.Root 'account-book-server/target/account-book-server-0.0.1-SNAPSHOT.jar').Substring(2) }
        $state.Java.CommandLine = '"D:/Work/Config/JDK/JDK/jdk21/bin/java.exe" -jar "' + $jar + '"'
        $state.InitialListeners = @((New-Listener 7631 1002))
        $state.InitialProcesses = @($state.Java)
        $null = Invoke-Fixture $state
        Assert-Failure $state 'BackendOwnerRejected'
        Assert-True ($state.Launches.Count -eq 0) 'Partially rooted command was treated as absolute.'
    }
}
Test-Case 'all-address and IPv6 occupancy on either port fail closed' {
    foreach ($port in @(13306, 7631)) {
        foreach ($address in @('0.0.0.0', '::', '::1', '192.168.1.20')) {
            $state = New-State
            $ownerId = if ($port -eq 13306) { 1001 } else { 1002 }
            $state.InitialListeners = @((New-Listener $port $ownerId $address))
            $state.InitialProcesses = @($state.Ssh, $state.Java)
            $null = Invoke-Fixture $state
            $category = if ($port -eq 13306) { 'TunnelPortRejected' } else { 'BackendPortRejected' }
            Assert-Failure $state $category
            Assert-True ($state.Launches.Count -eq 0) 'Non-loopback occupancy caused a launch.'
        }
    }
}
Test-Case 'unknown SSH owner and wrong remote target are rejected' {
    foreach ($case in @('owner', 'target')) {
        $state = New-State
        $state.InitialListeners = @((New-Listener 13306 1001))
        if ($case -eq 'owner') { $state.Ssh.ExecutablePath = 'D:\foreign\ssh.exe' }
        else { $state.Ssh.CommandLine = $state.Ssh.CommandLine.Replace('117.72.101.42', '117.72.101.43') }
        $state.InitialProcesses = @($state.Ssh)
        $null = Invoke-Fixture $state
        Assert-Failure $state 'TunnelPortRejected'
        Assert-True ($state.Launches.Count -eq 0) 'Unknown tunnel caused a launch.'
    }
}
Test-Case 'SSH password timeout is bounded and never launches backend' {
    $state = New-State
    $state.TunnelReadyAt = 90000
    $null = Invoke-Fixture $state
    Assert-Failure $state 'TunnelReadinessTimeout'
    Assert-True ($state.Elapsed -eq 3000 -and $state.Launches.Count -eq 1 -and $state.Launches[0].Kind -ceq 'ssh') 'Timeout launched backend, retried SSH, or waited unboundedly.'
}
Test-Case 'existing unhealthy backend with a tunnel is never restarted' {
    foreach ($case in @('down', 'badJson', 'code', 'network')) {
        $state = New-State
        $state.InitialListeners = @((New-Listener 13306 1001), (New-Listener 7631 1002))
        $state.InitialProcesses = @($state.Ssh, $state.Java)
        switch ($case) {
            'down' { $state.HealthContent = '{"status":"DOWN"}' }
            'badJson' { $state.HealthContent = 'not json' }
            'code' { $state.HealthCode = 503 }
            'network' { $state.HealthThrows = $true }
        }
        $null = Invoke-Fixture $state
        Assert-Failure $state 'ExistingBackendUnhealthy'
        Assert-True ($state.Launches.Count -eq 0 -and $state.Elapsed -eq 0) 'Unhealthy existing backend was restarted or retried.'
    }
}
# Break caught: pre-SSH health checks prevent restoration, or immediate post-SSH
# health checks reject a still-recovering connection pool.
Test-Case 'missing SSH is restored before existing backend health and delayed recovery is reused' {
    $state = New-State
    $state.InitialListeners = @((New-Listener 7631 1002))
    $state.InitialProcesses = @($state.Java)
    $state.HealthRequiresTunnel = $true
    $state.HealthReadyAt = 3000
    $null = Invoke-Fixture $state
    Assert-True ($null -eq $state.Failure) ('Recovery failed: ' + $state.Failure)
    Assert-True (($state.Launches.Kind -join ',') -ceq 'ssh') 'Recovery relaunched backend or duplicated SSH.'
    Assert-True ($state.Events.IndexOf('launch-ssh') -lt $state.Events.IndexOf('health')) 'Health was checked before restoring SSH.'
    Assert-True ($state.Elapsed -eq 3000 -and $state.Result.Backend -ceq 'Reused' -and $state.Result.Tunnel -ceq 'Started') 'Recovery did not wait and reuse the existing backend.'
}
Test-Case 'unrecoverable existing backend after SSH restoration times out without restarting' {
    $state = New-State
    $state.InitialListeners = @((New-Listener 7631 1002))
    $state.InitialProcesses = @($state.Java)
    $state.HealthRequiresTunnel = $true
    $state.HealthReadyAt = 90000
    $null = Invoke-Fixture $state
    Assert-Failure $state 'BackendRecoveryTimeout'
    Assert-True ($state.Elapsed -eq 4000 -and ($state.Launches.Kind -join ',') -ceq 'ssh') 'Recovery waited unboundedly or restarted a service.'
}
Test-Case 'password timeout with existing backend does not attempt health or restart' {
    $state = New-State
    $state.InitialListeners = @((New-Listener 7631 1002))
    $state.InitialProcesses = @($state.Java)
    $state.HealthRequiresTunnel = $true
    $state.TunnelReadyAt = 90000
    $null = Invoke-Fixture $state
    Assert-Failure $state 'TunnelReadinessTimeout'
    Assert-True ($state.Elapsed -eq 3000 -and ($state.Launches.Kind -join ',') -ceq 'ssh' -and $state.Events -notcontains 'health') 'Missing tunnel caused premature health validation or a duplicate launch.'
}
Test-Case 'loss of restored tunnel aborts backend recovery without relaunch' {
    $state = New-State
    $state.InitialListeners = @((New-Listener 7631 1002))
    $state.InitialProcesses = @($state.Java)
    $state.HealthRequiresTunnel = $true
    $state.HealthReadyAt = 90000
    $state.TunnelLostAt = 2000
    $null = Invoke-Fixture $state
    Assert-Failure $state 'TunnelReadinessLost'
    Assert-True ($state.Elapsed -eq 2000 -and ($state.Launches.Kind -join ',') -ceq 'ssh') 'Lost tunnel was ignored or restarted.'
}
Test-Case 'existing backend disappearing during recovery is not silently replaced' {
    $state = New-State
    $state.InitialListeners = @((New-Listener 7631 1002))
    $state.InitialProcesses = @($state.Java)
    $state.HealthRequiresTunnel = $true
    $state.HealthReadyAt = 90000
    $state.BackendLostAt = 2000
    $null = Invoke-Fixture $state
    Assert-Failure $state 'ExistingBackendUnhealthy'
    Assert-True ($state.Elapsed -eq 2000 -and ($state.Launches.Kind -join ',') -ceq 'ssh') 'Vanished backend was relaunched during recovery.'
}
Test-Case 'missing existing credentials or keys fails before launching anything' {
    foreach ($missing in @('keys.dpapi', 'credentials.dpapi')) {
        $state = New-State
        $state.MissingPath = $missing
        $null = Invoke-Fixture $state
        Assert-Failure $state 'ExistingConfigurationMissing'
        Assert-True ($state.Launches.Count -eq 0) 'Missing saved configuration was not a prelaunch failure.'
    }
}
Test-Case 'known SSH already awaiting authentication does not get duplicated' {
    $state = New-State
    $state.InitialProcesses = @($state.Ssh)
    $null = Invoke-Fixture $state
    Assert-Failure $state 'TunnelAuthenticationPending'
    Assert-True ($state.Launches.Count -eq 0) 'Pending SSH process was duplicated.'
}
Test-Case 'known Java already starting without a listener never gets duplicated' {
    foreach ($hasTunnel in @($false, $true)) {
        $state = New-State
        $state.InitialProcesses = @($state.Java)
        if ($hasTunnel) {
            $state.InitialListeners = @((New-Listener 13306 1001))
            $state.InitialProcesses += $state.Ssh
        }
        $null = Invoke-Fixture $state
        Assert-Failure $state 'BackendStartupPending'
        Assert-True ($state.Launches.Count -eq 0) 'Pending Java caused another service console to open.'
    }
}
Test-Case 'another wrapper cannot open a console while the first waits for password input' {
    $state = New-State
    $signals = [hashtable]::Synchronized(@{
        Waiting = [Threading.ManualResetEvent]::new($false)
        Release = [Threading.ManualResetEvent]::new($false)
        Failure = $null
    })
    $firstWrapper = [Management.Automation.PowerShell]::Create()
    $pending = $null
    $harness = @'
param($ModulePath, $Root, $Signals)
Import-Module $ModulePath -Force -DisableNameChecking
& (Get-Module DevStartup) {
    param($Root, $Signals)
    function Get-NetTCPConnection { param($State, $ErrorAction) }
    function Get-CimInstance { param($ClassName, $Filter, $ErrorAction) }
    function Test-Path { param($LiteralPath, $PathType) return $true }
    function Start-Process {
        param($FilePath, $ArgumentList, $WorkingDirectory, $WindowStyle)
        $null = $Signals.Waiting.Set()
    }
    function Start-Sleep {
        param($Milliseconds)
        $null = $Signals.Release.WaitOne(5000)
        throw 'OfflineEndPasswordWait'
    }
    function Invoke-WebRequest { throw 'OfflineUnexpectedHealthRequest' }
    function Write-Host { param($Object) }
    try { $null = Invoke-DevStartup -RepoRoot $Root -TunnelTimeoutSeconds 3 -BackendTimeoutSeconds 3 }
    catch { $Signals.Failure = $_.Exception.Message }
} $Root $Signals
'@
    try {
        $null = $firstWrapper.AddScript($harness).AddArgument($modulePath).AddArgument($state.Root).AddArgument($signals)
        $pending = $firstWrapper.BeginInvoke()
        Assert-True ($signals.Waiting.WaitOne(5000)) 'First offline wrapper never reached the password wait.'
        $null = Invoke-Fixture $state
        Assert-Failure $state 'StartupAlreadyInProgress'
        Assert-True ($state.Launches.Count -eq 0 -and $state.Events.Count -eq 0) 'Concurrent wrapper inspected or launched services despite the lock.'
    } finally {
        $null = $signals.Release.Set()
        if ($null -ne $pending) { $null = $firstWrapper.EndInvoke($pending) }
        $firstWrapper.Dispose()
        $signals.Waiting.Dispose()
        $signals.Release.Dispose()
    }
    Assert-True ($signals.Failure -ceq 'OfflineEndPasswordWait') 'The first wrapper did not end at the controlled offline wait.'
    $state = Invoke-Fixture (New-State)
    Assert-True ($null -eq $state.Failure) 'The startup mutex was not released after the first wrapper exited.'
}
Test-Case 'new backend readiness timeout preserves both child consoles without relaunch' {
    $state = New-State
    $state.BackendReadyAt = 90000
    $null = Invoke-Fixture $state
    Assert-Failure $state 'BackendReadinessTimeout'
    Assert-True ($state.Launches.Count -eq 2 -and $state.Elapsed -eq 4000) 'Backend failure caused retry or unbounded wait.'
}
Test-Case 'encoded retained-console payload keeps exact bounded SSH and literal backend path' {
    $state = Invoke-Fixture (New-State)
    Assert-True ($null -eq $state.Failure -and $state.Launches.Count -eq 2) 'No complete launch payload available.'
    $shell = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
    foreach ($launch in $state.Launches) {
        Assert-True ($launch.FilePath -ceq $shell -and $launch.WorkingDirectory -ceq $state.Root -and $launch.WindowStyle -ceq 'Normal') 'Wrong shell, cwd or visibility.'
        Assert-True (($launch.Arguments -join ' ') -match '^-NoProfile -NoExit -ExecutionPolicy Bypass -EncodedCommand [A-Za-z0-9+/=]+$') 'Console is not retained or encoded safely.'
    }
    $ssh = $state.Launches[0].Values
    Assert-True ($ssh[0] -ceq (Join-Path $env:SystemRoot 'System32/OpenSSH/ssh.exe')) 'SSH executable is not fixed Windows OpenSSH.'
    Assert-True (($ssh[1..($ssh.Count - 1)] -join ' ') -ceq '-N -T -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -L 127.0.0.1:13306:127.0.0.1:3306 root@117.72.101.42') 'SSH forwards or options changed.'
    $backend = $state.Launches[1].Values
    Assert-True ($backend[0] -ceq $shell -and $backend[1] -ceq '-NoProfile' -and $backend[2] -ceq '-ExecutionPolicy' -and $backend[3] -ceq 'Bypass' -and $backend[4] -ceq '-File') 'Backend does not invoke existing script in retained outer console.'
    Assert-True ($backend[5] -ceq (Join-Path $state.Root 'deploy/scripts/Start-LocalBackend.ps1') -and $backend[6] -ceq '-ExistingConfigurationOnly') 'Quoted path changed or preserve-only switch is missing.'
}

Write-Host ('RESULT passed=' + $script:passed + ' failed=' + $script:failed)
if ($script:failed -gt 0) { exit 1 }
