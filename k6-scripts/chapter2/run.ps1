param([string]$Script = "board-load")
$Date = Get-Date -Format "yyyy-MM-dd_HH-mm"
New-Item -ItemType Directory -Force -Path "results" | Out-Null
Write-Host "▶ Running: $Script-test.js"
k6 run "$Script-test.js" --summary-export "results/$Script-$Date.json"
