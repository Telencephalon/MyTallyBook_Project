Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'LocalBackend.psm1') -DisableNameChecking -ErrorAction Stop

function Assert-LocalBootstrapConsole {
    $remoteSession = Get-Variable -Name PSSenderInfo -ValueOnly -ErrorAction SilentlyContinue
    $nonInteractive = @([Environment]::GetCommandLineArgs() | Where-Object { $_ -match '^[-/]non' }).Count -gt 0
    if (-not [Environment]::UserInteractive -or $Host.Name -ne 'ConsoleHost' -or $nonInteractive -or $null -ne $remoteSession -or
        [Environment]::GetEnvironmentVariable('SSH_CONNECTION') -or [Environment]::GetEnvironmentVariable('SSH_CLIENT') -or
        [Console]::IsInputRedirected -or [Console]::IsOutputRedirected -or [Console]::IsErrorRedirected) {
        throw 'InteractiveLocalConsoleRequired'
    }
}

function Write-LocalBootstrapConsole([string]$Text) {
    # Deliberately bypass PowerShell success and information streams.
    [Console]::WriteLine($Text)
}

function Read-LocalBootstrapConfirmation {
    return [Console]::ReadLine()
}

function Invoke-LocalBootstrapKeyDisplay {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$RepoRoot)
    $keys = $null
    $pointer = [IntPtr]::Zero
    $plaintext = $null
    try {
        Assert-LocalBootstrapConsole
        Write-LocalBootstrapConsole 'Stop any transcript, screen recording, screen sharing or output capture before continuing.'
        Write-LocalBootstrapConsole 'External console logging cannot be prevented by this command. Nothing is copied to the clipboard.'
        Write-LocalBootstrapConsole 'Type SHOW to display APP_BOOTSTRAP_KEY in this local terminal, or anything else to cancel:'
        $confirmation = Read-LocalBootstrapConfirmation
        if ($confirmation -cne 'SHOW') {
            Write-LocalBootstrapConsole 'Cancelled. No key was read.'
            return 0
        }
        Assert-LocalBootstrapConsole
        $keys = Get-LocalBackendKeys -RepoRoot $RepoRoot
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($keys.BootstrapKey)
        $plaintext = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
        Write-LocalBootstrapConsole ('APP_BOOTSTRAP_KEY=' + $plaintext)
        return 0
    } catch {
        try { Write-LocalBootstrapConsole 'Bootstrap key display failed or was refused. No automatic changes were made.' } catch { }
        return 1
    } finally {
        if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
        $plaintext = $null
        if ($null -ne $keys) { $keys.BootstrapKey.Dispose(); $keys.TokenPepper.Dispose() }
    }
}

Export-ModuleMember -Function Invoke-LocalBootstrapKeyDisplay
