param(
    [string]$GatewayBaseUrl = $env:GATEWAY_BASE_URL,
    [string]$AdminToken = $env:GATESHIELD_ADMIN_TOKEN
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($GatewayBaseUrl)) {
    $GatewayBaseUrl = "http://localhost:8080"
}

if ([string]::IsNullOrWhiteSpace($AdminToken)) {
    $AdminToken = "change-me"
}

$headers = @{ "X-Admin-Token" = $AdminToken }
$tenantId = "smoke-" + ([guid]::NewGuid().ToString("N").Substring(0, 12))
$apiKey = "gs_smoke_" + ([guid]::NewGuid().ToString("N"))

function Invoke-Json {
    param(
        [string]$Method,
        [string]$Url,
        [hashtable]$Headers,
        [object]$Body
    )

    $json = $null
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 8
    }

    return Invoke-RestMethod -Method $Method -Uri $Url -Headers $Headers -ContentType "application/json" -Body $json
}

Write-Host "Checking actuator health..."
Invoke-RestMethod "$GatewayBaseUrl/actuator/health" | Out-Null

Write-Host "Checking admin health..."
Invoke-RestMethod "$GatewayBaseUrl/admin/health" | Out-Null

Write-Host "Creating tenant $tenantId..."
Invoke-Json -Method "POST" -Url "$GatewayBaseUrl/admin/tenants" -Headers $headers -Body @{
    id = $tenantId
    name = "Smoke Tenant"
    apiKey = $apiKey
    planName = "smoke"
    enabled = $true
} | Out-Null

Write-Host "Creating or updating route mock-api..."
$routeBody = @{
    routeId = "mock-api"
    pathPattern = "/api/v1/**"
    targetUrl = "http://mock-backend:8081"
    allowedMethods = @("GET")
    enabled = $true
    rateLimitRequests = 3
    rateLimitWindowSeconds = 60
}

try {
    Invoke-Json -Method "POST" -Url "$GatewayBaseUrl/admin/routes" -Headers $headers -Body $routeBody | Out-Null
} catch {
    Invoke-Json -Method "PUT" -Url "$GatewayBaseUrl/admin/routes/mock-api" -Headers $headers -Body $routeBody | Out-Null
}

Write-Host "Verifying unauthorized request is blocked..."
try {
    Invoke-WebRequest -Uri "$GatewayBaseUrl/api/v1/hello" -Method GET -ErrorAction Stop | Out-Null
    throw "Expected unauthorized request to fail"
} catch {
    if ($_.Exception.Response.StatusCode.value__ -ne 401) {
        throw
    }
}

Write-Host "Verifying proxied request succeeds..."
$clientHeaders = @{ "X-API-Key" = $apiKey }
$status = & curl.exe -s -o NUL -w "%{http_code}" "$GatewayBaseUrl/api/v1/hello" -H "X-API-Key: $apiKey"
if ($status -ne "200") {
    throw "Expected 200 from proxied request, got $status"
}

Write-Host "Verifying rate limit eventually returns 429..."
$saw429 = $false
for ($i = 0; $i -lt 80; $i++) {
    $status = & curl.exe -s -o NUL -w "%{http_code}" "$GatewayBaseUrl/api/v1/hello" -H "X-API-Key: $apiKey"
    if ($status -eq "429") {
        $saw429 = $true
        break
    }
}

if (-not $saw429) {
    throw "Expected rate limit to return 429"
}

Write-Host "GateShield smoke test passed."
