Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module -Name "$PSHOME\Modules\Microsoft.PowerShell.Security\Microsoft.PowerShell.Security.psd1" -ErrorAction Stop
Add-Type -AssemblyName System.Security
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
Import-Module (Join-Path $PSScriptRoot 'NativeProcess.psm1') -Force -DisableNameChecking -ErrorAction Stop

$script:JavaPath = 'D:/Work/Config/JDK/JDK/jdk21/bin/java.exe'
$script:JarRelativePath = 'account-book-server/target/account-book-server-0.0.1-SNAPSHOT.jar'
$script:MigrationHashes = @{
    'V1__init_schema.sql' = '2790a69c5847e92fe6510255491723b750b31796d426884671e695659ac0a769'
    'V2__align_approved_design.sql' = '9a69661dcec91a27b6a631a64d29217ed894c68cc8bb9622795806c82bc49ad8'
}

function Get-OwnedKeyPath([string]$RepoRoot) {
    if (-not (Test-Path -LiteralPath $RepoRoot -PathType Container)) { throw 'StorageRootInvalid' }
    $root = [IO.Path]::GetFullPath($RepoRoot)
    $path = Join-Path $root 'secrets/local-dev/keys.dpapi'
    # Check the complete ancestor chain, including a linked repo or temp parent.
    $candidate = $path
    while ($candidate) {
        if (Test-Path -LiteralPath $candidate) {
            $item = Get-Item -LiteralPath $candidate -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'StorageReparsePointRejected' }
        }
        $parent = Split-Path $candidate -Parent
        if ($parent -eq $candidate) { break }
        $candidate = $parent
    }
    return $path
}

function Set-OwnedDirectoryAcl([string]$Path) {
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $systemSid = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true, $false)
    $acl.SetOwner($sid)
    foreach ($identity in @($sid, $systemSid)) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function Assert-OwnedKeyAcl([string]$Path) {
    $allowed = @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value, 'S-1-5-18')
    foreach ($target in @((Split-Path $Path -Parent), $Path)) {
        $acl = Get-Acl -LiteralPath $target
        if ($allowed -notcontains $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value) { throw 'StorageAclRejected' }
        $rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
        if ($rules.Count -eq 0) { throw 'StorageAclRejected' }
        foreach ($rule in $rules) {
            if ($allowed -notcontains $rule.IdentityReference.Value -or $rule.AccessControlType -ne 'Allow') { throw 'StorageAclRejected' }
        }
    }
}

function Assert-KeyPayload($Payload) {
    $names = @($Payload.PSObject.Properties.Name)
    if ($names.Count -ne 3 -or $names -notcontains 'version' -or $names -notcontains 'bootstrapKey' -or $names -notcontains 'tokenPepper') { throw 'KeyPayloadInvalid' }
    if ($Payload.version -isnot [int] -or $Payload.version -ne 1) { throw 'KeyPayloadInvalid' }
    foreach ($value in @($Payload.bootstrapKey, $Payload.tokenPepper)) {
        if ($value -isnot [string] -or $value -cnotmatch '^[A-Za-z0-9+/]{43}=$') { throw 'KeyPayloadInvalid' }
        $bytes = [Convert]::FromBase64String($value)
        try {
            if ($bytes.Length -ne 32 -or [Convert]::ToBase64String($bytes) -cne $value) { throw 'KeyPayloadInvalid' }
        } finally { [Array]::Clear($bytes, 0, $bytes.Length) }
    }
    if ($Payload.bootstrapKey -ceq $Payload.tokenPepper) { throw 'KeyPayloadInvalid' }
}

function Get-LocalBackendKeys {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$RepoRoot)
    $plainBytes = $null
    $bootstrap = $null
    try {
        $path = Get-OwnedKeyPath $RepoRoot
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'KeyStorageMissing' }
        Assert-OwnedKeyAcl $path
        $cipher = [IO.File]::ReadAllBytes($path)
        $plainBytes = [Security.Cryptography.ProtectedData]::Unprotect($cipher, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        $payload = [Text.Encoding]::UTF8.GetString($plainBytes) | ConvertFrom-Json
        Assert-KeyPayload $payload
        $bootstrap = ConvertTo-SecureString $payload.bootstrapKey -AsPlainText -Force
        $pepper = ConvertTo-SecureString $payload.tokenPepper -AsPlainText -Force
        return [pscustomobject]@{ BootstrapKey = $bootstrap; TokenPepper = $pepper }
    } catch {
        if ($null -ne $bootstrap) { $bootstrap.Dispose() }
        throw 'KeyStorageReadFailed'
    } finally {
        if ($null -ne $plainBytes) { [Array]::Clear($plainBytes, 0, $plainBytes.Length) }
        $payload = $null
    }
}

