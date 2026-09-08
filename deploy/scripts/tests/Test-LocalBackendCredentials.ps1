[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$scriptsRoot = Split-Path $PSScriptRoot -Parent
Import-Module (Join-Path $scriptsRoot 'LocalBackend.psm1') -Force -DisableNameChecking
$credentialsModule = Join-Path $scriptsRoot 'LocalBackendCredentials.psm1'
$available = Test-Path -LiteralPath $credentialsModule
if ($available) { Import-Module $credentialsModule -Force -DisableNameChecking }
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('LocalCredentialsTests-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $testRoot
$script:passed = 0; $script:failed = 0; $script:caseNumber = 0

function Assert-True($Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Assert-Rejected([scriptblock]$Body, [string]$Category = '') {
    $failure = $null
    try { & $Body | Out-Null } catch { $failure = $_.Exception.Message }
    Assert-True ($null -ne $failure) 'Unsafe credential operation was accepted.'
    if ($Category) { Assert-True ($failure -ceq $Category) 'Credential failure was not sanitized to its fixed category.' }
}
function Assert-Disposed([Security.SecureString]$Value) {
    $disposed = $false
    try { $copy = $Value.Copy(); $copy.Dispose() } catch { $disposed = $_.Exception.InnerException -is [ObjectDisposedException] }
    Assert-True $disposed 'Secure input was not disposed on failure.'
}
function Get-TestPlaintext([Security.SecureString]$Value) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}
function Close-Credentials($Value) { $Value.DatabasePassword.Dispose(); $Value.WeChatAppSecret.Dispose() }
function Set-TestAppId([string]$Root, [string]$AppId = 'wx1234567890abcdef') {
    $directory = Join-Path $Root 'account-book-miniapp'
    $null = New-Item -ItemType Directory -Path $directory -Force
    [IO.File]::WriteAllText((Join-Path $directory 'project.config.json'), ('{"appid":"' + $AppId + '"}'))
}
function New-Fixture([bool]$CreateKeys = $true) {
    $script:caseNumber++
    $root = Join-Path $testRoot ('case-' + $script:caseNumber)
    $null = New-Item -ItemType Directory -Path $root
    Set-TestAppId $root
    if ($CreateKeys) { $null = Initialize-LocalBackendKeys -RepoRoot $root }
    return $root
}
function Get-Cipher([string]$Root) { return [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $Root 'secrets/local-dev/credentials.dpapi'))) }
function Save-TestCredentials([string]$Root, [string]$DatabasePassword = 'fake-db-password', [string]$WeChatAppSecret = 'fake-wechat-secret') {
    $db = ConvertTo-SecureString $DatabasePassword -AsPlainText -Force
    $wechat = ConvertTo-SecureString $WeChatAppSecret -AsPlainText -Force
    try { return Save-LocalBackendCredentials -RepoRoot $Root -DatabasePassword $db -WeChatAppSecret $wechat }
    finally { $db.Dispose(); $wechat.Dispose() }
}
function Invoke-StartupCredentials([string]$Root, [string]$DatabasePassword = 'fake-db-password', [string]$WeChatAppSecret = 'fake-wechat-secret', [bool]$CancelSecond = $false) {
    $state = [pscustomobject]@{ DatabaseText = $DatabasePassword; WeChatText = $WeChatAppSecret; Prompts = [Collections.Generic.List[string]]::new(); Inputs = [Collections.Generic.List[Security.SecureString]]::new(); Messages = [Collections.Generic.List[string]]::new(); Result = $null; Failure = $null }
    & (Get-Module LocalBackendCredentials) {
        param($Root, $DatabasePassword, $WeChatAppSecret, $CancelSecond, $State)
        function Read-Host([string]$Prompt, [switch]$AsSecureString) {
            if (-not $AsSecureString) { throw 'PromptWasNotHidden' }
            $State.Prompts.Add($Prompt)
            if ($CancelSecond -and $State.Prompts.Count -eq 2) { throw 'synthetic-sensitive-prompt-detail' }
            $value = if ($State.Prompts.Count -eq 1) { $State.DatabaseText } else { $State.WeChatText }
            $secure = [Security.SecureString]::new()
            foreach ($character in $value.ToCharArray()) { $secure.AppendChar($character) }
            $State.Inputs.Add($secure)
            return $secure
        }
        function Write-Host([string]$Object) { $State.Messages.Add($Object) }
        try { $State.Result = Get-LocalBackendStartupCredentials -RepoRoot $Root }
        catch { $State.Failure = $_.Exception.Message }
    } $Root $DatabasePassword $WeChatAppSecret $CancelSecond $state
    return $state
}
function Write-TestPayload([string]$Root, [string]$Json) {
    $plain = [Text.Encoding]::UTF8.GetBytes($Json)
    try { [IO.File]::WriteAllBytes((Join-Path $Root 'secrets/local-dev/credentials.dpapi'), [Security.Cryptography.ProtectedData]::Protect($plain, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)) }
    finally { [Array]::Clear($plain, 0, $plain.Length) }
}
function Test-Case([string]$Name, [scriptblock]$Body) {
    try {
        if (-not $available) { throw 'Local credential persistence module is not implemented.' }
        & $Body
        $script:passed++; Write-Host ('PASS ' + $Name)
    } catch { $script:failed++; Write-Host ('FAIL ' + $Name + ': ' + $_.Exception.Message + ' [test line ' + $_.InvocationInfo.ScriptLineNumber + ']') }
}

