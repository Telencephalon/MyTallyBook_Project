Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'LocalBackend.psm1') -DisableNameChecking -ErrorAction Stop
Import-Module (Join-Path $PSScriptRoot 'NativeProcess.psm1') -Force -DisableNameChecking -ErrorAction Stop
Import-Module (Join-Path $PSScriptRoot 'LocalBackendCredentials.psm1') -DisableNameChecking -ErrorAction Stop

function Test-DevBackendOwner($Process, [string]$RepoRoot) {
    $java = [IO.Path]::GetFullPath('D:/Work/Config/JDK/JDK/jdk21/bin/java.exe')
    $jar = [IO.Path]::GetFullPath((Join-Path $RepoRoot 'account-book-server/target/account-book-server-0.0.1-SNAPSHOT.jar'))
    return Test-ExactJavaJarProcess -Process $Process -JavaPath $java -JarPath $jar
}

function Test-DevKnownSsh($Process) {
    try {
        $listener = [pscustomobject]@{ LocalAddress = '127.0.0.1'; LocalPort = 13306; OwningProcess = $Process.ProcessId }
        Assert-LocalBackendListeners -Listeners @($listener) -Processes @($Process)
        return $true
    } catch { return $false }
}

function Get-DevServiceState([string]$RepoRoot) {
    # Do not restrict addresses: wildcard, IPv6, and other interfaces are conflicts.
    $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object { $_.LocalPort -eq 13306 -or $_.LocalPort -eq 7631 })
    $candidates = @(Get-CimInstance Win32_Process -Filter "Name='ssh.exe' OR Name='java.exe'" -ErrorAction Stop)
    $processes = @(foreach ($candidate in $candidates) {
        $metadata = Get-LimitedProcessMetadata -Process $candidate -RequirePreciseCreationTime:($candidate.Name -ieq 'java.exe')
        if ($null -ne $metadata) { $metadata }
    })
    $backend = @($listeners | Where-Object { $_.LocalPort -eq 7631 })
    if ($backend.Count -gt 0) {
        if ($backend.Count -ne 1 -or $backend[0].LocalAddress -ne '127.0.0.1') { throw 'BackendPortRejected' }
        $owners = @($processes | Where-Object { $_.ProcessId -eq $backend[0].OwningProcess })
        if ($owners.Count -ne 1 -or -not (Test-DevBackendOwner $owners[0] $RepoRoot)) { throw 'BackendOwnerRejected' }
    }
    $tunnel = @($listeners | Where-Object { $_.LocalPort -eq 13306 })
    if ($tunnel.Count -gt 0) {
        try { Assert-LocalBackendListeners -Listeners $tunnel -Processes $processes }
        catch { throw 'TunnelPortRejected' }
    }
    $pendingSsh = @($processes | Where-Object { Test-DevKnownSsh $_ })
    $pendingBackend = @($processes | Where-Object { Test-DevBackendOwner $_ $RepoRoot })
    return [pscustomobject]@{
        TunnelPresent = ($tunnel.Count -eq 1); BackendPresent = ($backend.Count -eq 1)
        PendingSsh = ($pendingSsh.Count -gt 0); PendingBackend = ($pendingBackend.Count -gt 0)
        BackendProcess = if ($backend.Count -eq 1) { @($processes | Where-Object { $_.ProcessId -eq $backend[0].OwningProcess })[0] } else { $null }
    }
}

function Test-DevBackendHealth {
    try {
        $response = Invoke-WebRequest -Uri 'http://127.0.0.1:7631/actuator/health' -UseBasicParsing -TimeoutSec 3 -MaximumRedirection 0 -ErrorAction Stop
        if ([int]$response.StatusCode -ne 200) { return $false }
        $content = if ($response.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($response.Content) } else { $response.Content }
        $payload = $content | ConvertFrom-Json -ErrorAction Stop
        if ($null -eq $payload -or $payload -is [array]) { return $false }
        $status = $payload.PSObject.Properties['status']
        return ($null -ne $status -and $status.Value -is [string] -and $status.Value -ceq 'UP')
    } catch { return $false }
}

function Wait-DevBackendRecovery([string]$RepoRoot, [int]$TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ($true) {
        # Revalidate ownership as well as health on every observation. Recovery
        # only waits for the existing connection pool; it never restarts Java.
        $state = Get-DevServiceState $RepoRoot
        if (-not $state.TunnelPresent) { throw 'TunnelReadinessLost' }
        if (-not $state.BackendPresent) { throw 'ExistingBackendUnhealthy' }
        if (Test-DevBackendHealth) { return $state }
        if ((Get-Date) -ge $deadline) { throw 'BackendRecoveryTimeout' }
        Start-Sleep -Milliseconds 1000
    }
}

