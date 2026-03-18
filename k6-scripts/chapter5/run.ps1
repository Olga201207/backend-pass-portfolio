param(
    [string]$Script   = "n-plus-one",
    [string]$Strategy = ""
)

# .env 파일이 있으면 자동 로드
$EnvFile = Join-Path $PSScriptRoot "../.env"
if (Test-Path $EnvFile) {
    Get-Content $EnvFile | Where-Object { $_ -notmatch '^#' -and $_ -match '=' } | ForEach-Object {
        $parts = $_ -split '=', 2
        [System.Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), 'Process')
    }
}

if ($Strategy) { $env:STRATEGY = $Strategy }

$Date = Get-Date -Format "yyyy-MM-dd_HH-mm"
New-Item -ItemType Directory -Force -Path "results" | Out-Null
$StrategyLabel = if ($Strategy) { $Strategy } else { "default" }
Write-Host "▶ Running: $Script-test.js (STRATEGY=$StrategyLabel)"
k6 run "$Script-test.js" --summary-export "results/$Script-$StrategyLabel-$Date.json"
