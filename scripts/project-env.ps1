# Load local configuration as data. Keep shared infrastructure keys scoped to this checkout.
$signalforgeRoot = Split-Path -Parent $PSScriptRoot
$signalforgeEnvFile = Join-Path $signalforgeRoot '.env'
if (-not (Test-Path -LiteralPath $signalforgeEnvFile)) { throw 'Run scripts/init-dev.ps1 first.' }
function Read-SignalForgeLocalEnv([string]$File) {
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $File -Encoding utf8) {
        if ($line -match '^\s*(#|$)') { continue }
        $pair = $line.Split('=', 2)
        if ($pair.Count -ne 2) { throw 'Invalid local .env line.' }
        $value = $pair[1].Trim()
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values[$pair[0].Trim()] = $value
    }
    return $values
}

function Import-SignalForgeFrontendEnv {
    $settings = Read-SignalForgeLocalEnv (Join-Path $signalforgeRoot 'frontend\.env')
    $database = $null
    $postgresPort = if ($env:POSTGRES_PORT) { [int]$env:POSTGRES_PORT } else { 45432 }
    if (-not [Uri]::TryCreate($settings['DATABASE_URL'], [UriKind]::Absolute, [ref]$database) -or
        $database.Scheme -notin @('postgres', 'postgresql') -or $database.Host -notin @('localhost', '127.0.0.1') -or
        $database.Port -ne $postgresPort -or $database.AbsolutePath -notin @('/signalforge_java_web', '/signalforge_web')) {
        throw 'Frontend DATABASE_URL must select the dedicated Java web database on the project PostgreSQL port.'
    }
    foreach ($key in @('DATABASE_URL', 'PROJECTS_DIR', 'ENCRYPTION_KEY')) {
        [Environment]::SetEnvironmentVariable($key, $settings[$key], 'Process')
    }
    $redisPort = if ($env:REDIS_PORT) { [int]$env:REDIS_PORT } else { 46379 }
    $env:REDIS_URL = "redis://127.0.0.1:$redisPort"
    [Environment]::SetEnvironmentVariable('CLICKHOUSE_URL', '', 'Process')
}

$signalforgeSettings = Read-SignalForgeLocalEnv $signalforgeEnvFile
foreach ($signalforgeKey in @('POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD', 'POSTGRES_PORT', 'REDIS_PORT', 'NEO4J_HTTP_PORT', 'NEO4J_BOLT_PORT', 'NEO4J_PASSWORD', 'SIGNALFORGE_BACKEND_PORT')) {
    [Environment]::SetEnvironmentVariable($signalforgeKey, $signalforgeSettings[$signalforgeKey], 'Process')
}
$signalforgeDbPort = if ($env:POSTGRES_PORT) { [int]$env:POSTGRES_PORT } else { 45432 }
$signalforgeDbName = if ($env:POSTGRES_DB) { $env:POSTGRES_DB } else { 'signalforge_java' }
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:$signalforgeDbPort/$signalforgeDbName"
$env:SPRING_DATASOURCE_USERNAME = if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { 'signalforge' }
$env:SPRING_DATASOURCE_PASSWORD = $env:POSTGRES_PASSWORD
foreach ($signalforgeKey in $signalforgeSettings.Keys) {
    if ($signalforgeKey -notmatch '^SIGNALFORGE_[A-Z_]+$') { continue }
    if ($signalforgeKey -in @('SIGNALFORGE_DEV_USERNAME', 'SIGNALFORGE_DEV_PASSWORD', 'SIGNALFORGE_AGENT_TOKEN') -or
        $null -eq [Environment]::GetEnvironmentVariable($signalforgeKey, 'Process')) {
        [Environment]::SetEnvironmentVariable($signalforgeKey, $signalforgeSettings[$signalforgeKey], 'Process')
    }
}