function Initialize-LocalBackendKeys {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$RepoRoot)
    $path = Get-OwnedKeyPath $RepoRoot
    if (Test-Path -LiteralPath $path) {
        $keys = Get-LocalBackendKeys -RepoRoot $RepoRoot
        $keys.BootstrapKey.Dispose()
        $keys.TokenPepper.Dispose()
        return [pscustomobject]@{ Path = $path; Version = 1; Created = $false }
    }
    $directory = Split-Path $path -Parent
    $null = New-Item -ItemType Directory -Path $directory -Force
    $null = Get-OwnedKeyPath $RepoRoot
    Set-OwnedDirectoryAcl $directory
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    $bootstrapBytes = New-Object byte[] 32
    $pepperBytes = New-Object byte[] 32
    $plainBytes = $null
    try {
        $rng.GetBytes($bootstrapBytes)
        $rng.GetBytes($pepperBytes)
        $payload = [ordered]@{ version = 1; bootstrapKey = [Convert]::ToBase64String($bootstrapBytes); tokenPepper = [Convert]::ToBase64String($pepperBytes) }
        $plainBytes = [Text.Encoding]::UTF8.GetBytes(($payload | ConvertTo-Json -Compress))
        $cipher = [Security.Cryptography.ProtectedData]::Protect($plainBytes, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        $null = Get-OwnedKeyPath $RepoRoot
        # Exclusive create never rotates or overwrites a concurrently created file.
        $stream = [IO.File]::Open($path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try { $stream.Write($cipher, 0, $cipher.Length); $stream.Flush($true) }
        finally { $stream.Dispose() }
        Assert-OwnedKeyAcl $path
        return [pscustomobject]@{ Path = $path; Version = 1; Created = $true }
    } finally {
        $rng.Dispose()
        [Array]::Clear($bootstrapBytes, 0, $bootstrapBytes.Length)
        [Array]::Clear($pepperBytes, 0, $pepperBytes.Length)
        if ($null -ne $plainBytes) { [Array]::Clear($plainBytes, 0, $plainBytes.Length) }
        $payload = $null
    }
}

function Get-LocalBackendAppId {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$RepoRoot)
    try {
        $config = Get-Content -LiteralPath (Join-Path $RepoRoot 'account-book-miniapp/project.config.json') -Raw -Encoding UTF8 | ConvertFrom-Json
        $value = $config.appid
        if ($value -isnot [string] -or $value -cnotmatch '^wx[0-9a-fA-F]{16}$' -or $value -eq 'wx0000000000000000') { throw 'AppIdInvalid' }
        return $value
    } catch { throw 'AppIdInvalid' }
}

