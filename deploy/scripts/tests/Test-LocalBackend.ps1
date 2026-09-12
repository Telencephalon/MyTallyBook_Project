[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Security
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$modulePath = Join-Path (Split-Path $PSScriptRoot -Parent) 'LocalBackend.psm1'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('LocalBackendTests-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $testRoot
$script:passed = 0
$script:failed = 0
$script:caseNumber = 0
$moduleAvailable = Test-Path -LiteralPath $modulePath
if ($moduleAvailable) { Import-Module $modulePath -Force -DisableNameChecking }

function Assert-True($Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Assert-Rejected([scriptblock]$Body) {
    $rejected = $false
    try { & $Body | Out-Null } catch { $rejected = $true }
    Assert-True $rejected 'Unsafe input was accepted.'
}
function Test-Case([string]$Name, [scriptblock]$Body) {
    try {
        if (-not $moduleAvailable) { throw 'Required local startup module is not implemented.' }
        & $Body
        $script:passed++
        Write-Host ('PASS ' + $Name)
    } catch {
        $script:failed++
        Write-Host ('FAIL ' + $Name + ': ' + $_.Exception.Message)
    }
}
function New-TestRoot {
    $script:caseNumber++
    $path = Join-Path $testRoot ('case-' + $script:caseNumber)
    $null = New-Item -ItemType Directory -Path $path
    return $path
}
function Set-TestAppId([string]$Root, [string]$AppId) {
    $directory = Join-Path $Root 'account-book-miniapp'
    $null = New-Item -ItemType Directory -Path $directory -Force
    [IO.File]::WriteAllText((Join-Path $directory 'project.config.json'), ('{"appid":"' + $AppId + '"}'))
}
function Get-TestPlaintext([Security.SecureString]$Value) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}
function Close-TestKeys($Keys) {
    $Keys.BootstrapKey.Dispose()
    $Keys.TokenPepper.Dispose()
}
function Invoke-KeyCheckWithoutSecurityAutoload([string]$Root, [string]$Mode) {
    $childScript = @'
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
try {
    Import-Module "$PSHOME\Modules\Microsoft.PowerShell.Utility\Microsoft.PowerShell.Utility.psd1" -ErrorAction Stop
    Import-Module "$PSHOME\Modules\Microsoft.PowerShell.Management\Microsoft.PowerShell.Management.psd1" -ErrorAction Stop
    $PSModuleAutoLoadingPreference = 'None'
    if (Get-Command Get-Acl -ErrorAction SilentlyContinue) { throw 'SecurityIsolationFailed' }
    Import-Module $env:LOCAL_BACKEND_TEST_MODULE -Force -DisableNameChecking -ErrorAction Stop
    $root = $env:LOCAL_BACKEND_TEST_ROOT
    if ($env:LOCAL_BACKEND_TEST_MODE -eq 'valid') {
        $metadata = Initialize-LocalBackendKeys -RepoRoot $root
        if ($metadata.Created) { throw 'ExistingFixtureWasRegenerated' }
        $keys = Get-LocalBackendKeys -RepoRoot $root
        try {
            if ($keys.BootstrapKey -isnot [Security.SecureString] -or $keys.TokenPepper -isnot [Security.SecureString]) { throw 'ReaderTypeInvalid' }
        } finally { $keys.BootstrapKey.Dispose(); $keys.TokenPepper.Dispose() }
    } else {
        $aclFailure = $null
        try { & (Get-Module LocalBackend) { param($Path) Assert-OwnedKeyAcl $Path } (Join-Path $root 'secrets/local-dev/keys.dpapi') }
        catch { $aclFailure = $_.Exception.Message }
        if ($aclFailure -cne 'StorageAclRejected') { throw 'ExpectedActualAclRejection' }
        $readerFailure = $null
        try { $unexpectedKeys = Get-LocalBackendKeys -RepoRoot $root }
        catch { $readerFailure = $_.Exception.Message }
        finally { if ($null -ne $unexpectedKeys) { $unexpectedKeys.BootstrapKey.Dispose(); $unexpectedKeys.TokenPepper.Dispose() } }
        if ($readerFailure -cne 'KeyStorageReadFailed') { throw 'UnsafeAclReadWasAccepted' }
    }
    [Console]::WriteLine('IsolatedKeyCheckPassed')
} catch { [Console]::WriteLine($_.Exception.Message); exit 1 }
'@
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
    $info.Arguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand ' + [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($childScript))
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.EnvironmentVariables['LOCAL_BACKEND_TEST_MODULE'] = $modulePath
    $info.EnvironmentVariables['LOCAL_BACKEND_TEST_ROOT'] = $Root
    $info.EnvironmentVariables['LOCAL_BACKEND_TEST_MODE'] = $Mode
    $child = $null
    try {
        $child = [Diagnostics.Process]::Start($info)
        $outputTask = $child.StandardOutput.ReadToEndAsync()
        $errorTask = $child.StandardError.ReadToEndAsync()
        if (-not $child.WaitForExit(15000)) { $child.Kill(); throw 'Isolated test PowerShell timed out.' }
        $output = $outputTask.Result.Trim()
        Assert-True ($child.ExitCode -eq 0 -and $output -ceq 'IsolatedKeyCheckPassed' -and $errorTask.Result.Length -eq 0) ('Isolated key check failed: ' + $output)
    } finally {
        $info.EnvironmentVariables.Clear()
        if ($null -ne $child) { $child.Dispose() }
    }
}
function Write-TestProtectedPayload([string]$Path, [string]$Json) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Json)
    try {
        $encrypted = [Security.Cryptography.ProtectedData]::Protect($bytes, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        [IO.File]::WriteAllBytes($Path, $encrypted)
    } finally { [Array]::Clear($bytes, 0, $bytes.Length) }
}
function New-MigrationFixture {
    $root = New-TestRoot
    $directory = Join-Path $root 'account-book-server/src/main/resources/db/migration'
    $null = New-Item -ItemType Directory -Path $directory -Force
    Copy-Item -LiteralPath (Join-Path $repoRoot 'account-book-server/src/main/resources/db/migration/V1__init_schema.sql') -Destination $directory
	Copy-Item -LiteralPath (Join-Path $repoRoot 'account-book-server/src/main/resources/db/migration/V2__align_approved_design.sql') -Destination $directory
	Copy-Item -LiteralPath (Join-Path $repoRoot 'account-book-server/src/main/resources/db/migration/V3__add_entry_person_name.sql') -Destination $directory
	Copy-Item -LiteralPath (Join-Path $repoRoot 'account-book-server/src/main/resources/db/migration/V4__allow_shared_category_type.sql') -Destination $directory
    return $root
}
function Write-TestJar([string]$Root, [string]$Omit = '', [string]$Change = '', [string]$Extra = '') {
    $target = Join-Path $Root 'account-book-server/target'
    $null = New-Item -ItemType Directory -Path $target -Force
    $path = Join-Path $target 'account-book-server-0.0.1-SNAPSHOT.jar'
    $archive = [IO.Compression.ZipFile]::Open($path, [IO.Compression.ZipArchiveMode]::Create)
    try {
		foreach ($name in @('V1__init_schema.sql', 'V2__align_approved_design.sql', 'V3__add_entry_person_name.sql', 'V4__allow_shared_category_type.sql', $Extra)) {
            if (-not $name -or $name -eq $Omit) { continue }
            $entry = $archive.CreateEntry('BOOT-INF/classes/db/migration/' + $name)
            $stream = $entry.Open()
            try {
                if ($name -eq $Change -or $name -eq $Extra) { $bytes = [Text.Encoding]::UTF8.GetBytes('changed migration') }
                else { $bytes = [IO.File]::ReadAllBytes((Join-Path $Root ('account-book-server/src/main/resources/db/migration/' + $name))) }
                $stream.Write($bytes, 0, $bytes.Length)
            } finally { $stream.Dispose() }
        }
    } finally { $archive.Dispose() }
    return $path
}
function New-TestListener([string]$Address = '127.0.0.1', [int]$Port = 13306, [int]$OwnerId = 321) {
    return [pscustomobject]@{ LocalAddress = $Address; LocalPort = $Port; OwningProcess = $OwnerId }
}
function Invoke-MigrationValidationWithoutGetFileHash([string]$Root, [string]$JarPath) {
    & (Get-Module LocalBackend) {
        param($Root, $JarPath)
        # Local shadow reproduces failed command resolution without global changes.
        function Get-FileHash { throw [Management.Automation.CommandNotFoundException]::new('Get-FileHash is unavailable in this test.') }
        try { Assert-LocalBackendMigrations -RepoRoot $Root -JarPath $JarPath }
        finally { Remove-Item -LiteralPath Function:Get-FileHash }
    } $Root $JarPath
}
function New-TestSsh {
    $executable = Join-Path $env:SystemRoot 'System32/OpenSSH/ssh.exe'
    return [pscustomobject]@{
        ProcessId = 321
        ExecutablePath = $executable
        CommandLine = ('"' + $executable + '" -N -T -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -L 127.0.0.1:13306:127.0.0.1:3306 root@117.72.101.42')
    }
}

try {
    Test-Case 'initializer returns metadata only and stores encrypted bytes' {
        $root = New-TestRoot
        $result = Initialize-LocalBackendKeys -RepoRoot $root
        Assert-True ($result.Created -and $result.Version -eq 1) 'New key metadata is incorrect.'
        Assert-True (($result.PSObject.Properties.Name -join ',') -eq 'Path,Version,Created') 'Initializer exposes more than metadata.'
        $keys = Get-LocalBackendKeys -RepoRoot $root
        try {
            Assert-True ($keys.BootstrapKey -is [Security.SecureString] -and $keys.TokenPepper -is [Security.SecureString]) 'Reader exposes plaintext.'
            $bootstrap = Get-TestPlaintext $keys.BootstrapKey
            $pepper = Get-TestPlaintext $keys.TokenPepper
            Assert-True ([Convert]::FromBase64String($bootstrap).Length -eq 32) 'Bootstrap key is not 32 bytes.'
            Assert-True ([Convert]::FromBase64String($pepper).Length -eq 32 -and $bootstrap -ne $pepper) 'Keys are not independent.'
            $disk = [Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($result.Path))
            Assert-True (-not $disk.Contains($bootstrap) -and -not $disk.Contains($pepper) -and -not $disk.Contains('bootstrapKey')) 'Plaintext key payload reached disk.'
        } finally { Close-TestKeys $keys }
    }
    Test-Case 'repeat initialization preserves ciphertext and key identity' {
        $root = New-TestRoot
        $first = Initialize-LocalBackendKeys -RepoRoot $root
        $before = [Convert]::ToBase64String([IO.File]::ReadAllBytes($first.Path))
        $keys = Get-LocalBackendKeys -RepoRoot $root
        $again = Initialize-LocalBackendKeys -RepoRoot $root
        $keysAgain = Get-LocalBackendKeys -RepoRoot $root
        try {
            Assert-True (-not $again.Created) 'Existing keys were regenerated.'
            Assert-True ($before -ceq [Convert]::ToBase64String([IO.File]::ReadAllBytes($first.Path))) 'Ciphertext was overwritten.'
            Assert-True ((Get-TestPlaintext $keys.BootstrapKey) -ceq (Get-TestPlaintext $keysAgain.BootstrapKey)) 'Bootstrap identity changed.'
            Assert-True ((Get-TestPlaintext $keys.TokenPepper) -ceq (Get-TestPlaintext $keysAgain.TokenPepper)) 'Pepper identity changed.'
        } finally { Close-TestKeys $keys; Close-TestKeys $keysAgain }
    }
    Test-Case 'isolated PowerShell reads and reuses keys with Security autoload unavailable' {
        $root = New-TestRoot
        $metadata = Initialize-LocalBackendKeys -RepoRoot $root
        $before = [Convert]::ToBase64String([IO.File]::ReadAllBytes($metadata.Path))
        Invoke-KeyCheckWithoutSecurityAutoload $root 'valid'
        Assert-True ($before -ceq [Convert]::ToBase64String([IO.File]::ReadAllBytes($metadata.Path))) 'Isolated read/reuse changed ciphertext.'
    }
    Test-Case 'isolated PowerShell still rejects actual unsafe key ACL' {
        $root = New-TestRoot
        $metadata = Initialize-LocalBackendKeys -RepoRoot $root
        $before = [Convert]::ToBase64String([IO.File]::ReadAllBytes($metadata.Path))
        $acl = Get-Acl -LiteralPath $metadata.Path
        $usersSid = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-545')
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($usersSid, 'Read', 'Allow'))
        Set-Acl -LiteralPath $metadata.Path -AclObject $acl
        Invoke-KeyCheckWithoutSecurityAutoload $root 'invalidAcl'
        Assert-True ($before -ceq [Convert]::ToBase64String([IO.File]::ReadAllBytes($metadata.Path))) 'Unsafe-ACL rejection changed ciphertext.'
    }
    Test-Case 'corrupt existing storage fails closed without overwrite' {
        $root = New-TestRoot
        $metadata = Initialize-LocalBackendKeys -RepoRoot $root
        [IO.File]::WriteAllBytes($metadata.Path, [byte[]](1, 2, 3, 4))
        Assert-Rejected { Initialize-LocalBackendKeys -RepoRoot $root }
        Assert-Rejected { Get-LocalBackendKeys -RepoRoot $root }
        Assert-True ([Convert]::ToBase64String([IO.File]::ReadAllBytes($metadata.Path)) -ceq 'AQIDBA==') 'Corrupt evidence was changed.'
    }
    Test-Case 'locked existing key file is never replaced' {
        $root = New-TestRoot
        $metadata = Initialize-LocalBackendKeys -RepoRoot $root
        $before = [Convert]::ToBase64String([IO.File]::ReadAllBytes($metadata.Path))
        $lock = [IO.File]::Open($metadata.Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::None)
        try { Assert-Rejected { Initialize-LocalBackendKeys -RepoRoot $root } }
        finally { $lock.Dispose() }
        Assert-True ($before -ceq [Convert]::ToBase64String([IO.File]::ReadAllBytes($metadata.Path))) 'Locked key file was replaced.'
    }
    Test-Case 'concurrent initializers create only one stable key payload' {
        $root = New-TestRoot
        $jobs = @()
        try {
            foreach ($number in 1..3) {
                $jobs += Start-Job -ArgumentList $modulePath, $root -ScriptBlock {
                    param($ModulePath, $Root)
                    Import-Module $ModulePath -Force -DisableNameChecking
                    try { (Initialize-LocalBackendKeys -RepoRoot $Root).Created }
                    catch { 'SafelyRejected' }
                }
            }
            $results = @($jobs | Wait-Job -Timeout 20 | Receive-Job)
            Assert-True (@($results | Where-Object { $_ -is [bool] -and $_ }).Count -eq 1) 'Concurrent initialization created more or fewer than one key payload.'
            $metadata = Initialize-LocalBackendKeys -RepoRoot $root
            Assert-True (-not $metadata.Created) 'Concurrent result was not valid reusable storage.'
        } finally {
            $jobs | Stop-Job
            $jobs | Remove-Job -Force
        }
    }
    Test-Case 'invalid decrypted schema and key encoding fail closed' {
        $root = New-TestRoot
        $metadata = Initialize-LocalBackendKeys -RepoRoot $root
        foreach ($json in @('{"version":2,"bootstrapKey":"bad","tokenPepper":"bad"}', '{"version":1,"bootstrapKey":"YQ==","tokenPepper":"Yg=="}', '{"version":1,"bootstrapKey":"!","tokenPepper":"!"}', '{}')) {
            Write-TestProtectedPayload $metadata.Path $json
            $before = [Convert]::ToBase64String([IO.File]::ReadAllBytes($metadata.Path))
            Assert-Rejected { Initialize-LocalBackendKeys -RepoRoot $root }
            Assert-True ($before -ceq [Convert]::ToBase64String([IO.File]::ReadAllBytes($metadata.Path))) 'Invalid encrypted payload was replaced.'
        }
    }
    Test-Case 'owned directory and key file ACL allow only current user and SYSTEM' {
        $root = New-TestRoot
        $secrets = Join-Path $root 'secrets'
        $null = New-Item -ItemType Directory -Path $secrets
        $before = (Get-Acl -LiteralPath $secrets).Sddl
        $metadata = Initialize-LocalBackendKeys -RepoRoot $root
        $allowed = @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value, 'S-1-5-18')
        foreach ($path in @((Split-Path $metadata.Path -Parent), $metadata.Path)) {
            $acl = Get-Acl -LiteralPath $path
            $rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
            Assert-True ($rules.Count -gt 0) 'Owned path has no access rules.'
            foreach ($rule in $rules) { Assert-True ($allowed -contains $rule.IdentityReference.Value -and $rule.AccessControlType -eq 'Allow') 'Broad or unexpected ACL was granted.' }
        }
        Assert-True ($before -ceq (Get-Acl -LiteralPath $secrets).Sddl) 'Unrelated secrets parent permissions changed.'
    }
    Test-Case 'reparse secrets ancestor is rejected without touching target' {
        $root = New-TestRoot
        $outside = New-TestRoot
        $link = Join-Path $root 'secrets'
        $null = New-Item -ItemType Junction -Path $link -Target $outside
        try {
            Assert-Rejected { Initialize-LocalBackendKeys -RepoRoot $root }
            Assert-True (@(Get-ChildItem -LiteralPath $outside -Force).Count -eq 0) 'Reparse target was modified.'
        } finally { Remove-Item -LiteralPath $link -Force }
    }
    Test-Case 'reparse owned directory is rejected without touching target' {
        $root = New-TestRoot
        $outside = New-TestRoot
        $null = New-Item -ItemType Directory -Path (Join-Path $root 'secrets')
        $link = Join-Path $root 'secrets/local-dev'
        $null = New-Item -ItemType Junction -Path $link -Target $outside
        try {
            Assert-Rejected { Initialize-LocalBackendKeys -RepoRoot $root }
            Assert-True (@(Get-ChildItem -LiteralPath $outside -Force).Count -eq 0) 'Owned-directory reparse target was modified.'
        } finally { Remove-Item -LiteralPath $link -Force }
    }
    Test-Case 'read actual miniapp AppID and reject placeholders' {
        $root = New-TestRoot
        Set-TestAppId $root 'wx1234567890abcdef'
        Assert-True ((Get-LocalBackendAppId -RepoRoot $root) -ceq 'wx1234567890abcdef') 'Valid configured AppID was not used.'
        foreach ($invalid in @('touristappid', '', 'wx0000000000000000', 'wxYOUR_APP_ID', 'wx1234', 'wx1234567890abcdeg')) {
            Set-TestAppId $root $invalid
            Assert-Rejected { Get-LocalBackendAppId -RepoRoot $root }
        }
    }
    Test-Case 'start info confines secrets to child env and fixes app guards' {
        $root = New-TestRoot
        Set-TestAppId $root 'wx1234567890abcdef'
        $null = Initialize-LocalBackendKeys -RepoRoot $root
        $keys = Get-LocalBackendKeys -RepoRoot $root
        $db = ConvertTo-SecureString 'fake-database-password' -AsPlainText -Force
        $wechat = ConvertTo-SecureString 'fake-wechat-secret' -AsPlainText -Force
        try {
            $info = New-LocalBackendStartInfo -RepoRoot $root -Keys $keys -DatabasePassword $db -WeChatAppSecret $wechat
            Assert-True (-not $info.UseShellExecute -and -not $info.RedirectStandardOutput -and -not $info.RedirectStandardError -and -not $info.CreateNoWindow) 'Child cannot use the current interactive console.'
            Assert-True ($info.FileName -eq 'D:/Work/Config/JDK/JDK/jdk21/bin/java.exe') 'Unexpected Java executable.'
            Assert-True ($info.WorkingDirectory -eq (Join-Path $root 'account-book-server')) 'Unexpected working directory.'
            Assert-True ($info.Arguments -eq ('-jar "' + (Join-Path $root 'account-book-server/target/account-book-server-0.0.1-SNAPSHOT.jar') + '"')) 'Unexpected or secret-bearing Java arguments.'
            $expected = @{
                DB_URL = 'jdbc:mysql://127.0.0.1:13306/account_book_dev?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=Asia/Shanghai'
                DB_USERNAME = 'account_book_dev_app'; DB_PASSWORD = 'fake-database-password'
                WECHAT_APP_ID = 'wx1234567890abcdef'; WECHAT_APP_SECRET = 'fake-wechat-secret'
                SPRING_CONFIG_LOCATION = 'classpath:/application.yml'; SPRING_PROFILES_ACTIVE = 'local'
                SERVER_ADDRESS = '127.0.0.1'; SERVER_PORT = '7631'
                SPRING_FLYWAY_ENABLED = 'true'; SPRING_FLYWAY_VALIDATE_ON_MIGRATE = 'true'
				SPRING_FLYWAY_TARGET = '4'; SPRING_FLYWAY_CLEAN_DISABLED = 'true'
                SPRING_FLYWAY_BASELINE_ON_MIGRATE = 'false'; SPRING_JPA_HIBERNATE_DDL_AUTO = 'validate'
            }
            foreach ($name in $expected.Keys) { Assert-True ($info.EnvironmentVariables[$name] -ceq $expected[$name]) ('Incorrect child setting: ' + $name) }
            foreach ($secret in @('fake-database-password', 'fake-wechat-secret', (Get-TestPlaintext $keys.BootstrapKey), (Get-TestPlaintext $keys.TokenPepper))) {
                Assert-True (-not $info.Arguments.Contains($secret)) 'Credential reached command arguments.'
            }
            Assert-True ($info.EnvironmentVariables['APP_BOOTSTRAP_KEY'] -ceq (Get-TestPlaintext $keys.BootstrapKey)) 'Stored bootstrap key was not passed to child.'
            Assert-True ($info.EnvironmentVariables['APP_TOKEN_PEPPER'] -ceq (Get-TestPlaintext $keys.TokenPepper)) 'Stored pepper was not passed to child.'
        } finally {
            if ($null -ne $info) { $info.EnvironmentVariables.Clear() }
            $db.Dispose(); $wechat.Dispose(); Close-TestKeys $keys
        }
    }
    Test-Case 'start info drops malicious inherited configuration without parent writes' {
        $root = New-TestRoot
        Set-TestAppId $root 'wx1234567890abcdef'
        $null = Initialize-LocalBackendKeys -RepoRoot $root
        $keys = Get-LocalBackendKeys -RepoRoot $root
        $inputValue = ConvertTo-SecureString 'fake-value' -AsPlainText -Force
        $names = @('SPRING_APPLICATION_JSON', 'SPRING_PROFILES_INCLUDE', 'SPRING_CONFIG_ADDITIONAL_LOCATION', 'DB_EVIL', 'APP_EVIL', 'WECHAT_EVIL', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'UNRELATED_FAKE_SECRET')
        $before = @{}
        try {
            foreach ($name in $names) { $before[$name] = [Environment]::GetEnvironmentVariable($name, 'Process'); [Environment]::SetEnvironmentVariable($name, 'malicious-test-only', 'Process') }
            $info = New-LocalBackendStartInfo -RepoRoot $root -Keys $keys -DatabasePassword $inputValue -WeChatAppSecret $inputValue
            foreach ($name in $names) {
                Assert-True (-not $info.EnvironmentVariables.ContainsKey($name)) ('Inherited override forwarded: ' + $name)
                Assert-True ([Environment]::GetEnvironmentVariable($name, 'Process') -eq 'malicious-test-only') 'Parent process environment was changed.'
            }
        } finally {
            foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $before[$name], 'Process') }
            if ($null -ne $info) { $info.EnvironmentVariables.Clear() }
            $inputValue.Dispose(); Close-TestKeys $keys
        }
    }
    Test-Case 'trusted source and packaged migration bytes pass validation' {
        $root = New-MigrationFixture
        $jar = Write-TestJar $root
        Assert-LocalBackendMigrations -RepoRoot $root -JarPath $jar
    }
    Test-Case 'trusted migrations pass when Get-FileHash is unavailable' {
        $root = New-MigrationFixture
        $jar = Write-TestJar $root
        Invoke-MigrationValidationWithoutGetFileHash $root $jar
    }
    Test-Case 'changed source retains hash rejection when Get-FileHash is unavailable' {
        $root = New-MigrationFixture
        $jar = Write-TestJar $root
        [IO.File]::AppendAllText((Join-Path $root 'account-book-server/src/main/resources/db/migration/V1__init_schema.sql'), '-- changed')
        $failure = $null
        try { Invoke-MigrationValidationWithoutGetFileHash $root $jar }
        catch { $failure = $_.Exception.Message }
        Assert-True ($failure -ceq 'SourceMigrationHashRejected') ('Expected SourceMigrationHashRejected, got: ' + $failure)
    }
    Test-Case 'missing source migration is rejected' {
        $root = New-MigrationFixture
        $jar = Write-TestJar $root
        Remove-Item -LiteralPath (Join-Path $root 'account-book-server/src/main/resources/db/migration/V2__align_approved_design.sql')
        Assert-Rejected { Assert-LocalBackendMigrations -RepoRoot $root -JarPath $jar }
    }
    Test-Case 'modified source migration is rejected' {
        $root = New-MigrationFixture
        $jar = Write-TestJar $root
        [IO.File]::AppendAllText((Join-Path $root 'account-book-server/src/main/resources/db/migration/V1__init_schema.sql'), '-- changed')
        Assert-Rejected { Assert-LocalBackendMigrations -RepoRoot $root -JarPath $jar }
    }
    Test-Case 'unapproved source migration is rejected' {
        $root = New-MigrationFixture
        $jar = Write-TestJar $root
        [IO.File]::WriteAllText((Join-Path $root 'account-book-server/src/main/resources/db/migration/V3__unexpected.sql'), 'select 1;')
        Assert-Rejected { Assert-LocalBackendMigrations -RepoRoot $root -JarPath $jar }
    }
    Test-Case 'missing packaged migration is rejected' {
        $root = New-MigrationFixture
        $jar = Write-TestJar $root -Omit 'V2__align_approved_design.sql'
        Assert-Rejected { Assert-LocalBackendMigrations -RepoRoot $root -JarPath $jar }
    }
    Test-Case 'modified packaged migration is rejected' {
        $root = New-MigrationFixture
        $jar = Write-TestJar $root -Change 'V1__init_schema.sql'
        Assert-Rejected { Assert-LocalBackendMigrations -RepoRoot $root -JarPath $jar }
    }
    Test-Case 'additional packaged migration is rejected' {
        $root = New-MigrationFixture
        $jar = Write-TestJar $root -Extra 'V3__unexpected.sql'
        Assert-Rejected { Assert-LocalBackendMigrations -RepoRoot $root -JarPath $jar }
    }
    Test-Case 'expected SSH loopback listener passes nonmutating validation' {
        Assert-LocalBackendListeners -Listeners @((New-TestListener)) -Processes @((New-TestSsh))
    }
    Test-Case 'occupied app port including wildcard is rejected' {
        foreach ($address in @('127.0.0.1', '0.0.0.0', '::')) {
            Assert-Rejected { Assert-LocalBackendListeners -Listeners @((New-TestListener), (New-TestListener $address 7631 999)) -Processes @((New-TestSsh)) }
        }
    }
    Test-Case 'absent tunnel listener is rejected' {
        Assert-Rejected { Assert-LocalBackendListeners -Listeners @() -Processes @() }
    }
    Test-Case 'wildcard tunnel and unknown process owners are rejected' {
        Assert-Rejected { Assert-LocalBackendListeners -Listeners @((New-TestListener '0.0.0.0')) -Processes @((New-TestSsh)) }
        Assert-Rejected { Assert-LocalBackendListeners -Listeners @((New-TestListener -OwnerId 999)) -Processes @((New-TestSsh)) }
    }
    Test-Case 'wrong SSH executable destination forward or additional options are rejected' {
        foreach ($change in @('executable', 'destination', 'forward', 'option')) {
            $process = New-TestSsh
            switch ($change) {
                'executable' { $process.ExecutablePath = 'C:/untrusted/ssh.exe' }
                'destination' { $process.CommandLine = $process.CommandLine.Replace('117.72.101.42', '192.0.2.1') }
                'forward' { $process.CommandLine = $process.CommandLine.Replace('127.0.0.1:3306', '127.0.0.1:3307') }
                'option' { $process.CommandLine = $process.CommandLine.Replace(' -N ', ' -o ProxyCommand=unexpected -N ') }
            }
            Assert-Rejected { Assert-LocalBackendListeners -Listeners @((New-TestListener)) -Processes @($process) }
        }
    }
    Test-Case 'preflight rejects missing artifacts without initializing keys' {
        $root = New-TestRoot
        Assert-Rejected { Assert-LocalBackendPreflight -RepoRoot $root }
        Assert-True (-not (Test-Path -LiteralPath (Join-Path $root 'secrets'))) 'Preflight created secrets storage.'
    }
} finally {
    $resolved = [IO.Path]::GetFullPath($testRoot)
    $expectedParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    if ((Split-Path $resolved -Parent) -ne $expectedParent -or (Split-Path $resolved -Leaf) -notmatch '^LocalBackendTests-[0-9a-f]{32}$') { throw 'Refusing unsafe test cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
Write-Host ('RESULT passed=' + $script:passed + ' failed=' + $script:failed)
if ($script:failed -gt 0) { exit 1 }
