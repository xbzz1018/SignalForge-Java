[CmdletBinding()]
param([ValidateRange(1,65535)][int]$Port = 58872, [ValidateRange(0,65535)][int]$BackendPort = 0)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'project-env.ps1')
. (Join-Path $PSScriptRoot 'service-network.ps1')
Assert-SignalForgePortAvailable -Port $Port -ServiceName 'Next.js frontend'
$signalforgeFrontend = Join-Path $signalforgeRoot 'frontend'
$signalforgeNext = Join-Path $signalforgeFrontend 'node_modules\next\dist\bin\next'
if (-not (Test-Path -LiteralPath $signalforgeNext)) { throw 'Run scripts/setup-frontend.ps1 first.' }
if (-not (Test-Path -LiteralPath (Join-Path $signalforgeFrontend '.env'))) { throw 'Run scripts/setup-frontend.ps1 first.' }
Import-SignalForgeFrontendEnv
$env:BETTER_AUTH_URL = "http://127.0.0.1:$Port"
$env:NEXT_PUBLIC_APP_URL = "http://127.0.0.1:$Port"
$env:SIGNALFORGE_AUTH_TRUSTED_ORIGINS = "http://127.0.0.1:$Port"
$signalforgeJavaPort = Get-SignalForgeBackendPort -RequestedPort $BackendPort
$env:SIGNALFORGE_JAVA_URL = "http://127.0.0.1:$signalforgeJavaPort"
foreach ($feature in @('MARKET_API', 'MARKET_MAINTENANCE', 'MEMORY', 'KNOWLEDGE', 'MODELPORT', 'OBSERVABILITY')) {
    [Environment]::SetEnvironmentVariable("SIGNALFORGE_${feature}_ENABLED", '0', 'Process')
}
$env:SIGNALFORGE_MARKET_API_URL = $env:SIGNALFORGE_JAVA_URL
$env:SIGNALFORGE_MARKET_API_BASE_URL = $env:SIGNALFORGE_JAVA_URL
[Environment]::SetEnvironmentVariable('SIGNALFORGE_MARKET_ADMIN_TOKEN', $null, 'Process')
$env:NEXT_TELEMETRY_DISABLED = '1'
$env:CI = '1'
if ($Port -ne 58872) { $env:SIGNALFORGE_NEXT_DIST_DIR = ".next-dev-$Port" }
if (-not $env:NODE_OPTIONS) { $env:NODE_OPTIONS = '--max-old-space-size=3072' }
Write-Host "SignalForge Java workspace: http://127.0.0.1:$Port"
Push-Location $signalforgeFrontend
try {
    & node.exe $signalforgeNext dev --hostname 127.0.0.1 --port $Port --turbopack
    if ($LASTEXITCODE -ne 0) { throw 'Frontend exited with an error; inspect the preceding log.' }
} finally { Pop-Location }