function Convert-SecureValue([Security.SecureString]$Value) {
    if ($null -eq $Value -or $Value.Length -eq 0) { throw 'SecretInputEmpty' }
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

function New-LocalBackendStartInfo {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$RepoRoot,
        [Parameter(Mandatory)]$Keys,
        [Parameter(Mandatory)][Security.SecureString]$DatabasePassword,
        [Parameter(Mandatory)][Security.SecureString]$WeChatAppSecret
    )
    $root = [IO.Path]::GetFullPath($RepoRoot)
    if ($root.Contains('"')) { throw 'LaunchPathInvalid' }
    $info = [Diagnostics.ProcessStartInfo]::new()
    try {
        $info.FileName = $script:JavaPath
        $info.Arguments = '-jar "' + (Join-Path $root $script:JarRelativePath) + '"'
        $info.WorkingDirectory = Join-Path $root 'account-book-server'
        $info.UseShellExecute = $false
        $info.CreateNoWindow = $false
        # Do not inherit arbitrary application, Spring, Java-agent or credential vars.
        $info.EnvironmentVariables.Clear()
        foreach ($name in @('SystemRoot', 'WINDIR', 'SystemDrive', 'TEMP', 'TMP', 'USERPROFILE', 'HOMEDRIVE', 'HOMEPATH', 'USERNAME', 'USERDOMAIN', 'LOCALAPPDATA', 'APPDATA', 'ProgramData', 'OS', 'NUMBER_OF_PROCESSORS', 'PROCESSOR_ARCHITECTURE')) {
            $value = [Environment]::GetEnvironmentVariable($name, 'Process')
            if ($null -ne $value) { $info.EnvironmentVariables[$name] = $value }
        }
        $fixed = @{
            SPRING_CONFIG_LOCATION = 'classpath:/application.yml'; SPRING_PROFILES_ACTIVE = 'local'
            SERVER_ADDRESS = '127.0.0.1'; SERVER_PORT = '7631'
            DB_URL = 'jdbc:mysql://127.0.0.1:13306/account_book_dev?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=Asia/Shanghai'
            DB_USERNAME = 'account_book_dev_app'
            WECHAT_APP_ID = (Get-LocalBackendAppId -RepoRoot $root)
            SPRING_FLYWAY_ENABLED = 'true'; SPRING_FLYWAY_VALIDATE_ON_MIGRATE = 'true'
            SPRING_FLYWAY_TARGET = '2'; SPRING_FLYWAY_CLEAN_DISABLED = 'true'
            SPRING_FLYWAY_BASELINE_ON_MIGRATE = 'false'; SPRING_JPA_HIBERNATE_DDL_AUTO = 'validate'
        }
        foreach ($name in $fixed.Keys) { $info.EnvironmentVariables[$name] = $fixed[$name] }
        $info.EnvironmentVariables['DB_PASSWORD'] = Convert-SecureValue $DatabasePassword
        $info.EnvironmentVariables['WECHAT_APP_SECRET'] = Convert-SecureValue $WeChatAppSecret
        $info.EnvironmentVariables['APP_BOOTSTRAP_KEY'] = Convert-SecureValue $Keys.BootstrapKey
        $info.EnvironmentVariables['APP_TOKEN_PEPPER'] = Convert-SecureValue $Keys.TokenPepper
        return $info
    } catch {
        $info.EnvironmentVariables.Clear()
        throw 'ChildConfigurationFailed'
    }
}

