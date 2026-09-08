Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module -Name "$PSHOME\Modules\Microsoft.PowerShell.Security\Microsoft.PowerShell.Security.psd1" -ErrorAction Stop
Import-Module (Join-Path $PSScriptRoot 'LocalBackend.psm1') -DisableNameChecking -ErrorAction Stop
Add-Type -AssemblyName System.Security

function Assert-CredentialStorageAcl([string]$Path) {
    $allowed = @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value, 'S-1-5-18')
    $acl = Get-Acl -LiteralPath $Path
    if ($allowed -notcontains $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value) { throw 'CredentialStorageAclRejected' }
    $rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
    if ($rules.Count -eq 0) { throw 'CredentialStorageAclRejected' }
    foreach ($rule in $rules) {
        if ($allowed -notcontains $rule.IdentityReference.Value -or $rule.AccessControlType -ne 'Allow') { throw 'CredentialStorageAclRejected' }
    }
}

function Get-CredentialStoragePath([string]$RepoRoot) {
    if (-not (Test-Path -LiteralPath $RepoRoot -PathType Container)) { throw 'CredentialStorageRootInvalid' }
    $path = Join-Path ([IO.Path]::GetFullPath($RepoRoot)) 'secrets/local-dev/credentials.dpapi'
    $candidate = $path
    while ($candidate) {
        $item = Get-Item -LiteralPath $candidate -Force -ErrorAction SilentlyContinue
        if ($null -ne $item -and ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'CredentialStorageReparsePointRejected' }
        $parent = Split-Path $candidate -Parent
        if ($parent -eq $candidate) { break }
        $candidate = $parent
    }
    $directory = Split-Path $path -Parent
    # The key initializer owns this directory. Never create it or alter its ACL here.
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) { throw 'CredentialStorageDirectoryMissing' }
    Assert-CredentialStorageAcl $directory
    if (Test-Path -LiteralPath $path) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'CredentialStorageFileInvalid' }
        Assert-CredentialStorageAcl $path
    }
    return $path
}

function Assert-CredentialPayload($Payload, [string]$AppId) {
    $names = @($Payload.PSObject.Properties.Name)
    if ($names.Count -ne 4 -or $names -notcontains 'version' -or $names -notcontains 'appId' -or $names -notcontains 'databasePassword' -or $names -notcontains 'weChatAppSecret') { throw 'CredentialPayloadInvalid' }
    if ($Payload.version -isnot [int] -or $Payload.version -ne 1 -or $Payload.appId -isnot [string] -or $Payload.appId -cne $AppId) { throw 'CredentialPayloadInvalid' }
    foreach ($value in @($Payload.databasePassword, $Payload.weChatAppSecret)) {
        if ($value -isnot [string] -or $value.Length -eq 0) { throw 'CredentialPayloadInvalid' }
    }
}

function Convert-CredentialSecureValue([Security.SecureString]$Value) {
    if ($null -eq $Value -or $Value.Length -eq 0) { throw 'CredentialInputEmpty' }
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

function Save-LocalBackendCredentials {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$RepoRoot,
        [Parameter(Mandatory)][Security.SecureString]$DatabasePassword,
        [Parameter(Mandatory)][Security.SecureString]$WeChatAppSecret
    )
    $plainBytes = $null
    $payload = $null
    try {
        $path = Get-CredentialStoragePath $RepoRoot
        if (Test-Path -LiteralPath $path) { throw 'CredentialStorageAlreadyExists' }
        $payload = [ordered]@{
            version = 1
            appId = (Get-LocalBackendAppId -RepoRoot $RepoRoot)
            databasePassword = (Convert-CredentialSecureValue $DatabasePassword)
            weChatAppSecret = (Convert-CredentialSecureValue $WeChatAppSecret)
        }
        $plainBytes = [Text.Encoding]::UTF8.GetBytes(($payload | ConvertTo-Json -Compress))
        $cipher = [Security.Cryptography.ProtectedData]::Protect($plainBytes, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        $null = Get-CredentialStoragePath $RepoRoot
        $stream = [IO.File]::Open($path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try { $stream.Write($cipher, 0, $cipher.Length); $stream.Flush($true) }
        finally { $stream.Dispose() }
        Assert-CredentialStorageAcl $path
        return [pscustomobject]@{ Path = $path; Version = 1; Created = $true }
    } catch { throw 'CredentialStorageSaveFailed' }
    finally {
        if ($null -ne $plainBytes) { [Array]::Clear($plainBytes, 0, $plainBytes.Length) }
        $payload = $null
    }
}

function Get-LocalBackendCredentials {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$RepoRoot)
    $plainBytes = $null
    $payload = $null
    $databasePassword = $null
    $weChatAppSecret = $null
    try {
        $path = Get-CredentialStoragePath $RepoRoot
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'CredentialStorageMissing' }
        $plainBytes = [Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes($path), $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        $payload = [Text.Encoding]::UTF8.GetString($plainBytes) | ConvertFrom-Json
        Assert-CredentialPayload $payload (Get-LocalBackendAppId -RepoRoot $RepoRoot)
        $databasePassword = ConvertTo-SecureString $payload.databasePassword -AsPlainText -Force
        $weChatAppSecret = ConvertTo-SecureString $payload.weChatAppSecret -AsPlainText -Force
        return [pscustomobject]@{ DatabasePassword = $databasePassword; WeChatAppSecret = $weChatAppSecret }
    } catch {
        if ($null -ne $databasePassword) { $databasePassword.Dispose() }
        if ($null -ne $weChatAppSecret) { $weChatAppSecret.Dispose() }
        throw 'CredentialStorageReadFailed'
    } finally {
        if ($null -ne $plainBytes) { [Array]::Clear($plainBytes, 0, $plainBytes.Length) }
        $payload = $null
    }
}

function Get-LocalBackendStartupCredentials {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$RepoRoot)
    $databasePassword = $null
    $weChatAppSecret = $null
    $ownershipTransferred = $false
    try {
        $path = Get-CredentialStoragePath $RepoRoot
        $null = Get-LocalBackendAppId -RepoRoot $RepoRoot
        if (Test-Path -LiteralPath $path) {
            $credentials = Get-LocalBackendCredentials -RepoRoot $RepoRoot
            $databasePassword = $credentials.DatabasePassword
            $weChatAppSecret = $credentials.WeChatAppSecret
            Write-Host 'Reusing current-user encrypted local credentials.'
        } else {
            Write-Host 'Local credentials will be saved encrypted for this Windows user. Authentication is not validated by saving.'
            $databasePassword = Read-Host 'Development DB password for account_book_dev_app (hidden)' -AsSecureString
            $weChatAppSecret = Read-Host 'WeChat AppSecret (hidden)' -AsSecureString
            $null = Save-LocalBackendCredentials -RepoRoot $RepoRoot -DatabasePassword $databasePassword -WeChatAppSecret $weChatAppSecret
            Write-Host 'Saved current-user encrypted local credentials.'
        }
        # The startup caller owns and disposes these returned SecureStrings.
        $ownershipTransferred = $true
        return [pscustomobject]@{ DatabasePassword = $databasePassword; WeChatAppSecret = $weChatAppSecret }
    } catch { throw 'CredentialSetupFailed' }
    finally {
        if (-not $ownershipTransferred) {
            if ($null -ne $databasePassword) { $databasePassword.Dispose() }
            if ($null -ne $weChatAppSecret) { $weChatAppSecret.Dispose() }
        }
    }
}

Export-ModuleMember -Function Save-LocalBackendCredentials, Get-LocalBackendCredentials, Get-LocalBackendStartupCredentials
