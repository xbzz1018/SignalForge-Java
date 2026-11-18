[CmdletBinding()]
param([ValidateRange(1,65535)][int]$Port = 51939, [switch]$Live)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'enter-java.ps1')
. (Join-Path $PSScriptRoot 'project-env.ps1')
. (Join-Path $PSScriptRoot 'service-network.ps1')
Assert-SignalForgePortAvailable -Port $Port -ServiceName 'Java smoke service'
$base = "http://127.0.0.1:$Port"
$jar = Join-Path $signalforgeRoot 'backend\target\signalforge-backend-0.2.0-SNAPSHOT-app.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Build the backend with scripts/check-backend.ps1 first.' }
$logs = Join-Path $signalforgeRoot '.runtime\java-smoke'
$null = New-Item -ItemType Directory -Force -Path $logs
$env:SIGNALFORGE_BACKEND_PORT = "$Port"
$env:SIGNALFORGE_ALLOW_FIXTURES = (-not $Live).ToString().ToLowerInvariant()
$env:SIGNALFORGE_KNOWLEDGE_WORKER_ENABLED = 'false'
$env:SIGNALFORGE_EVIDENCE_WORKER_ENABLED = 'false'

function Assert-Check([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Start-SmokeJava([bool]$Worker, [string]$Label) {
    $env:SIGNALFORGE_WORKER_ENABLED = $Worker.ToString().ToLowerInvariant()
    $process = Start-Process -FilePath $signalforgeJava -ArgumentList "-Dfile.encoding=UTF-8 -jar `"$jar`"" `
        -WorkingDirectory $signalforgeRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logs "$Label.log") -RedirectStandardError (Join-Path $logs "$Label.err.log")
    $until = [DateTime]::UtcNow.AddSeconds(45)
    while ([DateTime]::UtcNow -lt $until -and -not $process.HasExited) {
        try {
            if ((Invoke-WebRequest "$base/api/health" -NoProxy -TimeoutSec 2).StatusCode -eq 200) { return $process }
        } catch { Start-Sleep -Milliseconds 250 }
    }
    if (-not $process.HasExited) { $process.Kill() }
    throw "Java smoke startup failed; inspect $logs."
}
function Login-Smoke {
    $null = Invoke-RestMethod "$base/api/auth/csrf" -NoProxy -SessionVariable smokeSession -TimeoutSec 10
    $csrf = Invoke-RestMethod "$base/api/auth/csrf" -NoProxy -WebSession $smokeSession -TimeoutSec 10
    $null = Invoke-WebRequest "$base/api/auth/login" -NoProxy -WebSession $smokeSession -TimeoutSec 10 -Method Post `
        -ContentType 'application/x-www-form-urlencoded' -Headers @{ $csrf.headerName = $csrf.token } `
        -Body @{ username = $env:SIGNALFORGE_DEV_USERNAME; password = $env:SIGNALFORGE_DEV_PASSWORD }
    $csrf = Invoke-RestMethod "$base/api/auth/csrf" -NoProxy -WebSession $smokeSession -TimeoutSec 10
    @{ Session = $smokeSession; Headers = @{ $csrf.headerName = $csrf.token } }
}
function Wait-SmokeResult([string]$TaskUri, $Session) {
    $until = [DateTime]::UtcNow.AddSeconds(50)
    do {
        $task = Invoke-RestMethod $TaskUri -NoProxy -WebSession $Session -TimeoutSec 10
        if ($task.state -eq 'SUCCEEDED') { return Invoke-RestMethod "$TaskUri/result" -NoProxy -WebSession $Session -TimeoutSec 10 }
        if ($task.state -in @('FAILED','CANCELLED')) { throw "Research ended in $($task.state): $($task.errorCode)" }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $until)
    throw 'Research did not complete in time.'
}

$javaProcess = $null
try {
    $javaProcess = Start-SmokeJava $false 'queued'
    Assert-Check ((Invoke-WebRequest "$base/api/me" -NoProxy -SkipHttpErrorCheck).StatusCode -eq 401) 'Expected authentication.'
    $login = Login-Smoke
    $project = (Invoke-RestMethod "$base/api/me" -NoProxy -WebSession $login.Session).projects |
        Where-Object role -eq OWNER | Select-Object -First 1
    Assert-Check ([bool]$project) 'No project with OWNER access.'
    $tasks = "$base/api/projects/$($project.id)/research/tasks"
    $request = Get-Content -LiteralPath (Join-Path $signalforgeRoot 'contracts\examples\risk-comparison.fixture.json') -Raw | ConvertFrom-Json -AsHashtable
    if ($Live) {
        $end = [DateTime]::UtcNow.Date.AddDays(-2)
        $request.securities = @(@{securityId='510300';market='SSE'}, @{securityId='510500';market='SSE'})
        $request.knowledgeAsOf = [DateTime]::UtcNow.ToString('o')
        $request.priceRange.start = $end.AddDays(-90).ToString('yyyy-MM-dd')
        $request.priceRange.end = $end.ToString('yyyy-MM-dd')
    }
    $json = $request | ConvertTo-Json -Depth 8 -Compress
    $submit = @{ Uri=$tasks; NoProxy=$true; WebSession=$login.Session; TimeoutSec=10; Method='Post'; ContentType='application/json'; Body=$json }
    Assert-Check ((Invoke-WebRequest @submit -SkipHttpErrorCheck).StatusCode -eq 403) 'Expected CSRF protection.'
    $login.Headers['Idempotency-Key'] = "java-smoke-$([guid]::NewGuid())"
    $created = Invoke-WebRequest @submit -Headers $login.Headers
    $id = ($created.Content | ConvertFrom-Json).id
    $uri = "$tasks/$id"
    $repeat = Invoke-WebRequest @submit -Headers $login.Headers
    Assert-Check ($created.StatusCode -eq 202 -and $repeat.StatusCode -eq 200 -and ($repeat.Content | ConvertFrom-Json).id -eq $id) 'Idempotent submission failed.'
    Assert-Check ((Invoke-RestMethod $uri -NoProxy -WebSession $login.Session).state -eq 'QUEUED') 'Expected a durable queued task.'
    $javaProcess.Kill(); $javaProcess.WaitForExit()
    $javaProcess = Start-SmokeJava $true 'resumed'
    $login = Login-Smoke
    $result = Wait-SmokeResult $uri $login.Session
    Assert-Check ($result.demoData -eq (-not $Live) -and $result.series.Count -eq 2) 'Invalid risk result sources.'
    if (-not $Live) {
        Assert-Check ([Math]::Abs($result.series[0].metrics.TOTAL_RETURN - 13.8005) -lt 1e-8) 'Unexpected fixture return.'
    }
    $snapshots = Invoke-RestMethod "$uri/snapshots" -NoProxy -WebSession $login.Session
    Assert-Check ($snapshots.Count -eq 2) 'Expected two input snapshots.'
    $events = (Invoke-WebRequest "$uri/events" -NoProxy -WebSession $login.Session -TimeoutSec 10 -Headers @{'Last-Event-ID'='1'}).Content
    Assert-Check ($events -match 'event:\s*SUCCEEDED' -and $events -notmatch 'event:\s*QUEUED') 'SSE replay failed.'
    $javaProcess.Kill(); $javaProcess.WaitForExit()
    $javaProcess = Start-SmokeJava $true 'restarted'
    $login = Login-Smoke
    $persisted = Invoke-RestMethod "$uri/result" -NoProxy -WebSession $login.Session
    Assert-Check (($persisted | ConvertTo-Json -Depth 25 -Compress) -ceq ($result | ConvertTo-Json -Depth 25 -Compress)) 'Persisted result changed after restart.'
    $submit.WebSession = $login.Session
    $login.Headers['Idempotency-Key'] = "java-reuse-$([guid]::NewGuid())"
    $reuse = Invoke-RestMethod @submit -Headers $login.Headers
    $reused = Wait-SmokeResult "$tasks/$($reuse.id)" $login.Session
    Assert-Check (@($reused.series | Where-Object fetchStatus -ne SNAPSHOT_REUSED).Count -eq 0) 'Stored snapshot was not reused.'
    Assert-Check (($reused.series.dataVersion -join ',') -ceq ($persisted.series.dataVersion -join ',')) 'Snapshot versions changed.'
    [ordered]@{ status='passed'; runtime='Java'; demoData=(-not $Live); taskId=$id; dataVersions=$result.series.dataVersion
        checks=@('authentication','csrf','idempotency','queuedTaskAfterRestart','riskMetrics','sseReplay','persistedResultAfterRestart','snapshotReuse') } |
        ConvertTo-Json -Depth 5
} finally {
    if ($javaProcess -and -not $javaProcess.HasExited) { $javaProcess.Kill(); $javaProcess.WaitForExit() }
}
