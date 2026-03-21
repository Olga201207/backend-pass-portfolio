# chapter6 전체 테스트 자동 실행 스크립트
# 실행: cd k6-scripts/chapter6 && .\run-all.ps1
# 옵션: .\run-all.ps1 -BaseUrl "http://your-ec2-ip:8080/api"

param(
    [string]$BaseUrl = "http://<YOUR_EC2_IP>:8080/api"
)

$ErrorActionPreference = "Stop"

function Clear-Cache {
    Write-Host "`n[캐시 클리어] Redis 초기화 중..." -ForegroundColor Yellow
    $response = Invoke-WebRequest -Method DELETE -Uri "$($BaseUrl -replace '/api', '')/api/chapter6/books/cache" -UseBasicParsing
    Write-Host "[캐시 클리어] 완료 ($($response.StatusCode))" -ForegroundColor Green
}

function Run-Test {
    param([string]$Label, [string]$Script, [string]$OutputFile, [hashtable]$Env)

    Write-Host "`n======================================" -ForegroundColor Cyan
    Write-Host " $Label" -ForegroundColor Cyan
    Write-Host "======================================" -ForegroundColor Cyan

    # 환경변수 설정
    $env:BASE_URL = $BaseUrl
    foreach ($key in $Env.Keys) {
        Set-Item "env:$key" $Env[$key]
    }

    k6 run $Script --summary-export "results/$OutputFile"

    if ($LASTEXITCODE -ne 0) {
        Write-Host "[경고] $Label threshold 위반 또는 에러 발생 (exit code: $LASTEXITCODE)" -ForegroundColor Red
    } else {
        Write-Host "[완료] $Label" -ForegroundColor Green
    }
}

# results 디렉토리 확인
if (-not (Test-Path "results")) {
    New-Item -ItemType Directory -Path "results" | Out-Null
}

Write-Host "`n========================================" -ForegroundColor Magenta
Write-Host " Chapter 6 전체 테스트 시작" -ForegroundColor Magenta
Write-Host " BASE_URL: $BaseUrl" -ForegroundColor Magenta
Write-Host "========================================" -ForegroundColor Magenta

# -----------------------------------------------
# 6A. Cache Penetration
# setup()에서 자동으로 캐시 클리어
# -----------------------------------------------
Run-Test `
    -Label "6A Before — Cache Penetration (null 캐싱 없음)" `
    -Script "cache-penetration-test.js" `
    -OutputFile "scenario6a_penetration-before.json" `
    -Env @{ STRATEGY = "before"; NONEXISTENT_ID = "99999" }

Run-Test `
    -Label "6A After — Cache Penetration (NULL_VALUE sentinel)" `
    -Script "cache-penetration-test.js" `
    -OutputFile "scenario6a_penetration-after.json" `
    -Env @{ STRATEGY = "after"; NONEXISTENT_ID = "99999" }

# -----------------------------------------------
# 6B. Cache Avalanche
# setup()에서 자동으로 캐시 클리어
# -----------------------------------------------
Run-Test `
    -Label "6B Before — Cache Avalanche (고정 TTL)" `
    -Script "cache-avalanche-test.js" `
    -OutputFile "scenario6b_avalanche-before.json" `
    -Env @{ STRATEGY = "before"; TOTAL_BOOKS = "1000" }

Run-Test `
    -Label "6B After — Cache Avalanche (Jitter 적용)" `
    -Script "cache-avalanche-test.js" `
    -OutputFile "scenario6b_avalanche-after.json" `
    -Env @{ STRATEGY = "after"; TOTAL_BOOKS = "1000" }

# -----------------------------------------------
# 6C. Hot Key
# setup() 없음 → 수동 캐시 클리어 필수
# -----------------------------------------------
Clear-Cache
Run-Test `
    -Label "6C Before — Hot Key (분산 락 없음)" `
    -Script "hot-key-test.js" `
    -OutputFile "scenario6c_hotkey-before.json" `
    -Env @{ STRATEGY = "before"; BOOK_ID = "1" }

Clear-Cache
Run-Test `
    -Label "6C After — Hot Key (분산 락 적용)" `
    -Script "hot-key-test.js" `
    -OutputFile "scenario6c_hotkey-after.json" `
    -Env @{ STRATEGY = "after"; BOOK_ID = "1" }

# -----------------------------------------------
# 완료 요약
# -----------------------------------------------
Write-Host "`n========================================" -ForegroundColor Magenta
Write-Host " 전체 테스트 완료" -ForegroundColor Magenta
Write-Host " 결과 파일: k6-scripts/chapter6/results/" -ForegroundColor Magenta
Write-Host "========================================" -ForegroundColor Magenta

Write-Host "`n[결과 파일 목록]" -ForegroundColor Cyan
Get-ChildItem "results/scenario6*.json" | Select-Object Name, LastWriteTime | Format-Table -AutoSize
