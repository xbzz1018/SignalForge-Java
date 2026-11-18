[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$signalforgeRoot = Split-Path -Parent $PSScriptRoot
$signalforgeEnvFile = Join-Path $signalforgeRoot '.env'
if (-not (Test-Path -LiteralPath $signalforgeEnvFile)) {
    $password = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
    $neo4jPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
    @('POSTGRES_DB=signalforge_java', 'POSTGRES_USER=signalforge', 'POSTGRES_PORT=45432',
      "POSTGRES_PASSWORD=$password", "NEO4J_PASSWORD=$neo4jPassword", 'REDIS_PORT=46379',
      'NEO4J_HTTP_PORT=47474', 'NEO4J_BOLT_PORT=47687', 'SIGNALFORGE_BACKEND_PORT=58870') |
        Set-Content -LiteralPath $signalforgeEnvFile -Encoding utf8NoBOM
}
$signalforgeText = [IO.File]::ReadAllText($signalforgeEnvFile)
$signalforgeDefaults = [ordered]@{
    SIGNALFORGE_DEV_USERNAME = 'developer'
    SIGNALFORGE_DEV_PASSWORD = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
    SIGNALFORGE_AGENT_TOKEN = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
    SIGNALFORGE_BOOTSTRAP_ENABLED = 'true'
    SIGNALFORGE_ALLOW_FIXTURES = 'false'
}
foreach ($signalforgeEntry in $signalforgeDefaults.GetEnumerator()) {
    if ($signalforgeText -notmatch ("(?m)^" + [regex]::Escape($signalforgeEntry.Key) + '=')) {
        $signalforgeText = $signalforgeText.TrimEnd() + "`n" + $signalforgeEntry.Key + '=' + $signalforgeEntry.Value + "`n"
    } elseif ($signalforgeEntry.Key -eq 'SIGNALFORGE_AGENT_TOKEN') {
        $signalforgeTokenLine = [regex]::Match($signalforgeText, '(?m)^SIGNALFORGE_AGENT_TOKEN=([^\r\n]*)')
        if ($signalforgeTokenLine.Groups[1].Value.Trim() -in @('', '""', "''")) {
            $signalforgeText = $signalforgeText.Remove($signalforgeTokenLine.Index, $signalforgeTokenLine.Length).Insert(
                $signalforgeTokenLine.Index, 'SIGNALFORGE_AGENT_TOKEN=' + $signalforgeEntry.Value)
        }
    }
}
[IO.File]::WriteAllText($signalforgeEnvFile, $signalforgeText, [Text.UTF8Encoding]::new($false))
Write-Host 'Local development credentials are ready in .env. Existing database passwords were preserved.'
