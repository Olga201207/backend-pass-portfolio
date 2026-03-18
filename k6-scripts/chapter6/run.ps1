param(
    [string]$Script   = "hot-key",
    [string]$Strategy = "before"
)

# .env 파일이 있으면 자동 로드
$EnvFile = Join-Path $PSScriptRoot "../.env"
if (Test-Path $EnvFile) {
    Get-Content $EnvFile | Where-Object { $_ -notmatch '^#' -and $_ -match '=' } | ForEach-Object {
        $parts = $_ -split '=', 2
        [System.Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), 'Process')
    }
}

$env:STRATEGY = $Strategy
$Date = Get-Date -Format "yyyy-MM-dd_HH-mm"
New-Item -ItemType Directory -Force -Path "results" | Out-Null
Write-Host "▶ Running: $Script-test.js (STRATEGY=$Strategy)"
k6 run "$Script-test.js" --summary-export "results/$Script-$Strategy-$Date.json"
