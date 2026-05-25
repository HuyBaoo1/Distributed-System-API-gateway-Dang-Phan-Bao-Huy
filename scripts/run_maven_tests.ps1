param(
    [string]$JavaHome = $env:JAVA_HOME
)

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$fallbackJavaHome = Join-Path $env:USERPROFILE ".jdks\openjdk-22.0.2"

if (-not $JavaHome -and (Test-Path -LiteralPath $fallbackJavaHome)) {
    $JavaHome = $fallbackJavaHome
}

if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome "bin\java.exe"))) {
    Write-Error "JAVA_HOME is not configured. Install JDK 17+ or pass -JavaHome C:\path\to\jdk."
    exit 1
}

$env:JAVA_HOME = $JavaHome
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

Write-Host "Using JAVA_HOME=$env:JAVA_HOME"

Push-Location (Join-Path $repoRoot "api-gateway")
try {
    .\mvnw.cmd test
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}
finally {
    Pop-Location
}

Push-Location (Join-Path $repoRoot "mock-backend-service\mock-backend-service")
try {
    .\mvnw.cmd test
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}
finally {
    Pop-Location
}
