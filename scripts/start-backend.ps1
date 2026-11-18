[CmdletBinding()]
param([ValidateRange(0, 65535)][int]$Port = 0, [switch]$Demo, [switch]$NoBuild)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'enter-java.ps1')
. (Join-Path $PSScriptRoot 'project-env.ps1')
. (Join-Path $PSScriptRoot 'service-network.ps1')
$Port = Get-SignalForgeBackendPort -RequestedPort $Port
Assert-SignalForgePortAvailable -Port $Port -ServiceName 'Backend service'
if (-not $env:SIGNALFORGE_DEV_PASSWORD) { throw 'Run scripts/init-dev.ps1 first.' }
$env:SIGNALFORGE_BACKEND_PORT = "$Port"
if ($Demo) { $env:SIGNALFORGE_ALLOW_FIXTURES = 'true' }
$signalforgeJar = Join-Path $signalforgeRoot 'backend\target\signalforge-backend-0.2.0-SNAPSHOT-app.jar'
if (-not $NoBuild) {
    & mvn --batch-mode --no-transfer-progress --file (Join-Path $signalforgeRoot 'backend\pom.xml') "-Dmaven.repo.local=$signalforgeRoot\.cache\maven-repository" -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
}
if (-not (Test-Path -LiteralPath $signalforgeJar)) { throw 'Backend jar is missing. Start without -NoBuild.' }
Write-Host "Workspace: http://127.0.0.1:$Port"
& $signalforgeJava '-Dfile.encoding=UTF-8' -jar $signalforgeJar
if ($LASTEXITCODE -ne 0) { throw 'Backend exited with an error. Check the preceding service log.' }
