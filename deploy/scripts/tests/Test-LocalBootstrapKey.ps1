[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$scriptsRoot = Split-Path $PSScriptRoot -Parent
$displayModulePath = Join-Path $scriptsRoot 'LocalBootstrapKey.psm1'
$entryPath = Join-Path $scriptsRoot 'Show-LocalBootstrapKey.ps1'
Import-Module (Join-Path $scriptsRoot 'LocalBackend.psm1') -Force -DisableNameChecking
$available = Test-Path -LiteralPath $displayModulePath
if ($available) { Import-Module $displayModulePath -Force -DisableNameChecking }
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('LocalBootstrapKeyTests-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $testRoot
$script:passed = 0
$script:failed = 0
$script:caseNumber = 0

function Assert-True($Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Assert-Disposed([Security.SecureString]$Value) {
    $disposed = $false
    try { $copy = $Value.Copy(); $copy.Dispose() }
    catch { $disposed = $_.Exception.InnerException -is [ObjectDisposedException] }
    Assert-True $disposed 'A returned SecureString was not disposed.'
}
function Get-TestPlaintext([Security.SecureString]$Value) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}
function New-TestFixture {
    $script:caseNumber++
    $root = Join-Path $testRoot ('case-' + $script:caseNumber)
    $null = New-Item -ItemType Directory -Path $root
    $metadata = Initialize-LocalBackendKeys -RepoRoot $root
    return [pscustomobject]@{ Root = $root; Path = $metadata.Path; Cipher = [Convert]::ToBase64String([IO.File]::ReadAllBytes($metadata.Path)) }
}
function Assert-Unchanged($Fixture) {
    Assert-True ($Fixture.Cipher -ceq [Convert]::ToBase64String([IO.File]::ReadAllBytes($Fixture.Path))) 'Display changed encrypted fixture bytes.'
    Assert-True (@(Get-ChildItem -LiteralPath $Fixture.Root -Recurse -File -Force).Count -eq 1) 'Display created an extra file.'
}
function Invoke-TestDisplay([string]$Root, [string]$Answer = 'SHOW', [bool]$RejectConsole = $false, [bool]$FailRead = $false, [bool]$FailWrite = $false, [bool]$FailAllWrites = $false) {
    $state = [pscustomobject]@{
        Answer = $Answer; RejectConsole = $RejectConsole; FailRead = $FailRead; FailWrite = $FailWrite; FailAllWrites = $FailAllWrites
        Reads = 0; Prompts = 0; Messages = [Collections.Generic.List[string]]::new(); Keys = $null; Result = $null
    }
    # Only the local-console boundary is simulated. Reads use the real DPAPI reader.
    & (Get-Module LocalBootstrapKey) {
        param($Root, $State)
        function Assert-LocalBootstrapConsole { if ($State.RejectConsole) { throw 'SyntheticConsoleRejection' } }
        function Read-LocalBootstrapConfirmation { $State.Prompts++; return $State.Answer }
        function Write-LocalBootstrapConsole([string]$Text) {
            if ($State.FailAllWrites) { throw 'synthetic-console-unavailable-detail' }
            if ($State.FailWrite -and $Text.StartsWith('APP_BOOTSTRAP_KEY=')) { throw 'synthetic-sensitive-output-detail' }
            $State.Messages.Add($Text)
        }
        function Get-LocalBackendKeys([string]$RepoRoot) {
            $State.Reads++
            if ($State.FailRead) { throw 'synthetic-sensitive-reader-detail' }
            $State.Keys = LocalBackend\Get-LocalBackendKeys -RepoRoot $RepoRoot
            return $State.Keys
        }
        $State.Result = @(Invoke-LocalBootstrapKeyDisplay -RepoRoot $Root)
    } $Root $state
    return $state
}
function Test-Case([string]$Name, [scriptblock]$Body) {
    try {
        if (-not $available) { throw 'Local bootstrap display module is not implemented.' }
        & $Body
        $script:passed++
        Write-Host ('PASS ' + $Name)
    } catch {
        $script:failed++
        # Test failures never include captured console content or key values.
        Write-Host ('FAIL ' + $Name + ': ' + $_.Exception.Message)
    }
}

try {
    Test-Case 'confirmed display shows only bootstrap on console and disposes both secrets' {
        $fixture = New-TestFixture
        $expected = Get-LocalBackendKeys -RepoRoot $fixture.Root
        try { $bootstrap = Get-TestPlaintext $expected.BootstrapKey; $pepper = Get-TestPlaintext $expected.TokenPepper }
        finally { $expected.BootstrapKey.Dispose(); $expected.TokenPepper.Dispose() }
        $state = Invoke-TestDisplay $fixture.Root
        Assert-True ($state.Reads -eq 1 -and $state.Prompts -eq 1) 'Confirmation did not precede a single key read.'
        Assert-True ($state.Result.Count -eq 1 -and $state.Result[0] -is [int] -and $state.Result[0] -eq 0) 'Display returned more than a success status.'
        Assert-True (@($state.Messages | Where-Object { $_ -ceq ('APP_BOOTSTRAP_KEY=' + $bootstrap) }).Count -eq 1) 'Bootstrap was not displayed exactly once.'
        Assert-True (-not ($state.Messages -join "`n").Contains($pepper)) 'Pepper reached the console.'
        Assert-Disposed $state.Keys.BootstrapKey
        Assert-Disposed $state.Keys.TokenPepper
        Assert-Unchanged $fixture
        $bootstrap = $null; $pepper = $null
    }
    Test-Case 'cancellation and nonexact confirmation never read or display key material' {
        $fixture = New-TestFixture
        foreach ($answer in @('', 'show', ' SHOW ', 'NO')) {
            $state = Invoke-TestDisplay $fixture.Root $answer
            Assert-True ($state.Reads -eq 0 -and $null -eq $state.Keys) 'Cancelled confirmation read keys.'
            Assert-True ($state.Result.Count -eq 1 -and $state.Result[0] -eq 0) 'Cancellation failed.'
            Assert-True (@($state.Messages | Where-Object { $_.StartsWith('APP_BOOTSTRAP_KEY=') }).Count -eq 0) 'Cancellation displayed a key.'
        }
        Assert-Unchanged $fixture
    }
    Test-Case 'console rejection occurs before confirmation or key read' {
        $fixture = New-TestFixture
        $state = Invoke-TestDisplay $fixture.Root -RejectConsole $true
        Assert-True ($state.Reads -eq 0 -and $state.Prompts -eq 0) 'Rejected console prompted or decrypted.'
        Assert-True ($state.Result.Count -eq 1 -and $state.Result[0] -eq 1) 'Rejected console succeeded.'
        Assert-True (-not ($state.Messages -join "`n").Contains('SyntheticConsoleRejection')) 'Console error detail escaped.'
        Assert-Unchanged $fixture
    }
    Test-Case 'reader failure exposes only sanitized status' {
        $fixture = New-TestFixture
        $state = Invoke-TestDisplay $fixture.Root -FailRead $true
        Assert-True ($state.Reads -eq 1 -and $state.Result[0] -eq 1) 'Read failure was not reported.'
        Assert-True (-not ($state.Messages -join "`n").Contains('synthetic-sensitive-reader-detail')) 'Reader exception leaked.'
        Assert-True (@($state.Messages | Where-Object { $_.StartsWith('APP_BOOTSTRAP_KEY=') }).Count -eq 0) 'Failed read displayed a key.'
        Assert-Unchanged $fixture
    }
    Test-Case 'output failure still disposes bootstrap and pepper' {
        $fixture = New-TestFixture
        $state = Invoke-TestDisplay $fixture.Root -FailWrite $true
        Assert-True ($state.Result[0] -eq 1) 'Output failure succeeded.'
        Assert-True (-not ($state.Messages -join "`n").Contains('synthetic-sensitive-output-detail')) 'Output exception leaked.'
        Assert-Disposed $state.Keys.BootstrapKey
        Assert-Disposed $state.Keys.TokenPepper
        Assert-Unchanged $fixture
    }
    Test-Case 'unavailable error output does not leak a raw exception or read keys' {
        $fixture = New-TestFixture
        $state = Invoke-TestDisplay $fixture.Root -FailAllWrites $true
        Assert-True ($state.Reads -eq 0 -and $state.Result[0] -eq 1) 'Unavailable console did not fail safely.'
        Assert-Unchanged $fixture
    }
    Test-Case 'real console guard rejects this redirected test process' {
        $rejected = $false
        try { & (Get-Module LocalBootstrapKey) { Assert-LocalBootstrapConsole } }
        catch { $rejected = $true }
        Assert-True $rejected 'Actual redirected console was accepted.'
    }
    Test-Case 'standalone command refuses redirected noninteractive execution without secret access' {
        # Empty temporary repo means no real keys could be read even if a guard regressed.
        $root = Join-Path $testRoot 'redirected-entry'
        $directory = Join-Path $root 'deploy/scripts'
        $null = New-Item -ItemType Directory -Path $directory -Force
        foreach ($name in @('Show-LocalBootstrapKey.ps1', 'LocalBootstrapKey.psm1', 'LocalBackend.psm1')) {
            Copy-Item -LiteralPath (Join-Path $scriptsRoot $name) -Destination $directory
        }
        $info = [Diagnostics.ProcessStartInfo]::new()
        $info.FileName = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
        $info.Arguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + (Join-Path $directory 'Show-LocalBootstrapKey.ps1') + '"'
        $info.UseShellExecute = $false; $info.CreateNoWindow = $true
        $info.RedirectStandardInput = $true; $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
        $child = $null
        try {
            $child = [Diagnostics.Process]::Start($info)
            $child.StandardInput.Close()
            $output = $child.StandardOutput.ReadToEndAsync()
            $errors = $child.StandardError.ReadToEndAsync()
            if (-not $child.WaitForExit(15000)) { $child.Kill(); throw 'Refusal command timed out.' }
            Assert-True ($child.ExitCode -eq 1) 'Redirected standalone execution was accepted.'
            Assert-True (($output.Result + $errors.Result) -match 'refused') 'Expected sanitized console refusal was absent.'
            Assert-True (-not ($output.Result + $errors.Result).Contains('Type SHOW')) 'Redirected execution asked for confirmation.'
            Assert-True (-not (Test-Path -LiteralPath (Join-Path $root 'secrets'))) 'Refused command wrote secrets storage.'
        } finally { $info.EnvironmentVariables.Clear(); if ($null -ne $child) { $child.Dispose() } }
    }
} finally {
    $resolved = [IO.Path]::GetFullPath($testRoot)
    $expectedParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    if ((Split-Path $resolved -Parent) -ne $expectedParent -or (Split-Path $resolved -Leaf) -notmatch '^LocalBootstrapKeyTests-[0-9a-f]{32}$') { throw 'Refusing unsafe test cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
Write-Host ('RESULT passed=' + $script:passed + ' failed=' + $script:failed)
if ($script:failed -gt 0) { exit 1 }
