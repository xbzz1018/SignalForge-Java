# Shared local startup checks. Checking bindability also catches conflicts without a visible listener.
function Assert-SignalForgePortAvailable {
    param(
        [Parameter(Mandatory = $true)][ValidateRange(1, 65535)][int]$Port,
        [Parameter(Mandatory = $true)][string]$ServiceName
    )
    $signalforgeProbe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
    $signalforgeProbe.ExclusiveAddressUse = $true
    try {
        $signalforgeProbe.Start()
    } catch {
        $signalforgeCause = $_.Exception.GetBaseException()
        $signalforgeCode = if ($signalforgeCause -is [System.Net.Sockets.SocketException]) { $signalforgeCause.NativeErrorCode } else { 'unknown' }
        $signalforgeOwners = @()
        if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
            $signalforgeOwners = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
                Select-Object -ExpandProperty OwningProcess -Unique)
        }
        $signalforgeDetail = if ($signalforgeOwners.Count -gt 0) {
            "Listener PID(s): $($signalforgeOwners -join ', ')."
        } else {
            'No listener PID is visible; Windows still rejected a local bind.'
        }
        throw "$ServiceName cannot use 127.0.0.1:$Port (socket error $signalforgeCode). $signalforgeDetail Configure a free port."
    } finally {
        $signalforgeProbe.Stop()
    }
}

function Get-SignalForgeBackendPort {
    param([ValidateRange(0, 65535)][int]$RequestedPort)
    if ($RequestedPort -gt 0) { return $RequestedPort }
    if (-not $env:SIGNALFORGE_BACKEND_PORT) { return 58870 }
    $signalforgeParsedPort = 0
    if (-not [int]::TryParse($env:SIGNALFORGE_BACKEND_PORT, [ref]$signalforgeParsedPort) -or
        $signalforgeParsedPort -lt 1 -or $signalforgeParsedPort -gt 65535) {
        throw 'SIGNALFORGE_BACKEND_PORT must be an integer from 1 to 65535.'
    }
    return $signalforgeParsedPort
}
