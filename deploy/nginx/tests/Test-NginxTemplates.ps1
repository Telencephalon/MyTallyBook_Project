$ErrorActionPreference = 'Stop'

$nginxDirectory = Split-Path -Parent $PSScriptRoot
$httpTemplate = Get-Content -Raw -LiteralPath (
    Join-Path $nginxDirectory 'account-book-http.conf.template'
)
$httpsTemplate = Get-Content -Raw -LiteralPath (
    Join-Path $nginxDirectory 'account-book-https.conf.template'
)
$proxySnippet = Get-Content -Raw -LiteralPath (
    Join-Path $nginxDirectory 'account-book-proxy.inc'
)

function Assert-False {
    param(
        [Parameter(Mandatory)]
        [bool]$Condition,

        [Parameter(Mandatory)]
        [string]$Message
    )

    if ($Condition) {
        throw $Message
    }
}

function Assert-True {
    param(
        [Parameter(Mandatory)]
        [bool]$Condition,

        [Parameter(Mandatory)]
        [string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

$publicPort80Pattern = '(?m)^\s*listen\s+(?:0\.0\.0\.0:)?80(?:\s|;)|^\s*listen\s+\[::\]:80(?:\s|;)'

Assert-False `
    -Condition ([regex]::IsMatch($httpTemplate, $publicPort80Pattern)) `
    -Message 'The pre-HTTPS template must not claim the existing public port 80.'

Assert-False `
    -Condition ([regex]::IsMatch($httpsTemplate, $publicPort80Pattern)) `
    -Message 'The HTTPS template must not claim the existing public port 80.'

Assert-True `
    -Condition ($httpTemplate.Contains('listen 127.0.0.1:8081;')) `
    -Message 'The pre-HTTPS template must keep a loopback-only validation listener.'

Assert-True `
    -Condition ([regex]::IsMatch($httpsTemplate, '(?m)^\s*listen\s+443\s+ssl')) `
    -Message 'The production template must provide the public HTTPS listener on port 443.'

Assert-True `
    -Condition ($proxySnippet.Contains('proxy_pass http://127.0.0.1:7631;')) `
    -Message 'The proxy snippet must target the loopback-only Spring Boot port 7631.'

Write-Output 'Nginx template isolation checks passed.'
