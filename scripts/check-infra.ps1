[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'infra-common.ps1')
& $signalforgeDocker @signalforgeComposeArgs exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT 1"'
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL query failed.' }
& $signalforgeDocker @signalforgeComposeArgs exec -T redis redis-cli ping
if ($LASTEXITCODE -ne 0) { throw 'Redis ping failed.' }
& $signalforgeDocker @signalforgeComposeArgs exec -T neo4j sh -c 'cypher-shell -a bolt://localhost:7687 -u neo4j -p "$SIGNALFORGE_NEO4J_PASSWORD" "RETURN 1 AS probe"'
if ($LASTEXITCODE -ne 0) { throw 'Neo4j query failed.' }
& $signalforgeDocker @signalforgeComposeArgs ps
if ($LASTEXITCODE -ne 0) { throw 'Compose status inspection failed.' }
