$ErrorActionPreference = 'Stop'

$systemdDirectory = Split-Path -Parent $PSScriptRoot
$servicePath = Join-Path $systemdDirectory 'account-book.service'

if (-not (Test-Path -LiteralPath $servicePath -PathType Leaf)) {
    throw "Missing systemd unit: $servicePath"
}

$sections = @{}
$currentSection = $null

foreach ($rawLine in Get-Content -LiteralPath $servicePath) {
    $line = $rawLine.Trim()
    if ($line.Length -eq 0 -or $line.StartsWith('#') -or $line.StartsWith(';')) {
        continue
    }

    if ($line -match '^\[([^\]]+)\]$') {
        $currentSection = $Matches[1]
        if (-not $sections.ContainsKey($currentSection)) {
            $sections[$currentSection] = @{}
        }
        continue
    }

    if ($null -eq $currentSection -or $line -notmatch '^([^=]+)=(.*)$') {
        throw "Invalid systemd unit line: $rawLine"
    }

    $key = $Matches[1].Trim()
    $value = $Matches[2].Trim()
    if (-not $sections[$currentSection].ContainsKey($key)) {
        $sections[$currentSection][$key] = @()
    }
    $sections[$currentSection][$key] += $value
}

function Get-SingleValue {
    param(
        [Parameter(Mandatory)]
        [string]$Section,

        [Parameter(Mandatory)]
        [string]$Key
    )

    if (-not $sections.ContainsKey($Section) -or -not $sections[$Section].ContainsKey($Key)) {
        throw "Missing [$Section] $Key setting."
    }

    $values = @($sections[$Section][$Key])
    if ($values.Count -ne 1) {
        throw "Expected one [$Section] $Key setting, found $($values.Count)."
    }
    return $values[0]
}

function Assert-Equal {
    param(
        [Parameter(Mandatory)]
        [string]$Actual,

        [Parameter(Mandatory)]
        [string]$Expected,

        [Parameter(Mandatory)]
        [string]$Message
    )

    if ($Actual -cne $Expected) {
        throw "$Message Expected '$Expected', got '$Actual'."
    }
}

function Assert-ContainsValue {
    param(
        [Parameter(Mandatory)]
        [string]$Section,

        [Parameter(Mandatory)]
        [string]$Key,

        [Parameter(Mandatory)]
        [string]$Expected
    )

    if (-not $sections.ContainsKey($Section) `
            -or -not $sections[$Section].ContainsKey($Key) `
            -or -not (@($sections[$Section][$Key]) -ccontains $Expected)) {
        throw "Missing [$Section] $Key=$Expected setting."
    }
}

Assert-Equal (Get-SingleValue Service User) 'accountbook' 'The service must not run as root.'
Assert-Equal (Get-SingleValue Service Group) 'accountbook' 'The service must use its dedicated group.'
Assert-Equal (Get-SingleValue Service WorkingDirectory) '/opt/account-book' 'The working directory is outside the owned application tree.'
Assert-Equal (Get-SingleValue Service EnvironmentFile) '/etc/account-book/account-book.env' 'Credentials must come from the restricted server environment file.'
Assert-ContainsValue Service Environment 'SPRING_PROFILES_ACTIVE=prod'
Assert-ContainsValue Service Environment 'SERVER_ADDRESS=127.0.0.1'
Assert-ContainsValue Service Environment 'SERVER_PORT=7631'
Assert-ContainsValue Service ExecStartPre '/usr/bin/test -L /opt/account-book/current.jar'
Assert-ContainsValue Service ExecStartPre '/usr/bin/test -r /opt/account-book/current.jar'

$execStart = Get-SingleValue Service ExecStart
if ($execStart -cne '/usr/bin/java -Xms256m -Xmx768m -XX:+UseG1GC -jar /opt/account-book/current.jar') {
    throw 'ExecStart must run the JAR through the current release link without a shell.'
}
if ($execStart -match '(?i)(/bin/(ba)?sh|\s-c\s|\$\(|`|>|<|\|)') {
    throw 'ExecStart must not invoke a shell or contain shell metacharacters.'
}

Assert-Equal (Get-SingleValue Service Restart) 'on-failure' 'Only failures may trigger a restart.'
Assert-Equal (Get-SingleValue Service RestartSec) '10s' 'Restart attempts must be delayed.'
Assert-Equal (Get-SingleValue Unit StartLimitIntervalSec) '300s' 'The restart window must be bounded.'
Assert-Equal (Get-SingleValue Unit StartLimitBurst) '3' 'The restart burst must be bounded.'
Assert-Equal (Get-SingleValue Service TimeoutStopSec) '40s' 'Graceful shutdown must have enough time to complete.'

Assert-Equal (Get-SingleValue Service NoNewPrivileges) 'true' 'Privilege escalation must be disabled.'
Assert-Equal (Get-SingleValue Service PrivateTmp) 'true' 'The service must use a private temporary directory.'
Assert-Equal (Get-SingleValue Service ProtectSystem) 'strict' 'The system filesystem must be read-only to the service.'
Assert-Equal (Get-SingleValue Service ProtectHome) 'true' 'Home directories must be hidden from the service.'
Assert-Equal (Get-SingleValue Service UMask) '0027' 'Files created by the service must not be world-readable.'
Assert-Equal (Get-SingleValue Service StandardOutput) 'journal' 'Application output must be managed by journald.'
Assert-Equal (Get-SingleValue Service StandardError) 'journal' 'Application errors must be managed by journald.'

Write-Output 'systemd service isolation and restart-policy checks passed.'
