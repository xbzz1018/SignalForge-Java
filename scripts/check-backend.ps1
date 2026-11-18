[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'enter-java.ps1')
Push-Location $signalforgeRoot
try {
    & mvn --batch-mode --no-transfer-progress --file backend\pom.xml "-Dmaven.repo.local=$signalforgeRoot\.cache\maven-repository" verify
    if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed.' }
} finally { Pop-Location }
