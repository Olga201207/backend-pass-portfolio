param([string]$Phase = "before")
$Date = Get-Date -Format "yyyy-MM-dd_HH-mm"
New-Item -ItemType Directory -Force -Path "results" | Out-Null
Write-Host "▶ Running: $Phase/order-test.js"
k6 run "$Phase/order-test.js" --summary-export "results/order-$Phase-$Date.json"
