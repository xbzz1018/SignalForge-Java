[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'infra-common.ps1')
& $signalforgeDocker info --format '{{.ServerVersion}}'
if ($LASTEXITCODE -ne 0) { throw 'Start Docker Desktop in Linux container mode, then run this script again.' }
& $signalforgeDocker @signalforgeComposeArgs config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Compose configuration is invalid.' }
& $signalforgeDocker @signalforgeComposeArgs up --detach --wait --wait-timeout 180
if ($LASTEXITCODE -ne 0) { throw 'Infrastructure startup failed. Check Docker Desktop and compose service logs.' }
& (Join-Path $PSScriptRoot 'check-infra.ps1')