function Assert-DevStartupFiles([string]$RepoRoot) {
    foreach ($relative in @('secrets/local-dev/keys.dpapi', 'secrets/local-dev/credentials.dpapi')) {
        if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot $relative) -PathType Leaf)) { throw 'ExistingConfigurationMissing' }
    }
    foreach ($path in @(
        'D:/Work/Config/JDK/JDK/jdk21/bin/java.exe',
        (Join-Path $RepoRoot 'account-book-server/target/account-book-server-0.0.1-SNAPSHOT.jar'),
        (Join-Path $RepoRoot 'deploy/scripts/Start-LocalBackend.ps1'),
        (Join-Path $env:SystemRoot 'System32/OpenSSH/ssh.exe'),
        (Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe')
    )) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'StartupDependencyMissing' }
    }
}

function Get-DevFileHash([string]$Path) {
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try {
        $sha = [Security.Cryptography.SHA256]::Create()
        try { return ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
        finally { $sha.Dispose() }
    } finally { $stream.Dispose() }
}

function Assert-DevSafePath([string]$Path, [string]$RepoRoot) {
    $root = [IO.Path]::GetFullPath($RepoRoot).TrimEnd('\')
    $candidate = [IO.Path]::GetFullPath($Path)
    if (-not $candidate.StartsWith(($root + '\'), [StringComparison]::OrdinalIgnoreCase)) { throw 'UpdatePreflightFailed' }
    while ($candidate -and $candidate.Length -ge $root.Length) {
        if (Test-Path -LiteralPath $candidate) {
            $item = Get-Item -LiteralPath $candidate -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'UpdatePreflightFailed' }
        }
        if ($candidate -ieq $root) { break }
        $candidate = Split-Path $candidate -Parent
    }
}

function Assert-DevMavenWrapperCached([string]$RepoRoot) {
    $propertiesPath = Join-Path $RepoRoot 'account-book-server/.mvn/wrapper/maven-wrapper.properties'
    if (-not (Test-Path -LiteralPath $propertiesPath -PathType Leaf)) { throw 'UpdatePreflightFailed' }
    try {
        $properties = Get-Content -LiteralPath $propertiesPath -Raw -Encoding UTF8 | ConvertFrom-StringData
        $url = [string]$properties.distributionUrl
        if ($url -cnotmatch '^https://repo\.maven\.apache\.org/maven2/org/apache/maven/apache-maven/(?<version>[0-9.]+)/apache-maven-\k<version>-bin\.zip$') { throw 'UpdatePreflightFailed' }
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $key = ([BitConverter]::ToString($sha.ComputeHash([byte[]][char[]]$url))).Replace('-', '').ToLowerInvariant() }
        finally { $sha.Dispose() }
        $distribution = 'apache-maven-' + $Matches.version
        $cached = Join-Path $env:USERPROFILE ('.m2/wrapper/dists/' + $distribution + '/' + $key + '/bin/mvn.cmd')
        if (-not (Test-Path -LiteralPath $cached -PathType Leaf)) { throw 'UpdatePreflightFailed' }
    } catch { throw 'UpdatePreflightFailed' }
}

function Assert-DevUpdatePreflight([string]$RepoRoot) {
    Assert-LocalBackendArtifacts -RepoRoot $RepoRoot -RequireJar
    foreach ($relative in @(
        'secrets/local-dev/keys.dpapi',
        'secrets/local-dev/credentials.dpapi',
        'account-book-server/mvnw.cmd',
        'account-book-server/src/main/java/com/mytallybook/accountbook/member/MemberController.java',
        'account-book-server/src/main/java/com/mytallybook/accountbook/invite/InviteController.java'
    )) {
        $path = [IO.Path]::GetFullPath((Join-Path $RepoRoot $relative))
        Assert-DevSafePath -Path $path -RepoRoot $RepoRoot
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'UpdatePreflightFailed' }
    }
    Assert-DevSafePath -Path (Join-Path $RepoRoot 'backup/local-backend') -RepoRoot $RepoRoot
    & git -C $RepoRoot check-ignore --quiet --no-index -- 'backup/local-backend/probe.jar' 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'UpdatePreflightFailed' }
    Assert-DevMavenWrapperCached $RepoRoot
    Assert-DevExistingConfigurationReadable $RepoRoot
}

function Assert-DevExistingConfigurationReadable([string]$RepoRoot) {
    $keys = $null
    $credentials = $null
    try {
        $keys = Get-LocalBackendKeys -RepoRoot $RepoRoot
        $credentials = Get-LocalBackendCredentials -RepoRoot $RepoRoot
    } finally {
        if ($null -ne $credentials) {
            if ($null -ne $credentials.DatabasePassword) { $credentials.DatabasePassword.Dispose() }
            if ($null -ne $credentials.WeChatAppSecret) { $credentials.WeChatAppSecret.Dispose() }
        }
        if ($null -ne $keys) {
            if ($null -ne $keys.BootstrapKey) { $keys.BootstrapKey.Dispose() }
            if ($null -ne $keys.TokenPepper) { $keys.TokenPepper.Dispose() }
        }
    }
}

function New-DevBackendBackup([string]$RepoRoot) {
    try {
        $jar = [IO.Path]::GetFullPath((Join-Path $RepoRoot 'account-book-server/target/account-book-server-0.0.1-SNAPSHOT.jar'))
        $hash = Get-DevFileHash $jar
        $directory = [IO.Path]::GetFullPath((Join-Path $RepoRoot 'backup/local-backend'))
        $null = New-Item -ItemType Directory -Path $directory -Force
        $name = 'account-book-server-' + (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss-fffffff') + '-' + $hash.Substring(0, 12) + '.jar'
        $backup = Join-Path $directory $name
        $source = [IO.File]::Open($jar, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
        try {
            $destination = [IO.File]::Open($backup, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
            try { $source.CopyTo($destination); $destination.Flush($true) }
            finally { $destination.Dispose() }
        } finally { $source.Dispose() }
        if ((Get-DevFileHash $backup) -cne $hash) { throw 'BackendBackupVerificationFailed' }
        return $backup
    } catch {
        if ($_.Exception.Message -ceq 'BackendBackupVerificationFailed') { throw }
        throw 'BackendBackupFailed'
    }
}

function New-DevMavenStartInfo([string]$RepoRoot) {
    $root = [IO.Path]::GetFullPath($RepoRoot)
    if ($root.Contains('"')) { throw 'BuildPathInvalid' }
    $server = Join-Path $root 'account-book-server'
    $wrapper = Join-Path $server 'mvnw.cmd'
    $shell = Join-Path $env:SystemRoot 'System32/cmd.exe'
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $shell
    $info.Arguments = '/d /s /c ""' + $wrapper + '" -o --no-transfer-progress package"'
    $info.WorkingDirectory = $server
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.EnvironmentVariables.Clear()
    foreach ($name in @('SystemRoot', 'WINDIR', 'SystemDrive', 'TEMP', 'TMP', 'USERPROFILE', 'HOMEDRIVE', 'HOMEPATH', 'USERNAME', 'USERDOMAIN', 'LOCALAPPDATA', 'APPDATA', 'ProgramData', 'OS', 'NUMBER_OF_PROCESSORS', 'PROCESSOR_ARCHITECTURE')) {
        $value = [Environment]::GetEnvironmentVariable($name, 'Process')
        if ($null -ne $value) { $info.EnvironmentVariables[$name] = $value }
    }
    $info.EnvironmentVariables['JAVA_HOME'] = [IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName('D:/Work/Config/JDK/JDK/jdk21/bin/java.exe'))
    $info.EnvironmentVariables['PATH'] = (@(
        (Join-Path $env:SystemRoot 'System32'),
        (Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0'),
        'D:\Work\Config\JDK\JDK\jdk21\bin'
    ) -join ';')
    return $info
}

function Invoke-DevBackendPackage([string]$RepoRoot) {
    $process = $null
    try {
        $process = [Diagnostics.Process]::Start((New-DevMavenStartInfo $RepoRoot))
        if ($null -eq $process) { throw 'BackendPackageFailed' }
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw 'BackendPackageFailed' }
    } catch { throw 'BackendPackageFailed' }
    finally { if ($null -ne $process) { $process.Dispose() } }
}

function Assert-DevPackagedBackend([string]$RepoRoot) {
    $jar = Join-Path $RepoRoot 'account-book-server/target/account-book-server-0.0.1-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw 'PackagedBackendRejected' }
    try {
        Assert-LocalBackendMigrations -RepoRoot $RepoRoot -JarPath $jar
        $archive = [IO.Compression.ZipFile]::OpenRead($jar)
        try {
            $names = @($archive.Entries | Select-Object -ExpandProperty FullName)
            foreach ($name in @(
                'BOOT-INF/classes/com/mytallybook/accountbook/member/MemberController.class',
                'BOOT-INF/classes/com/mytallybook/accountbook/invite/InviteController.class'
            )) { if ($names -cnotcontains $name) { throw 'PackagedBackendRejected' } }
        } finally { $archive.Dispose() }
    } catch { throw 'PackagedBackendRejected' }
}

function Stop-DevBackend($Process, [string]$RepoRoot, [int]$TimeoutSeconds) {
    if ($null -eq $Process -or -not (Test-DevBackendOwner $Process $RepoRoot)) { throw 'BackendIdentityChanged' }
    $java = [IO.Path]::GetFullPath('D:/Work/Config/JDK/JDK/jdk21/bin/java.exe')
    $jar = [IO.Path]::GetFullPath((Join-Path $RepoRoot 'account-book-server/target/account-book-server-0.0.1-SNAPSHOT.jar'))
    Stop-ExactNativeProcess -Expected $Process -ExpectedJavaPath $java -ExpectedJarPath $jar -TimeoutSeconds $TimeoutSeconds
}

function Wait-DevBackendReleased([string]$RepoRoot, [int]$TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ($true) {
        try {
            $state = Get-DevServiceState $RepoRoot
            if (-not $state.TunnelPresent) { throw 'TunnelReadinessLost' }
            if (-not $state.BackendPresent -and -not $state.PendingBackend) { return $state }
        } catch {
            if ($_.Exception.Message -cne 'BackendOwnerRejected' -and $_.Exception.Message -cne 'BackendPortRejected') { throw }
        }
        if ((Get-Date) -ge $deadline) { throw 'BackendPortReleaseFailed' }
        Start-Sleep -Milliseconds 200
    }
}

function Start-DevConsole([string]$RepoRoot, [ValidateSet('ssh', 'backend')][string]$Kind) {
    $shell = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
    if ($Kind -eq 'ssh') {
        $ssh = (Join-Path $env:SystemRoot 'System32/OpenSSH/ssh.exe').Replace("'", "''")
        $command = "Write-Host 'MyTallyBook SSH tunnel: enter the server password here. Keep this window open.'; & '$ssh' -N -T -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -L 127.0.0.1:13306:127.0.0.1:3306 root@117.72.101.42; Write-Host 'SSH has exited. No automatic retry was attempted.'"
    } else {
        $backendScript = (Join-Path $RepoRoot 'deploy/scripts/Start-LocalBackend.ps1').Replace("'", "''")
        $literalShell = $shell.Replace("'", "''")
        # An inner PowerShell contains the existing script's exit statement. The
        # outer -NoExit console remains visible when startup fails or Java exits.
        $command = "Write-Host 'MyTallyBook backend: existing encrypted configuration only.'; & '$literalShell' -NoProfile -ExecutionPolicy Bypass -File '$backendScript' -ExistingConfigurationOnly; Write-Host 'Backend launcher has exited. No automatic retry was attempted.'"
    }
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    Start-Process -FilePath $shell -ArgumentList @('-NoProfile', '-NoExit', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $encoded) -WorkingDirectory $RepoRoot -WindowStyle Normal
}

function Invoke-DevStartup {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$RepoRoot,
        [switch]$UpdateBackend,
        [ValidateRange(1, 600)][int]$TunnelTimeoutSeconds = 120,
        [ValidateRange(1, 600)][int]$BackendTimeoutSeconds = 120,
        [ValidateRange(1, 600)][int]$StopTimeoutSeconds = 15
    )
    $root = [IO.Path]::GetFullPath($RepoRoot).TrimEnd('\')
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $key = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($root.ToUpperInvariant())))).Replace('-', '') }
    finally { $sha.Dispose() }
    $mutex = [Threading.Mutex]::new($false, ('Local\MyTallyBook.DevStartup.' + $key))
    $locked = $false
    try {
        try { $locked = $mutex.WaitOne(0) }
        catch [Threading.AbandonedMutexException] { $locked = $true }
        if (-not $locked) { throw 'StartupAlreadyInProgress' }

        $state = Get-DevServiceState $root
        if (-not $state.BackendPresent -and $state.PendingBackend) { throw 'BackendStartupPending' }
        # An absent SSH tunnel makes DB health fail even when Java is healthy.
        # Restore that prerequisite before judging the existing backend.
        if ($state.TunnelPresent -and $state.BackendPresent -and -not (Test-DevBackendHealth)) { throw 'ExistingBackendUnhealthy' }
        $existingBackend = $state.BackendPresent
        Assert-DevStartupFiles $root
        $backupPath = $null
        if ($UpdateBackend) {
            Assert-DevUpdatePreflight $root
            $backupPath = New-DevBackendBackup $root
        }
        try {
        $tunnelResult = 'Reused'
        if (-not $state.TunnelPresent) {
            if ($state.PendingSsh) { throw 'TunnelAuthenticationPending' }
            Write-Host 'Opening SSH password console. Enter the server password there; waiting up to 120 seconds by default for 127.0.0.1:13306.'
            Start-DevConsole $root 'ssh'
            $tunnelResult = 'Started'
            $deadline = (Get-Date).AddSeconds($TunnelTimeoutSeconds)
            while ($true) {
                $state = Get-DevServiceState $root
                if ($state.TunnelPresent) { break }
                if ((Get-Date) -ge $deadline) { throw 'TunnelReadinessTimeout' }
                Start-Sleep -Milliseconds 1000
            }
        } else { Write-Host 'Reusing the verified SSH tunnel at 127.0.0.1:13306.' }

        # Recheck after password entry: nothing is ever stopped to clear a port.
        $state = Get-DevServiceState $root
        if (-not $state.TunnelPresent) { throw 'TunnelReadinessLost' }

        if ($tunnelResult -eq 'Started' -and $state.BackendPresent) {
            Write-Host ('SSH restored. Waiting up to ' + $BackendTimeoutSeconds + ' seconds for the existing backend to reconnect to its database; it will not be restarted during recovery.')
            $state = Wait-DevBackendRecovery -RepoRoot $root -TimeoutSeconds $BackendTimeoutSeconds
        }

        if ($UpdateBackend) {
            if ($state.BackendPresent) {
                if ($null -eq $state.BackendProcess -or -not (Test-DevBackendHealth)) { throw 'ExistingBackendUnhealthy' }
                Stop-DevBackend -Process $state.BackendProcess -RepoRoot $root -TimeoutSeconds $StopTimeoutSeconds
                $state = Wait-DevBackendReleased -RepoRoot $root -TimeoutSeconds $StopTimeoutSeconds
            } elseif ($state.PendingBackend) { throw 'BackendStartupPending' }
            Invoke-DevBackendPackage $root
            Assert-DevPackagedBackend $root
            $state = Get-DevServiceState $root
            if (-not $state.TunnelPresent) { throw 'TunnelReadinessLost' }
            if ($state.BackendPresent -or $state.PendingBackend) { throw 'BackendPortReleaseFailed' }
            Start-DevConsole $root 'backend'
            $deadline = (Get-Date).AddSeconds($BackendTimeoutSeconds)
            while ($true) {
                $state = Get-DevServiceState $root
                if (-not $state.TunnelPresent) { throw 'TunnelReadinessLost' }
                if ($state.BackendPresent -and (Test-DevBackendHealth)) { break }
                if ((Get-Date) -ge $deadline) { throw 'BackendReadinessTimeout' }
                Start-Sleep -Milliseconds 1000
            }
            Write-Host 'Development services ready: SSH 127.0.0.1:13306 ready; backend updated and healthy at http://127.0.0.1:7631.'
            return [pscustomobject]@{ Tunnel = $tunnelResult; Backend = 'Updated'; BackupPath = $backupPath }
        }

        $backendResult = 'Reused'
        if ($state.BackendPresent) {
            if (-not (Test-DevBackendHealth)) { throw 'ExistingBackendUnhealthy' }
            Write-Host 'Reusing the verified healthy backend at 127.0.0.1:7631.'
        } else {
            if ($existingBackend) { throw 'ExistingBackendUnhealthy' }
            if ($state.PendingBackend) { throw 'BackendStartupPending' }
            Write-Host 'Opening backend console using existing encrypted configuration. Waiting for HTTP 200 and status UP.'
            Start-DevConsole $root 'backend'
            $backendResult = 'Started'
            $deadline = (Get-Date).AddSeconds($BackendTimeoutSeconds)
            while ($true) {
                $state = Get-DevServiceState $root
                if (-not $state.TunnelPresent) { throw 'TunnelReadinessLost' }
                if ($state.BackendPresent -and (Test-DevBackendHealth)) { break }
                if ((Get-Date) -ge $deadline) { throw 'BackendReadinessTimeout' }
                Start-Sleep -Milliseconds 1000
            }
        }
        Write-Host 'Development services ready: SSH 127.0.0.1:13306; backend http://127.0.0.1:7631 (health UP).'
        return [pscustomobject]@{ Tunnel = $tunnelResult; Backend = $backendResult }
        } catch {
            if ($UpdateBackend -and $null -ne $backupPath) { $_.Exception.Data['BackendBackupPath'] = $backupPath }
            throw
        }
    } finally {
        if ($locked) { $mutex.ReleaseMutex() }
        $mutex.Dispose()
    }
}

Export-ModuleMember -Function Invoke-DevStartup
