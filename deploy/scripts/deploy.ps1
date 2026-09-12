[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$JarPath,
    [string]$Server = '117.72.101.42',
    [string]$SshUser = 'root',
    [ValidateRange(1, 65535)]
    [int]$SshPort = 22,
    [ValidatePattern('^/[A-Za-z0-9._/-]+$')]
    [string]$ReleaseRoot = '/opt/account-book',
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$')]
    [string]$Version,
    [switch]$Apply,
    [string]$ConfirmText
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
function Fail([string]$Message) { throw $Message }

if ($ReleaseRoot -match '(^|/)\.{1,2}(/|$)' -or $ReleaseRoot.Contains('//') -or $ReleaseRoot.EndsWith('/')) {
    Fail 'ReleaseRoot must be a normalized absolute application directory without dot segments.'
}

$jar = [IO.Path]::GetFullPath($JarPath)
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { Fail 'JAR file does not exist.' }
if ([IO.Path]::GetExtension($jar) -ine '.jar') { Fail 'Artifact must have a .jar extension.' }
$hash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
if ([string]::IsNullOrWhiteSpace($Version)) {
    $Version = 'account-book-' + (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss') + '-' + $hash.Substring(0, 12)
}
$artifactName = "$Version.jar"
$remoteTmp = "/tmp/$artifactName"
$remoteSha = "/tmp/$artifactName.sha256"
$remoteInstaller = "/tmp/account-book-install-release.sh"
$remoteBackup = "/tmp/account-book-backup-mysql.sh"
$installer = Join-Path $PSScriptRoot 'install-release.sh'
$backupScript = Join-Path $PSScriptRoot 'backup-mysql.sh'
$shaTemp = Join-Path ([IO.Path]::GetTempPath()) ([IO.Path]::GetRandomFileName())
$target = "$SshUser@$Server"
try {
    Set-Content -LiteralPath $shaTemp -Value "$hash  $artifactName" -NoNewline -Encoding ascii
    $remote = "set -Eeuo pipefail; sudo install -m 0750 '$remoteBackup' /usr/local/sbin/account-book-backup-mysql; sudo /usr/local/sbin/account-book-backup-mysql; sudo install -m 0750 '$remoteInstaller' /usr/local/sbin/account-book-install-release; sudo /usr/local/sbin/account-book-install-release --artifact '$remoteTmp' --sha256 '$remoteSha' --release-root '$ReleaseRoot'"
    Write-Host 'MyTallyBook release preparation (dry-run by default)'
    Write-Host "Artifact: $jar"
    Write-Host "SHA-256:  $hash"
    Write-Host "Remote:   ${target}:$ReleaseRoot/releases/$artifactName"
    Write-Host "Planned:  scp -P $SshPort `"$jar`" `"$target`:$remoteTmp`""
    Write-Host "Planned:  scp -P $SshPort `"$shaTemp`" `"$target`:$remoteSha`""
    Write-Host "Planned:  scp -P $SshPort `"$installer`" `"$target`:$remoteInstaller`""
    Write-Host "Planned:  scp -P $SshPort `"$backupScript`" `"$target`:$remoteBackup`""
    Write-Host "Planned:  ssh -p $SshPort $target `"$remote`""
    if (-not $Apply) {
        Write-Host 'Dry-run complete. No network connection, backup, service restart, or remote write was attempted.'
        return
    }
    if ($Server -eq '__API_DOMAIN__' -or $Server -match '^(localhost|127\.0\.0\.1)$') { Fail 'A real server host is required for -Apply.' }
    if ($ConfirmText -cne 'DEPLOY') { Fail 'Explicit -ConfirmText DEPLOY is required for -Apply.' }
    if ($SshUser -match '[\s`"'';|&<>]') { Fail 'SshUser contains unsafe characters.' }
    if ($Server -match '[\s`"'';|&<>/]') { Fail 'Server contains unsafe characters.' }
    & scp -P $SshPort -- $jar "$target`:$remoteTmp"
    if ($LASTEXITCODE -ne 0) { Fail 'Artifact upload failed.' }
    & scp -P $SshPort -- $shaTemp "$target`:$remoteSha"
    if ($LASTEXITCODE -ne 0) { Fail 'Checksum upload failed.' }
    & scp -P $SshPort -- $installer "$target`:$remoteInstaller"
    if ($LASTEXITCODE -ne 0) { Fail 'Installer upload failed.' }
    & scp -P $SshPort -- $backupScript "$target`:$remoteBackup"
    if ($LASTEXITCODE -ne 0) { Fail 'Backup script upload failed.' }
    & ssh -p $SshPort -- $target $remote
    if ($LASTEXITCODE -ne 0) { Fail 'Remote release installer failed.' }
    Write-Host 'Release command completed. Inspect systemd, local health, HTTPS, and logs before declaring success.'
}
finally {
    Remove-Item -LiteralPath $shaTemp -Force -ErrorAction SilentlyContinue
}