try {
    Test-Case 'save stores only encrypted credentials and preserves keys and directory ACL' {
        $root = New-Fixture
        $keyPath = Join-Path $root 'secrets/local-dev/keys.dpapi'
        $keyBefore = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keyPath))
        $keyAcl = (Get-Acl -LiteralPath $keyPath).Sddl
        $directory = Split-Path $keyPath -Parent
        $directoryAcl = (Get-Acl -LiteralPath $directory).Sddl
        $metadata = Save-TestCredentials $root
        Assert-True (($metadata.PSObject.Properties.Name -join ',') -ceq 'Path,Version,Created') 'Save returned more than safe metadata.'
        Assert-True ($metadata.Version -eq 1 -and $metadata.Created) 'Save metadata invalid.'
        $disk = [Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($metadata.Path))
        foreach ($value in @('fake-db-password', 'fake-wechat-secret', 'databasePassword', 'weChatAppSecret')) { Assert-True (-not $disk.Contains($value)) 'Plaintext credential payload reached disk.' }
        Assert-True ($keyBefore -ceq [Convert]::ToBase64String([IO.File]::ReadAllBytes($keyPath))) 'Existing key bytes changed.'
        Assert-True ($keyAcl -ceq (Get-Acl -LiteralPath $keyPath).Sddl -and $directoryAcl -ceq (Get-Acl -LiteralPath $directory).Sddl) 'Existing key or directory ACL changed.'
    }
    Test-Case 'reader returns correct disposable SecureStrings without changing ciphertext' {
        $root = New-Fixture
        $null = Save-TestCredentials $root
        $before = Get-Cipher $root
        $values = Get-LocalBackendCredentials -RepoRoot $root
        try {
            Assert-True ($values.DatabasePassword -is [Security.SecureString] -and $values.WeChatAppSecret -is [Security.SecureString]) 'Reader returned plaintext.'
            Assert-True ((Get-TestPlaintext $values.DatabasePassword) -ceq 'fake-db-password') 'Stored database password mismatch.'
            Assert-True ((Get-TestPlaintext $values.WeChatAppSecret) -ceq 'fake-wechat-secret') 'Stored WeChat secret mismatch.'
        } finally { Close-Credentials $values }
        Assert-True ($before -ceq (Get-Cipher $root)) 'Reader rewrote ciphertext.'
    }
    Test-Case 'first startup asks two hidden prompts and later startup reads without prompts' {
        $root = New-Fixture
        $first = Invoke-StartupCredentials $root
        Assert-True ($null -eq $first.Failure -and $first.Prompts.Count -eq 2) ('First startup failed; prompts=' + $first.Prompts.Count + '; category=' + $first.Failure)
        Assert-True ((Get-TestPlaintext $first.Result.DatabasePassword) -ceq 'fake-db-password' -and (Get-TestPlaintext $first.Result.WeChatAppSecret) -ceq 'fake-wechat-secret') 'Successful startup returned disposed or incorrect inputs.'
        Close-Credentials $first.Result
        $before = Get-Cipher $root
        $again = Invoke-StartupCredentials $root
        try {
            Assert-True ($null -eq $again.Failure -and $again.Prompts.Count -eq 0) 'Existing store caused credential prompts.'
            Assert-True ((Get-TestPlaintext $again.Result.DatabasePassword) -ceq 'fake-db-password') 'Startup did not reuse stored credentials.'
            Assert-True (-not ($first.Messages -join '').Contains('fake-db-password') -and -not ($first.Messages -join '').Contains('fake-wechat-secret')) 'Startup status leaked credentials.'
        } finally { Close-Credentials $again.Result }
        Assert-True ($before -ceq (Get-Cipher $root)) 'Repeated startup rewrote ciphertext.'
    }
    Test-Case 'existing credential file cannot be replaced even with valid new inputs' {
        $root = New-Fixture
        $null = Save-TestCredentials $root
        $before = Get-Cipher $root
        Assert-Rejected { Save-TestCredentials $root 'replacement-db' 'replacement-wechat' } 'CredentialStorageSaveFailed'
        Assert-True ($before -ceq (Get-Cipher $root)) 'Exclusive save replaced existing ciphertext.'
    }
    Test-Case 'empty input fails without a file and disposes both prompted inputs' {
        foreach ($which in @('db', 'wechat')) {
            $root = New-Fixture
            $state = if ($which -eq 'db') { Invoke-StartupCredentials $root -DatabasePassword '' } else { Invoke-StartupCredentials $root -WeChatAppSecret '' }
            Assert-True ($state.Failure -ceq 'CredentialSetupFailed') 'Empty input was not rejected safely.'
            Assert-True (-not (Test-Path -LiteralPath (Join-Path $root 'secrets/local-dev/credentials.dpapi'))) 'Empty credentials were persisted.'
            foreach ($value in $state.Inputs) { Assert-Disposed $value }
        }
    }
    Test-Case 'cancelled second prompt disposes first input and writes nothing' {
        $root = New-Fixture
        $state = Invoke-StartupCredentials $root -CancelSecond $true
        Assert-True ($state.Failure -ceq 'CredentialSetupFailed' -and $state.Inputs.Count -eq 1) 'Prompt cancellation was not sanitized.'
        Assert-Disposed $state.Inputs[0]
        Assert-True (-not (Test-Path -LiteralPath (Join-Path $root 'secrets/local-dev/credentials.dpapi'))) 'Cancelled input persisted a file.'
    }
    Test-Case 'actual pipeline Stop during second prompt disposes first input without writing' {
        $root = New-Fixture
        $state = [hashtable]::Synchronized(@{ Count = 0; FirstInput = $null; Messages = [Collections.Generic.List[string]]::new(); Waiting = [Threading.ManualResetEvent]::new($false) })
        $pipeline = [Management.Automation.PowerShell]::Create()
        $scriptText = @'
param($ModulePath, $Root, $State)
$ErrorActionPreference = 'Stop'
Import-Module $ModulePath -Force -DisableNameChecking
& (Get-Module LocalBackendCredentials) {
    param($Root, $State)
    function Read-Host([string]$Prompt, [switch]$AsSecureString) {
        if (-not $AsSecureString) { throw 'PromptWasNotHidden' }
        $State.Count++
        if ($State.Count -eq 1) {
            $State.FirstInput = ConvertTo-SecureString 'fake-pipeline-stop-password' -AsPlainText -Force
            return $State.FirstInput
        }
        $null = $State.Waiting.Set()
        while ($true) { Start-Sleep -Milliseconds 20 }
    }
    function Write-Host([string]$Object) { $State.Messages.Add($Object) }
    $null = Get-LocalBackendStartupCredentials -RepoRoot $Root
} $Root $State
'@
        try {
            $null = $pipeline.AddScript($scriptText).AddArgument($credentialsModule).AddArgument($root).AddArgument($state)
            $pending = $pipeline.BeginInvoke()
            Assert-True ($state.Waiting.WaitOne(10000)) 'Test pipeline did not reach second prompt.'
            $pipeline.Stop()
            try { $null = $pipeline.EndInvoke($pending) }
            catch { Assert-True ($_.Exception.InnerException -is [Management.Automation.PipelineStoppedException]) 'Unexpected pipeline-stop result.' }
            Assert-True ($pipeline.InvocationStateInfo.State -eq 'Stopped' -and $state.Count -eq 2) 'Real pipeline stop was not exercised.'
            Assert-Disposed $state.FirstInput
            Assert-True (-not (Test-Path -LiteralPath (Join-Path $root 'secrets/local-dev/credentials.dpapi'))) 'Stopped input persisted a credential file.'
            Assert-True (-not ($state.Messages -join '').Contains('fake-pipeline-stop-password')) 'Pipeline cancellation leaked input in status output.'
        } finally {
            $pipeline.Stop(); $pipeline.Dispose()
            if ($null -ne $state.FirstInput) { $state.FirstInput.Dispose() }
            $state.Waiting.Dispose()
        }
    }
    Test-Case 'corrupt store fails closed without prompts or replacement' {
        $root = New-Fixture
        $null = Save-TestCredentials $root
        [IO.File]::WriteAllBytes((Join-Path $root 'secrets/local-dev/credentials.dpapi'), [byte[]](1, 2, 3))
        $before = Get-Cipher $root
        $state = Invoke-StartupCredentials $root
        Assert-True ($state.Failure -ceq 'CredentialSetupFailed' -and $state.Prompts.Count -eq 0) 'Corrupt store silently reprompted or succeeded.'
        Assert-True ($before -ceq (Get-Cipher $root)) 'Corrupt evidence was changed.'
    }
    Test-Case 'AppID change fails closed without prompts or replacement' {
        $root = New-Fixture
        $null = Save-TestCredentials $root
        $before = Get-Cipher $root
        Set-TestAppId $root 'wxabcdef1234567890'
        $state = Invoke-StartupCredentials $root
        Assert-True ($state.Failure -ceq 'CredentialSetupFailed' -and $state.Prompts.Count -eq 0) 'Wrong AppID was accepted or reprompted.'
        Assert-True ($before -ceq (Get-Cipher $root)) 'AppID mismatch changed stored credentials.'
    }
    Test-Case 'strict payload rejects wrong version fields types and empty values' {
        $root = New-Fixture
        $null = Save-TestCredentials $root
        foreach ($json in @('{}', '{"version":2,"appId":"wx1234567890abcdef","databasePassword":"x","weChatAppSecret":"y"}', '{"version":"1","appId":"wx1234567890abcdef","databasePassword":"x","weChatAppSecret":"y"}', '{"version":1,"appId":"wx1234567890abcdef","databasePassword":"","weChatAppSecret":"y"}', '{"version":1,"appId":"wx1234567890abcdef","databasePassword":123,"weChatAppSecret":"y"}', '{"version":1,"appId":"wx1234567890abcdef","databasePassword":"x","weChatAppSecret":"y","username":"root"}')) {
            Write-TestPayload $root $json
            $before = Get-Cipher $root
            Assert-Rejected { Get-LocalBackendCredentials -RepoRoot $root } 'CredentialStorageReadFailed'
            Assert-True ($before -ceq (Get-Cipher $root)) 'Invalid schema was replaced.'
        }
    }
    Test-Case 'unsafe file ACL is rejected without repair or prompts' {
        $root = New-Fixture
        $metadata = Save-TestCredentials $root
        $acl = Get-Acl -LiteralPath $metadata.Path
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new('S-1-5-32-545'), 'Read', 'Allow'))
        Set-Acl -LiteralPath $metadata.Path -AclObject $acl
        $beforeAcl = (Get-Acl -LiteralPath $metadata.Path).Sddl
        $before = Get-Cipher $root
        $state = Invoke-StartupCredentials $root
        Assert-True ($state.Failure -ceq 'CredentialSetupFailed' -and $state.Prompts.Count -eq 0) 'Unsafe file ACL was accepted or reprompted.'
        Assert-True ($beforeAcl -ceq (Get-Acl -LiteralPath $metadata.Path).Sddl -and $before -ceq (Get-Cipher $root)) 'Unsafe credential file was repaired or rewritten.'
    }
    Test-Case 'unsafe existing directory ACL is rejected before prompts with no changes' {
        $root = New-Fixture
        $directory = Join-Path $root 'secrets/local-dev'
        $acl = [IO.Directory]::GetAccessControl($directory, [Security.AccessControl.AccessControlSections]::Access)
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new('S-1-5-32-545'), 'Read', 'Allow'))
        [IO.Directory]::SetAccessControl($directory, $acl)
        $before = (Get-Acl -LiteralPath $directory).Sddl
        $state = Invoke-StartupCredentials $root
        Assert-True ($state.Failure -ceq 'CredentialSetupFailed' -and $state.Prompts.Count -eq 0) 'Unsafe directory prompted for secrets.'
        Assert-True ($before -ceq (Get-Acl -LiteralPath $directory).Sddl) 'Existing directory ACL was changed.'
        Assert-True (-not (Test-Path -LiteralPath (Join-Path $directory 'credentials.dpapi'))) 'Unsafe directory accepted a credential write.'
    }
    Test-Case 'reparse ancestor and credential target are rejected without following them' {
        foreach ($part in @('secrets', 'secrets/local-dev', 'secrets/local-dev/credentials.dpapi')) {
            $root = New-Fixture $false
            $outside = New-Fixture
            $link = Join-Path $root $part
            $null = New-Item -ItemType Directory -Path (Split-Path $link -Parent) -Force
            $null = New-Item -ItemType Junction -Path $link -Target $outside
            try {
                $state = Invoke-StartupCredentials $root
                Assert-True ($state.Failure -ceq 'CredentialSetupFailed' -and $state.Prompts.Count -eq 0) 'Reparse storage was followed or prompted.'
                Assert-True (-not (Test-Path -LiteralPath (Join-Path $outside 'credentials.dpapi'))) 'Reparse target received a credential file.'
            } finally { [IO.Directory]::Delete($link) }
        }
    }
    Test-Case 'preflight rejects credentials not ignored even when keys are ignored' {
        $root = New-Fixture
        $jarDirectory = Join-Path $root 'account-book-server/target'
        $null = New-Item -ItemType Directory -Path $jarDirectory -Force
        [IO.File]::WriteAllBytes((Join-Path $jarDirectory 'account-book-server-0.0.1-SNAPSHOT.jar'), [byte[]](0))
        & (Get-Module LocalBackend) {
            param($Root)
            function git {
                $code = if ($args -contains 'secrets/local-dev/credentials.dpapi') { 1 } else { 0 }
                Set-Variable -Name LASTEXITCODE -Value $code -Scope 1
            }
            function Assert-LocalBackendMigrations { }
            function Assert-LocalBackendPorts { }
            $failure = $null
            try { Assert-LocalBackendPreflight -RepoRoot $Root } catch { $failure = $_.Exception.Message }
            if ($failure -cne 'SecretsNotGitIgnored') { throw 'Preflight allowed unignored credential storage.' }
        } $root
    }
    Test-Case 'preflight rejects tracked secrets before credential collection' {
        $root = New-Fixture
        $jarDirectory = Join-Path $root 'account-book-server/target'
        $null = New-Item -ItemType Directory -Path $jarDirectory -Force
        [IO.File]::WriteAllBytes((Join-Path $jarDirectory 'account-book-server-0.0.1-SNAPSHOT.jar'), [byte[]](0))
        & (Get-Module LocalBackend) {
            param($Root)
            function git {
                Set-Variable -Name LASTEXITCODE -Value 0 -Scope 1
                if ($args -contains 'ls-files') { return 'secrets/local-dev/credentials.dpapi' }
            }
            function Assert-LocalBackendMigrations { }
            function Assert-LocalBackendPorts { }
            $failure = $null
            try { Assert-LocalBackendPreflight -RepoRoot $Root } catch { $failure = $_.Exception.Message }
            if ($failure -cne 'SecretsTrackedOrGitUnavailable') { throw 'Preflight allowed tracked secret content.' }
        } $root
    }
} finally {
    $resolved = [IO.Path]::GetFullPath($testRoot)
    $expectedParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    if ((Split-Path $resolved -Parent) -ne $expectedParent -or (Split-Path $resolved -Leaf) -notmatch '^LocalCredentialsTests-[0-9a-f]{32}$') { throw 'Refusing unsafe credential-test cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
Write-Host ('RESULT passed=' + $script:passed + ' failed=' + $script:failed)
if ($script:failed -gt 0) { exit 1 }
