[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'infra-common.ps1')
& $signalforgeDocker @signalforgeComposeArgs stop
if ($LASTEXITCODE -ne 0) { throw 'Infrastructure stop failed.' }
Write-Host 'Services stopped. Existing database volumes are preserved.'
