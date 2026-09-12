[CmdletBinding()]
param(
    [string]$Server = '117.72.101.42',
    [string]$SshUser = 'root',
    [ValidateRange(1, 65535)] [int]$SshPort = 22,
    [ValidateSet('local', 'https')] [string]$Mode = 'local',
    [string]$Domain = 'www.cr-chenny.com',
    [string]$NginxRoot = '/opt/nginx',
    [switch]$Apply,
    [string]$ConfirmText
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
function Fail([string]$Message) { throw $Message }
if ($Server -notmatch '^([0-9]{1,3}\.){3}[0-9]{1,3}$') { Fail 'Server must be an IPv4 address.' }
$serverOctets = $Server.Split('.')
foreach ($octet in $serverOctets) { if ([int]$octet -gt 255) { Fail 'Server IPv4 address is invalid.' } }
if ($SshUser -notmatch '^[A-Za-z_][A-Za-z0-9_.-]{0,31}$') { Fail 'SshUser contains unsafe characters.' }
if ($Domain -notmatch '^[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$' -or $Domain.Contains('..')) { Fail 'Domain is invalid.' }
if ($NginxRoot -notmatch '^/[A-Za-z0-9._/-]+$' -or $NginxRoot -match '(^|/)\.{1,2}(/|$)' -or $NginxRoot.Contains('//') -or $NginxRoot.EndsWith('/')) { Fail 'NginxRoot must be a normalized absolute path.' }
if ($NginxRoot -eq '/') { Fail 'NginxRoot may not be filesystem root.' }

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$installer = Join-Path $PSScriptRoot 'install-nginx-prod.sh'
$templates = Join-Path $repoRoot 'deploy\nginx'
foreach ($path in @($installer, (Join-Path $templates 'account-book-http.conf.template'), (Join-Path $templates 'account-book-https.conf.template'), (Join-Path $templates 'account-book-proxy.inc'))) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { Fail "Required file missing: $path" }
}

$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
$stage = "/tmp/mytallybook-nginx-$stamp"
$target = "$SshUser@$Server"
$remote = "set -Eeuo pipefail; sudo bash '$stage/install-nginx-prod.sh' --template-dir '$stage/templates' --domain '$Domain' --nginx-root '$NginxRoot' --mode '$Mode' --server-ip '$Server'"

Write-Host 'MyTallyBook Nginx installation (dry-run by default)'
Write-Host "Server:  $target"
Write-Host "Mode:    $Mode"
Write-Host "Domain:  $Domain"
Write-Host "Root:    $NginxRoot"
Write-Host "Planned: ssh -p $SshPort $target mkdir -p $stage/templates"
Write-Host "Planned: scp -P $SshPort installer and deploy/nginx templates to $stage"
Write-Host "Planned: ssh -p $SshPort $target to run the installer"
if (-not $Apply) {
    Write-Host 'Dry-run complete. No network connection, package install, file write or service change was attempted.'
    return
}
if ($ConfirmText -cne 'NGINX-INSTALL') { Fail 'Explicit -ConfirmText NGINX-INSTALL is required for -Apply.' }

& ssh -p $SshPort -- $target "set -Eeuo pipefail; mkdir -p '$stage/templates'"
if ($LASTEXITCODE -ne 0) { Fail 'Remote staging directory creation failed.' }
& scp -P $SshPort -- $installer ($target + ':' + $stage + '/install-nginx-prod.sh')
if ($LASTEXITCODE -ne 0) { Fail 'Nginx installer upload failed.' }
foreach ($template in Get-ChildItem -LiteralPath $templates -File) {
    & scp -P $SshPort -- $template.FullName ($target + ':' + $stage + '/templates/' + $template.Name)
    if ($LASTEXITCODE -ne 0) { Fail "Template upload failed: $($template.Name)" }
}
& ssh -p $SshPort -- $target ($remote + ' --apply --confirm NGINX-INSTALL')
if ($LASTEXITCODE -ne 0) { Fail 'Remote Nginx installer failed.' }
Write-Host 'Nginx installation/configuration completed. Inspect nginx -t, systemd and health output before public acceptance.'
