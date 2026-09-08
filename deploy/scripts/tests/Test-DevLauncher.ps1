[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$launcher = Join-Path $repo 'Start-Dev.cmd'
$passed = 0
$failed = 0

# Break caught: cwd-relative paths, broken quoting, implicit updates, arbitrary
# argument forwarding, or PAUSE overwriting a failing PowerShell exit code.
foreach ($case in @(
    @{ Name='default from foreign cwd with spaces and shell characters'; Arguments=''; ChildExit=0; WantExit=0; Update='False' },
    @{ Name='explicit update forwarded once'; Arguments='-UpdateBackend'; ChildExit=0; WantExit=0; Update='True' },
    @{ Name='child failure survives retained console'; Arguments=''; ChildExit=7; WantExit=7; Update='False' },
    @{ Name='unknown option cannot launch backend'; Arguments='-Unexpected'; ChildExit=0; WantExit=2; Update=$null },
    @{ Name='extra option cannot reach PowerShell'; Arguments='-UpdateBackend -Unexpected'; ChildExit=0; WantExit=2; Update=$null }
)) {
    $root = Join-Path ([IO.Path]::GetTempPath()) ('mtb-launcher-' + [guid]::NewGuid().ToString('N') + " space & owner's !book")
    $process = $null
    try {
        if (-not (Test-Path -LiteralPath $launcher)) { throw 'Root double-click launcher is missing.' }
        $scripts = Join-Path $root 'deploy/scripts'
        $null = New-Item -ItemType Directory -Path $scripts -Force
        Copy-Item -LiteralPath $launcher -Destination (Join-Path $root 'Start-Dev.cmd')
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'fixtures/dev-launcher/Start-Dev.ps1') -Destination (Join-Path $scripts 'Start-Dev.ps1')
        $info = [Diagnostics.ProcessStartInfo]::new()
        $info.FileName = Join-Path $env:SystemRoot 'System32/cmd.exe'
        $info.Arguments = '/d /s /c ""' + (Join-Path $root 'Start-Dev.cmd') + '" ' + $case.Arguments + '"'
        $info.WorkingDirectory = Join-Path $env:SystemRoot 'System32'
        $info.UseShellExecute = $false
        $info.CreateNoWindow = $true
        $info.RedirectStandardInput = $true
        $info.RedirectStandardOutput = $true
        $info.RedirectStandardError = $true
        $info.EnvironmentVariables['MTB_LAUNCHER_TEST_EXIT'] = [string]$case.ChildExit
        $process = [Diagnostics.Process]::Start($info)
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $process.StandardInput.WriteLine('continue')
        $process.StandardInput.Close()
        if (-not $process.WaitForExit(15000)) { $process.Kill(); throw 'Fixture launcher exceeded 15 seconds.' }
        $output = $stdout.GetAwaiter().GetResult()
        $errors = $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne $case.WantExit) { throw ('Wrong exit code: ' + $process.ExitCode + '; stdout=' + $output + '; stderr=' + $errors) }
        if ($null -eq $case.Update) {
            if ($output.Contains('FIXTURE_ROOT=')) { throw 'Rejected arguments still invoked the backend entry.' }
        } else {
            if (-not $output.Contains('FIXTURE_ROOT=' + $scripts) -or -not $output.Contains('FIXTURE_UPDATE=' + $case.Update)) { throw ('Wrong target or forwarded flag: ' + $output) }
        }
        $passed++
        Write-Host ('PASS ' + $case.Name)
    } catch {
        $failed++
        Write-Host ('FAIL ' + $case.Name + ': ' + $_.Exception.Message)
    } finally {
        if ($null -ne $process) { $process.Dispose() }
        $full = [IO.Path]::GetFullPath($root)
        $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
        if ($full.StartsWith($temp, [StringComparison]::OrdinalIgnoreCase) -and [IO.Path]::GetFileName($full).StartsWith('mtb-launcher-') -and (Test-Path -LiteralPath $full)) {
            Remove-Item -LiteralPath $full -Recurse -Force
        }
    }
}
Write-Host ('RESULT passed=' + $passed + ' failed=' + $failed)
if ($failed -gt 0) { exit 1 }
