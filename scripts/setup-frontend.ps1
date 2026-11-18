[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'project-env.ps1')
Push-Location (Join-Path $signalforgeRoot 'frontend')
try {
    & npm.cmd ci --ignore-scripts --no-audit --no-fund --prefer-offline
    if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency install failed.' }
    & (Join-Path $PSScriptRoot 'init-frontend.ps1')
    Import-SignalForgeFrontendEnv
    & node.exe --env-file=.env node_modules\prisma\build\index.js generate
    if ($LASTEXITCODE -ne 0) { throw 'Prisma client generation failed.' }
    & node.exe --env-file=.env node_modules\prisma\build\index.js migrate deploy
    if ($LASTEXITCODE -ne 0) { throw 'Dedicated frontend database migration failed.' }
    & node.exe --env-file=.env --import tsx ..\scripts\bootstrap-frontend.ts
    if ($LASTEXITCODE -ne 0) { throw 'Frontend account initialization failed.' }
} finally { Pop-Location }