function Assert-LocalBackendMigrations {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$RepoRoot, [Parameter(Mandatory)][string]$JarPath)
    $directory = Join-Path $RepoRoot 'account-book-server/src/main/resources/db/migration'
    $files = @(Get-ChildItem -LiteralPath $directory -File -Recurse -Force)
    if ($files.Count -ne 2) { throw 'SourceMigrationInventoryRejected' }
    foreach ($file in $files) {
        if ($file.DirectoryName -ne [IO.Path]::GetFullPath($directory) -or -not $script:MigrationHashes.ContainsKey($file.Name)) { throw 'SourceMigrationInventoryRejected' }
        $stream = [IO.File]::OpenRead($file.FullName)
        try {
            $sha = [Security.Cryptography.SHA256]::Create()
            try { $hash = ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
            finally { $sha.Dispose() }
        } finally { $stream.Dispose() }
        if ($hash -ne $script:MigrationHashes[$file.Name]) { throw 'SourceMigrationHashRejected' }
    }
    $archive = [IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $entries = @($archive.Entries | Where-Object { $_.FullName -match '(^|/)db/migration/.+' -and -not $_.FullName.EndsWith('/') })
        if ($entries.Count -ne 2) { throw 'JarMigrationInventoryRejected' }
        $seen = @{}
        foreach ($entry in $entries) {
            if (-not $script:MigrationHashes.ContainsKey($entry.Name) -or $entry.FullName -cne ('BOOT-INF/classes/db/migration/' + $entry.Name) -or $seen.ContainsKey($entry.Name)) { throw 'JarMigrationInventoryRejected' }
            $seen[$entry.Name] = $true
            $stream = $entry.Open()
            $sha = [Security.Cryptography.SHA256]::Create()
            try { $hash = ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
            finally { $sha.Dispose(); $stream.Dispose() }
            if ($hash -ne $script:MigrationHashes[$entry.Name]) { throw 'JarMigrationHashRejected' }
        }
    } finally { $archive.Dispose() }
}

function Assert-LocalBackendListeners {
    [CmdletBinding()]
    param([Parameter(Mandatory)][AllowEmptyCollection()][object[]]$Listeners, [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$Processes)
    if (@($Listeners | Where-Object { $_.LocalPort -eq 7631 }).Count -ne 0) { throw 'AppPortOccupied' }
    $tunnel = @($Listeners | Where-Object { $_.LocalPort -eq 13306 })
    if ($tunnel.Count -ne 1 -or $tunnel[0].LocalAddress -ne '127.0.0.1') { throw 'TunnelListenerRejected' }
    $owners = @($Processes | Where-Object { $_.ProcessId -eq $tunnel[0].OwningProcess })
    if ($owners.Count -ne 1) { throw 'TunnelOwnerUnknown' }
    $process = $owners[0]
    $expected = Join-Path $env:SystemRoot 'System32/OpenSSH/ssh.exe'
    if (-not $process.ExecutablePath -or [IO.Path]::GetFullPath($process.ExecutablePath) -ine [IO.Path]::GetFullPath($expected)) { throw 'TunnelExecutableRejected' }
    # Intentionally accept only the known local tunnel's bounded option set.
    $prefix = '(?:"' + [regex]::Escape($expected) + '"|' + [regex]::Escape($expected) + ')'
    $options = '(?:\s+-N|\s+-T|\s+-o\s+ExitOnForwardFailure=yes|\s+-o\s+ServerAliveInterval=30|\s+-o\s+ServerAliveCountMax=3)*'
    $pattern = '^' + $prefix + $options + '\s+-L\s+127\.0\.0\.1:13306:127\.0\.0\.1:3306\s+root@117\.72\.101\.42\s*$'
    if ($process.CommandLine -inotmatch $pattern) { throw 'TunnelCommandRejected' }
}

function Assert-LocalBackendPorts {
    [CmdletBinding()]
    param()
    $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object { $_.LocalPort -eq 7631 -or $_.LocalPort -eq 13306 })
    $processes = @(foreach ($ownerId in @($listeners | Where-Object { $_.LocalPort -eq 13306 } | Select-Object -ExpandProperty OwningProcess -Unique)) {
        $candidate = Get-CimInstance Win32_Process -Filter ('ProcessId=' + [int]$ownerId) -ErrorAction Stop
        $metadata = Get-LimitedProcessMetadata -Process $candidate
        if ($null -ne $metadata) { $metadata }
    })
    Assert-LocalBackendListeners -Listeners $listeners -Processes $processes
}

function Assert-LocalBackendArtifacts {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$RepoRoot, [switch]$RequireJar)
    $jar = Join-Path $RepoRoot $script:JarRelativePath
    if (-not (Test-Path -LiteralPath $script:JavaPath -PathType Leaf)) { throw 'JavaOrJarMissing' }
    if ($RequireJar -and -not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw 'JavaOrJarMissing' }
    $null = Get-LocalBackendAppId -RepoRoot $RepoRoot
    # --no-index also checks ignore rules if someone mistakenly tracked a secret.
    foreach ($secretPath in @('secrets/local-dev/keys.dpapi', 'secrets/local-dev/credentials.dpapi')) {
        & git -C $RepoRoot check-ignore --quiet --no-index -- $secretPath 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'SecretsNotGitIgnored' }
    }
    $tracked = @(& git -C $RepoRoot ls-files -- secrets 2>$null)
    if ($LASTEXITCODE -ne 0 -or $tracked.Count -gt 0) { throw 'SecretsTrackedOrGitUnavailable' }
    $null = Get-OwnedKeyPath $RepoRoot
    if ($RequireJar) { Assert-LocalBackendMigrations -RepoRoot $RepoRoot -JarPath $jar }
}

function Assert-LocalBackendPreflight {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$RepoRoot)
    Assert-LocalBackendArtifacts -RepoRoot $RepoRoot -RequireJar
    Assert-LocalBackendPorts
}

Export-ModuleMember -Function Initialize-LocalBackendKeys, Get-LocalBackendKeys, Get-LocalBackendAppId, New-LocalBackendStartInfo, Assert-LocalBackendMigrations, Assert-LocalBackendListeners, Assert-LocalBackendPorts, Assert-LocalBackendArtifacts, Assert-LocalBackendPreflight
