$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'infra-common.ps1')
$frontendEnv = Join-Path $signalforgeRoot 'frontend\.env'
$postgresPort = if ($env:POSTGRES_PORT) { [int]$env:POSTGRES_PORT } else { 45432 }
$database = 'signalforge_java_web'
if (Test-Path -LiteralPath $frontendEnv) {
    Import-SignalForgeFrontendEnv
    Write-Host 'Frontend configuration and credentials preserved.'
    return
}

if (-not $env:SIGNALFORGE_DEV_USERNAME -or -not $env:SIGNALFORGE_DEV_PASSWORD) { throw 'Run scripts/init-dev.ps1 first.' }
$query = "SELECT 1 FROM pg_database WHERE datname='$database'"
$exists = & $signalforgeDocker @signalforgeComposeArgs exec -T postgres psql -X -At -v ON_ERROR_STOP=1 -U $env:POSTGRES_USER -d postgres -c $query
if ($LASTEXITCODE -ne 0) { throw 'Could not query the project PostgreSQL instance.' }
if ($exists -ne '1') {
    & $signalforgeDocker @signalforgeComposeArgs exec -T postgres psql -X -v ON_ERROR_STOP=1 -U $env:POSTGRES_USER -d postgres -c "CREATE DATABASE $database"
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the dedicated Java web database.' }
}
$pgUser = [Uri]::EscapeDataString($env:POSTGRES_USER)
$pgPassword = [Uri]::EscapeDataString($env:POSTGRES_PASSWORD)
$webUser = $env:SIGNALFORGE_DEV_USERNAME
$authSecret = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
$encryptionKey = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
$workspaces = Join-Path $signalforgeRoot '.runtime\frontend-workspaces'
@("DATABASE_URL=postgresql://${pgUser}:${pgPassword}@127.0.0.1:${postgresPort}/${database}?schema=public",
  "PROJECTS_DIR=$workspaces", "ENCRYPTION_KEY=$encryptionKey", 'SIGNALFORGE_AUTH_MODE=local',
  "SIGNALFORGE_AUTH_SECRET=$authSecret", "SIGNALFORGE_AUTH_ADMIN_EMAIL=$webUser@signalforge.local",
  "SIGNALFORGE_AUTH_ADMIN_PASSWORD=$env:SIGNALFORGE_DEV_PASSWORD", "SIGNALFORGE_AUTH_ADMIN_NAME=$webUser",
  'SIGNALFORGE_AUTH_ALLOW_SIGNUP=false', 'SIGNALFORGE_AUTH_SECURE_COOKIES=false',
  'BETTER_AUTH_URL=http://127.0.0.1:58872', 'SIGNALFORGE_AUTH_TRUSTED_ORIGINS=http://127.0.0.1:58872',
  'NEXT_PUBLIC_APP_URL=http://127.0.0.1:58872', "SIGNALFORGE_WEB_LOGIN=$webUser", "SIGNALFORGE_WEB_JAVA_USERNAME=$webUser",
  'SIGNALFORGE_JAVA_URL=http://127.0.0.1:58870', 'SIGNALFORGE_DATABASE_ENABLED=1',
  'SIGNALFORGE_MARKET_API_ENABLED=0', 'SIGNALFORGE_MEMORY_ENABLED=0', 'SIGNALFORGE_KNOWLEDGE_ENABLED=0',
  'SIGNALFORGE_MODELPORT_ENABLED=0', 'SIGNALFORGE_OBSERVABILITY_ENABLED=0', 'SIGNALFORGE_REDIS_CACHE_ENABLED=0',
  'SIGNALFORGE_MARKET_MAINTENANCE_ENABLED=0', 'SIGNALFORGE_ENABLE_INTERNAL_TOKEN_API=0',
  'SIGNALFORGE_LLM_AGENT_ENABLED=0', 'SIGNALFORGE_LLM_QUERY_REWRITE_ENABLED=0') |
  Set-Content -LiteralPath $frontendEnv -Encoding utf8NoBOM
Write-Host 'Created frontend/.env for the dedicated Java web database.'
