$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'project-env.ps1')
$signalforgeDockerCommand = Get-Command docker.exe -ErrorAction SilentlyContinue
if (-not $signalforgeDockerCommand) { throw 'Install Docker Desktop and make docker.exe available on PATH.' }
$signalforgeDocker = $signalforgeDockerCommand.Source
$signalforgeComposeArgs = @('compose',  '--env-file', (Join-Path $signalforgeRoot '.env'), '--file', (Join-Path $signalforgeRoot 'compose.yml'))
