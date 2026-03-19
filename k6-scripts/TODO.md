# 성능 검증 남은 작업

> 작성일: 2026-03-19 | 시나리오 1 (N+1) 완료 후 기록

---

## 즉시 해야 할 것

### EC2 앱 재시작
chapter2 300VU 테스트로 OOM 발생 → 재시작 필요

```bash
# EC2 SSH 접속 후
docker-compose restart app

# 헬스체크
curl http://<EC2_IP>:8080/actuator/health
```

---

## 시나리오별 남은 작업

### ✅ 시나리오 1: N+1 쿼리 (완료)

결과 파일 위치: `k6-scripts/chapter5/results/`

| 전략 | p95 | TTFB avg | RPS |
|------|-----|----------|-----|
| lazy (before) | 924ms | 220ms | 18.16 |
| fetch-join | 983ms | 106ms (-52%) | 18.21 |
| batch-size | 454ms | 135ms (-39%) | 19.93 |
| entity-graph | **212ms** | **37ms (-83%)** | **22.71** |

> 핵심 인사이트: p95가 아닌 **TTFB(waiting)** 가 N+1 해결의 핵심 지표.
> fetch-join은 receiving 증가로 p95가 개선 안 돼 보이지만 서버 처리시간(TTFB)은 절반으로 줄었음.

---

### ⬜ 시나리오 2: Bulk Insert 최적화

**데이터 시딩 불필요** (POST 자체가 insert)

```powershell
cd k6-scripts\chapter5

# Before: 1440회 개별 save()
powershell -ExecutionPolicy Bypass -File .\run.ps1 -Script bulk-insert -Strategy individual

# After: JdbcTemplate.batchUpdate() 단일 배치
powershell -ExecutionPolicy Bypass -File .\run.ps1 -Script bulk-insert -Strategy batch
```

**확인 지표**: `http_req_duration` 절댓값 (1 VU, 1 iteration — percentile 무의미)
- individual 예상: 수십~수백 초 (1440번 DB 왕복)
- batch 예상: 수 초 이내

**엔드포인트**:
- `DELETE /api/chapter5/bulk` (기존 데이터 삭제, run.ps1이 자동 호출)
- `POST /api/chapter5/bulk/individual?count=1440`
- `POST /api/chapter5/bulk/batch?count=1440`

---

### ⬜ 시나리오 3: 동시성 제어

**데이터 시딩** (최초 1회):
```powershell
# 이벤트 생성 - chapter4 setup API 확인 필요
# HotelController처럼 setup 엔드포인트 있는지 확인
Invoke-RestMethod -Method Post "http://<EC2_IP>:8080/api/chapter4/events/setup"
```

```powershell
cd k6-scripts\chapter4

# After: Pessimistic Lock
powershell -ExecutionPolicy Bypass -File .\run.ps1 -Strategy pessimistic

# After: Optimistic Lock
powershell -ExecutionPolicy Bypass -File .\run.ps1 -Strategy optimistic

# After: Named Lock
powershell -ExecutionPolicy Bypass -File .\run.ps1 -Strategy named
```

**확인 지표**:
- 최종 참가자 수 (정합성) — 선착순 100명 이벤트에 정확히 100명인지
- `http_req_duration` p95
- 락 경합으로 인한 에러율

---

### ⬜ 시나리오 4: 통계 쿼리 최적화

**데이터 시딩** (최초 1회):
```powershell
# chapter3 mock data가 RDS에 있는지 확인
# src/main/resources/chapter3/mock-data.sql 확인
Invoke-RestMethod -Method Post "http://<EC2_IP>:8080/api/chapter3/orders/setup"
```

```powershell
cd k6-scripts\chapter3\before
powershell -ExecutionPolicy Bypass -File .\run.ps1  # Before: 매 요청마다 집계 쿼리

cd ..\after
powershell -ExecutionPolicy Bypass -File .\run.ps1  # After: 사전 집계 테이블 조회
```

**확인 지표**: `http_req_duration` p95, `http_req_waiting` (TTFB)

---

### ⬜ 시나리오 5: 비동기 처리

**데이터 시딩** (최초 1회):
```powershell
Invoke-RestMethod -Method Post "http://<EC2_IP>:8080/api/chapter5/movies/setup?count=100"
```

```powershell
cd k6-scripts\chapter5

# Before: 동기 처리 (외부 API 순차 호출)
powershell -ExecutionPolicy Bypass -File .\run.ps1 -Script async -Strategy sync

# After: 비동기 처리 (CompletableFuture 병렬 호출)
powershell -ExecutionPolicy Bypass -File .\run.ps1 -Script async -Strategy async
```

**확인 지표**: `http_req_duration` p95, `http_req_waiting`

---

### ⬜ 시나리오 6: Redis 캐시

**데이터 시딩** (최초 1회):
```powershell
Invoke-RestMethod -Method Post "http://<EC2_IP>:8080/api/chapter6/books/setup?count=1000"
```

```powershell
cd k6-scripts\chapter6

# 6C: Hot Key 문제
powershell -ExecutionPolicy Bypass -File .\run.ps1 -Script hot-key -Strategy before
powershell -ExecutionPolicy Bypass -File .\run.ps1 -Script hot-key -Strategy after

# 6B: Cache Avalanche (캐시 만료 동시 폭발)
powershell -ExecutionPolicy Bypass -File .\run.ps1 -Script cache-avalanche -Strategy before
powershell -ExecutionPolicy Bypass -File .\run.ps1 -Script cache-avalanche -Strategy after

# 6A: Cache Penetration (없는 키 지속 요청)
powershell -ExecutionPolicy Bypass -File .\run.ps1 -Script cache-penetration -Strategy before
powershell -ExecutionPolicy Bypass -File .\run.ps1 -Script cache-penetration -Strategy after
```

**확인 지표**: `http_req_duration` p95, cache hit/miss rate (Grafana 커스텀 메트릭)

---

## Grafana 시각화 체크리스트

각 시나리오 실행 후 Grafana에서 캡처할 패널: `http://<EC2_IP>:3000`

| 시나리오 | 대시보드 | 패널 |
|---------|---------|------|
| N+1 | 커스텀 | `app.query.per_request` (전략별 쿼리 수) |
| N+1 | JVM Micrometer #4701 | HTTP Request Duration p95 |
| Bulk Insert | JVM Micrometer #4701 | HTTP Request Duration (절댓값) |
| Bulk Insert | JVM Micrometer #4701 | JVM Heap Memory |
| 동시성 | Spring Boot #19004 | Error Rate, HTTP Duration |
| 통계 쿼리 | HikariCP #14091 | Active Connections |
| 비동기 | JVM Micrometer #4701 | Thread Pool Active |
| Redis | 커스텀 | Cache Hit Rate, Redis Memory |

---

## 결과 파일 관리 규칙

- 파일명 형식: `{scenario}-{strategy}-YYYY-MM-DD_HH-mm.json`
- 주석 파일: 동일 이름에 `.annotated.json` suffix 추가
- **삭제 대상**: 직렬화된 one-liner JSON (구버전 handleSummary 생성 파일)
- **보존 대상**: `--summary-export`로 생성된 pretty-print JSON + annotated 버전

---

## 참고: k6 실행 방법

`.ps1`이 메모장으로 열릴 때:
```powershell
powershell -ExecutionPolicy Bypass -File .\run.ps1 -Script {script} -Strategy {strategy}
```

BASE_URL 직접 지정 (run.ps1 없이):
```bash
k6 run --env BASE_URL=http://<EC2_IP>:8080/api --env STRATEGY=lazy n-plus-one-test.js --summary-export results/output.json
```
