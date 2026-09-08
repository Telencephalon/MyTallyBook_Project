[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$startupPath = Join-Path (Split-Path $PSScriptRoot -Parent) 'Start-LocalBackend.ps1'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$script:passed = 0
$script:failed = 0

function Assert-True($Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Assert-Disposed([Security.SecureString]$Value) {
    $disposed = $false
    try { $copy = $Value.Copy(); $copy.Dispose() }
    catch { $disposed = $_.Exception.InnerException -is [ObjectDisposedException] }
    Assert-True $disposed 'The startup script did not dispose an acquired secure value.'
}

function Invoke-OfflineStartup([string]$Mode = 'Preserve', [string]$FailurePoint = 'None') {
    $state = [hashtable]::Synchronized(@{
        ExpectedRoot = $repoRoot
        Mode = $Mode
        FailurePoint = $FailurePoint
        Events = [Collections.Generic.List[string]]::new()
        Messages = [Collections.Generic.List[string]]::new()
        SecureValues = [Collections.Generic.List[Security.SecureString]]::new()
        ChildInputsValid = $false
        ExitCode = $null
        Returned = $false
    })
    # A separate in-process runspace contains the script's `exit`, while invoking
    # the unmodified script by path keeps its parameter binding and cleanup real.
    # External boundaries are replaced: no DPAPI stores, prompts, preflight tools,
    # network, process launches, service operations, or repository writes occur.
    $harness = @'
param($StartupPath, $State)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
function Assert-FixtureRoot([string]$RepoRoot) {
    if ($RepoRoot -cne $State.ExpectedRoot) { throw 'WrongRepositoryRoot' }
}
function New-FixtureSecureValue([char]$Character) {
    $value = [Security.SecureString]::new()
    $value.AppendChar($Character)
    $State.SecureValues.Add($value)
    return $value
}
function Import-Module {
    [CmdletBinding()]
    param([string]$Name, [switch]$Force, [switch]$DisableNameChecking)
    $allowed = @(
        (Join-Path (Split-Path $StartupPath -Parent) 'LocalBackend.psm1'),
        (Join-Path (Split-Path $StartupPath -Parent) 'LocalBackendCredentials.psm1')
    )
    if ($allowed -cnotcontains $Name -or -not $Force -or -not $DisableNameChecking) { throw 'UnexpectedModuleImport' }
}
function Write-Host([string]$Object) { $State.Messages.Add($Object) }
function Read-Host { $State.Events.Add('Prompt'); throw 'UnexpectedInteractivePrompt' }
function Assert-LocalBackendPreflight([string]$RepoRoot) {
    Assert-FixtureRoot $RepoRoot
    $State.Events.Add('Preflight')
    if ($State.FailurePoint -eq 'Preflight') { throw 'SyntheticPreflightFailure' }
}
function Initialize-LocalBackendKeys([string]$RepoRoot) {
    Assert-FixtureRoot $RepoRoot
    $State.Events.Add('InitializeKeys')
    if ($State.Mode -eq 'Preserve') { throw 'UnexpectedKeyInitialization' }
    return [pscustomobject]@{ Path = (Join-Path $RepoRoot 'secrets/local-dev/keys.dpapi'); Version = 1; Created = $false }
}
function Get-LocalBackendKeys([string]$RepoRoot) {
    Assert-FixtureRoot $RepoRoot
    $State.Events.Add('ReadKeys')
    if ($State.FailurePoint -eq 'Keys') { throw 'SyntheticKeyStorageMissing' }
    return [pscustomobject]@{ BootstrapKey = (New-FixtureSecureValue 'b'); TokenPepper = (New-FixtureSecureValue 'p') }
}
function Get-LocalBackendCredentials([string]$RepoRoot) {
    Assert-FixtureRoot $RepoRoot
    $State.Events.Add('ReadCredentials')
    if ($State.FailurePoint -eq 'Credentials') { throw 'SyntheticCredentialStorageMissing' }
    return [pscustomobject]@{ DatabasePassword = (New-FixtureSecureValue 'd'); WeChatAppSecret = (New-FixtureSecureValue 'w') }
}
function Get-LocalBackendStartupCredentials([string]$RepoRoot) {
    Assert-FixtureRoot $RepoRoot
    $State.Events.Add('SetupCredentials')
    if ($State.Mode -eq 'Preserve') { throw 'UnexpectedCredentialSetup' }
    return [pscustomobject]@{ DatabasePassword = (New-FixtureSecureValue 'd'); WeChatAppSecret = (New-FixtureSecureValue 'w') }
}
function New-LocalBackendStartInfo {
    param([string]$RepoRoot, $Keys, [Security.SecureString]$DatabasePassword, [Security.SecureString]$WeChatAppSecret)
    Assert-FixtureRoot $RepoRoot
    $State.Events.Add('ChildConfiguration')
    $State.ChildInputsValid = (
        $State.SecureValues.Count -eq 4 -and
        [object]::ReferenceEquals($Keys.BootstrapKey, $State.SecureValues[0]) -and
        [object]::ReferenceEquals($Keys.TokenPepper, $State.SecureValues[1]) -and
        [object]::ReferenceEquals($DatabasePassword, $State.SecureValues[2]) -and
        [object]::ReferenceEquals($WeChatAppSecret, $State.SecureValues[3]) -and
        $DatabasePassword.Length -eq 1 -and $WeChatAppSecret.Length -eq 1
    )
    # Always stop before real Java; cleanup and the sanitized failure remain real.
    throw 'OfflineStopBeforeJava'
}
function Assert-LocalBackendPorts { $State.Events.Add('UnexpectedFinalPorts'); throw 'OfflineStopBeforeJava' }
$arguments = @{}
if ($State.Mode -eq 'Preserve') { $arguments.ExistingConfigurationOnly = $true }
elseif ($State.Mode -eq 'ExplicitFalse') { $arguments.ExistingConfigurationOnly = $false }
& $StartupPath @arguments
$State.ExitCode = $LASTEXITCODE
$State.Returned = $true
'@
    $pipeline = [Management.Automation.PowerShell]::Create()
    try {
        $null = $pipeline.AddScript($harness).AddArgument($startupPath).AddArgument($state)
        $null = $pipeline.Invoke()
        # HadErrors is also true for the script's intentional exit 1; only an
        # uncaught error record or failure to return indicates a harness error.
        Assert-True ($pipeline.Streams.Error.Count -eq 0) 'The startup script produced an uncaught error in the isolated caller.'
        Assert-True $state.Returned 'The startup script did not return safely to its isolated caller.'
        return $state
    } finally { $pipeline.Dispose() }
}

function Assert-OfflineResult($State, [string]$Events, [int]$SecureValueCount) {
    Assert-True (($State.Events -join ',') -ceq $Events) ('Unexpected startup boundary sequence: ' + ($State.Events -join ','))
    Assert-True ($State.ExitCode -eq 1) 'A rejected or deliberately stopped startup did not fail closed.'
    Assert-True ($State.SecureValues.Count -eq $SecureValueCount) 'Unexpected secure values were acquired.'
    foreach ($value in $State.SecureValues) { Assert-Disposed $value }
    Assert-True (-not (($State.Messages -join ' ') -match 'Synthetic|OfflineStopBeforeJava|UnexpectedKeyInitialization|UnexpectedCredentialSetup')) 'Internal failure detail escaped the startup status.'
}

function Test-Case([string]$Name, [scriptblock]$Body) {
    try {
        & $Body
        $script:passed++
        Write-Host ('PASS ' + $Name)
    } catch {
        $script:failed++
        Write-Host ('FAIL ' + $Name + ': ' + $_.Exception.Message)
    }
}

# These boundary assertions catch the real script selecting an initializing or
# interactive path, falling back after missing storage, or changing default use.
Test-Case 'preserve-only reads existing values directly and passes them to child configuration' {
    $state = Invoke-OfflineStartup
    Assert-OfflineResult $state 'Preflight,ReadKeys,ReadCredentials,ChildConfiguration' 4
    Assert-True $state.ChildInputsValid 'Child configuration did not receive the existing secure values unchanged.'
}
Test-Case 'preserve-only missing keys fails before credentials without initialization' {
    $state = Invoke-OfflineStartup -FailurePoint Keys
    Assert-OfflineResult $state 'Preflight,ReadKeys' 0
    Assert-True (-not $state.ChildInputsValid) 'Missing keys reached child configuration.'
}
Test-Case 'preserve-only missing credentials fails without setup and releases acquired keys' {
    $state = Invoke-OfflineStartup -FailurePoint Credentials
    Assert-OfflineResult $state 'Preflight,ReadKeys,ReadCredentials' 2
    Assert-True (-not $state.ChildInputsValid) 'Missing credentials reached child configuration.'
}
Test-Case 'preserve-only preflight rejection never accesses encrypted configuration' {
    $state = Invoke-OfflineStartup -FailurePoint Preflight
    Assert-OfflineResult $state 'Preflight' 0
}
Test-Case 'omitting the switch preserves the original initialization and credential setup flow' {
    $state = Invoke-OfflineStartup -Mode Default
    Assert-OfflineResult $state 'Preflight,InitializeKeys,ReadKeys,SetupCredentials,ChildConfiguration' 4
    Assert-True $state.ChildInputsValid 'Default startup did not pass its secure values to child configuration.'
}
Test-Case 'an explicitly false switch preserves the original startup flow' {
    $state = Invoke-OfflineStartup -Mode ExplicitFalse
    Assert-OfflineResult $state 'Preflight,InitializeKeys,ReadKeys,SetupCredentials,ChildConfiguration' 4
    Assert-True $state.ChildInputsValid 'Explicitly disabled preserve-only mode changed default startup.'
}

Write-Host ('RESULT passed=' + $script:passed + ' failed=' + $script:failed)
if ($script:failed -gt 0) { exit 1 }
