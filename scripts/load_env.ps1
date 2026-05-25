param(
    [string]$Path = ".env"
)

if (-not (Test-Path -LiteralPath $Path)) {
    Write-Error "Env file not found: $Path"
    exit 1
}

Get-Content -LiteralPath $Path | ForEach-Object {
    $line = $_.Trim()
    if (-not $line -or $line.StartsWith("#") -or -not $line.Contains("=")) {
        return
    }

    $key, $value = $line.Split("=", 2)
    $key = $key.Trim()
    $value = $value.Trim().Trim('"').Trim("'")
    Set-Item -Path "Env:$key" -Value $value
}

Write-Host "Loaded environment variables from $Path"
