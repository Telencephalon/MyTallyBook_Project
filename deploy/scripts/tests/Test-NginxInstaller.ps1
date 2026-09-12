$ErrorActionPreference = 'Stop'
$scriptsRoot = Split-Path -Parent $PSScriptRoot
$script = Get-Content -Raw -LiteralPath (Join-Path $scriptsRoot 'install-nginx-prod.sh')
$wrapper = Get-Content -Raw -LiteralPath (Join-Path $scriptsRoot 'Install-NginxProd.ps1')
function Assert-True([bool]$condition, [string]$message) { if (-not $condition) { throw $message } }
Assert-True ($script.StartsWith('#!/usr/bin/env bash')) 'installer must be bash.'
Assert-True ($script -match 'NGINX_ROOT="/opt/nginx"') 'default /opt/nginx root is required.'
Assert-True ($script -match 'DOMAIN="www\.cr-chenny\.com"') 'requested domain must be default.'
Assert-True ($script -match 'SERVER_IP="117\.72\.101\.42"') 'server IP must be default.'
Assert-True ($script -match 'NGINX-INSTALL') 'explicit apply confirmation is required.'
Assert-True ($script -match 'TEMPLATE_DIR_OVERRIDE') 'standalone template override is required.'
Assert-True ($script -match 'apt-get install -y.*nginx') 'Ubuntu package installation is required.'
Assert-True ($script -notmatch '(?m)^\s*listen\s+80') 'installer must not add a port 80 listener.'
Assert-True ($script -match '127\.0\.0\.1:8081') 'local mode must remain loopback-only.'
Assert-True ($script -match 'nginx -t') 'syntax validation is required.'
Assert-True ($script -match 'rollback') 'failed installation must have rollback.'
Assert-True ($script -match 'if \(\( \$# > 0 \)\); then rc="\$1"') 'explicit failures must preserve nonzero rollback status.'
Assert-True ($script -match 'DEFAULT_MOVED') 'default site must be restorable.'
Assert-True ($script -match 'curl --resolve.*--max-time') 'HTTPS health check must target local listener with total timeout.'
Assert-True ($script -match 'fullchain\.pem.*privkey\.pem') 'HTTPS mode must require certificates.'
Assert-True (Test-Path -LiteralPath (Join-Path $scriptsRoot 'Install-NginxProd.ps1')) 'Windows upload wrapper is required.'
Assert-True ($wrapper -match 'Server = ''117\.72\.101\.42''') 'wrapper server default is required.'
Assert-True ($wrapper -match 'ConfirmText NGINX-INSTALL') 'wrapper confirmation gate is required.'
Write-Host 'Test-NginxInstaller: PASS'
