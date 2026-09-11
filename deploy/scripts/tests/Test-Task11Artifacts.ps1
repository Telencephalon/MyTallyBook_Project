$ErrorActionPreference = 'Stop'

$deployRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$repoRoot = Split-Path -Parent $deployRoot
$service = Get-Content -Raw -LiteralPath (Join-Path $deployRoot 'systemd\account-book.service')
$prod = Get-Content -Raw -LiteralPath (Join-Path $repoRoot 'account-book-server\src\main\resources\application-prod.yml')
$deploy = Get-Content -Raw -LiteralPath (Join-Path $deployRoot 'scripts\deploy.ps1')
$install = Get-Content -Raw -LiteralPath (Join-Path $deployRoot 'scripts\install-release.sh')
$backup = Get-Content -Raw -LiteralPath (Join-Path $deployRoot 'scripts\backup-mysql.sh')
$restorePath = Join-Path $deployRoot 'scripts\restore-mysql.md'

function Assert-True([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
}

Assert-True ($service -match '(?m)^User=accountbook$' -and $service -match '(?m)^Group=accountbook$') 'systemd must run as accountbook.'
Assert-True ($service -match '(?m)^EnvironmentFile=/etc/account-book/account-book\.env$') 'systemd must use the restricted environment file.'
Assert-True ($service -match '(?m)^ExecStart=.*-jar /opt/account-book/current\.jar$') 'systemd must run the atomic current.jar link.'
Assert-True ($service -match '(?m)^Environment=SERVER_ADDRESS=127\.0\.0\.1$' -and $service -match '(?m)^Environment=SERVER_PORT=7631$') 'systemd loopback/port contract is missing.'
Assert-True ($service -match '(?m)^Restart=on-failure$' -and $service -match '(?m)^RestartSec=') 'systemd restart policy is missing.'
Assert-True ($prod -match 'address: \$\{SERVER_ADDRESS:127\.0\.0\.1\}' -and $prod -match 'port: \$\{SERVER_PORT:7631\}') 'production profile must default to loopback:7631.'
Assert-True (Test-Path -LiteralPath $restorePath -PathType Leaf) 'Restore runbook is missing.'
Assert-True ($deploy -match 'Get-FileHash' -and $deploy -match 'Dry-run complete' -and $deploy -match 'ConfirmText') 'deploy.ps1 must hash artifacts, default to dry-run and require explicit confirmation.'
Assert-True ($install -match 'sha256sum -c' -and $install -match 'current\.jar' -and $install -match 'rollback') 'install-release.sh must verify, atomically switch and roll back.'
Assert-True ($backup -match 'defaults-extra-file' -and $backup -match 'gzip -n' -and $backup -match 'retention_days' -and $backup -match 'weekly_keep' -and $backup -match 'weekly_dir') 'backup-mysql.sh must use option-file credentials, compressed backups and daily/weekly retention.'
Assert-True (-not ($deploy -match '(?i)(password|secret|token)\s*=') -and -not ($backup -match '(?i)(password|secret|token)\s*=') ) 'Deployment scripts must not contain credential assignments.'

Write-Output 'Task 11 artifact safety checks passed.'
