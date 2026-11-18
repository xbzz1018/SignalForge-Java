# Use project-local Java tools when present, or an existing JDK 21 and Maven.
$signalforgeRoot = Split-Path -Parent $PSScriptRoot
$signalforgePrefix = Join-Path $signalforgeRoot '.runtime\conda'
$signalforgeLibrary = Join-Path $signalforgePrefix 'Library'
$signalforgeJava = Join-Path $signalforgeLibrary 'lib\jvm\bin\java.exe'
if (Test-Path -LiteralPath $signalforgeJava) {
    $env:JAVA_HOME = $signalforgeLibrary
    $env:PATH = (Join-Path $signalforgeLibrary 'bin') + ';' + $env:PATH
} else {
    $java = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
        Get-Item -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe')
    } else { Get-Command java.exe -ErrorAction SilentlyContinue }
    if (-not $java) { throw 'Install JDK 21 and make java.exe available on PATH.' }
    $signalforgeJava = if ($java -is [IO.FileInfo]) { $java.FullName } else { $java.Source }
}
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw 'Install Maven 3.9 and add it to PATH.' }
$env:MAVEN_OPTS = '-Dfile.encoding=UTF-8'
