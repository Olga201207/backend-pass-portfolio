# Backend Performance Portfolio

백엔드 성능 개선 포트폴리오 프로젝트입니다.
AWS 환경(EC2 + RDS + Redis)에 배포해 **문제 발견 → 원인 분석 → 해결 → 수치 검증**의 흐름으로 6가지 성능 시나리오를 구현했습니다.

> 상세 분석 문서: [`docs/performance-improvements.md`](docs/performance-improvements.md)

---

## 시스템 아키텍쳐

![시스템 아키텍쳐](<시스템 아키텍쳐.PNG>)

## 인프라 아키텍쳐

![인프라 아키텍쳐](<인프라 아키텍쳐-backend-portfolio.PNG>)

---

## 기술 스택

| 분류 | 기술 |
|------|------|
| Language | Java 17 |
| Framework | Spring Boot 3, Spring Security, Spring Data JPA |
| Database | MySQL 8, Redis |
| Infra | AWS EC2, AWS RDS, Docker, Docker Compose |
| Monitoring | Prometheus, Grafana, AWS CloudWatch |
| Load Test | k6 |
| Build | Gradle |

---

## 시나리오 요약

| # | 시나리오 | 핵심 개선 | 측정 조건 |
|---|---------|----------|---------|
| 1 | **N+1 쿼리** — Lazy Loading 최적화 | 쿼리 수 6 → 1 (83% 감소) | 30 VU / 3분 |
| 2 | **대용량 처리** — Bulk Insert 최적화 | p95 2,475ms → 115ms (95% 감소) | 1 VU / 1,440건 단건 vs 배치 |
| 3 | **동시성 제어** — 락 전략 비교 | 4개 전략 모두 Race Condition 0건, 에러 0% | 100 VU / 50초 / 정원 30명 |
| 4 | **통계 쿼리** — Materialized View 패턴 | 에러율 100% → 0% (8분간 성공 0건 → 31,659건), p95 99ms | 50→100 VU ramp / 8분 / 데이터 100만 건 |
| 5 | **비동기 처리** — 외부 API 병렬화 | avg 838ms → 504ms (40% 감소) | 20 VU / 3분 |
| 6A | **Cache Penetration** — Null Sentinel | avg 49ms → 17ms (65% 감소), DB 호출 99%+ 감소 | 100 VU / 80초 |
| 6B | **Cache Avalanche** — TTL Jitter | p95 492ms → 308ms (37% 감소) | 300 VU / 40초 |
| 6C | **Hot Key** — 분산 락 | DB 호출 300 → 2 (99.3% 감소) | 300 VU / 30초 |

> **시나리오 4 수치 해석**
> Before의 p95는 59,989ms로 기록되지만, 이는 응답 시간이 아니라 k6 기본 타임아웃 60초에서 잘린 값입니다(max 59,993ms로 60초를 넘지 않음). 분포가 min 30,010 · med 30,015 · p90 30,068ms로 HikariCP 기본 `connectionTimeout` 30초에 몰려 있어, 100 VU 구간에서 커넥션 풀이 고갈된 것으로 보입니다. 8분 동안 종료된 요청 1,021건은 **전부 실패**했고 성공한 요청은 0건입니다.
>
> 즉 이 시나리오의 Before는 "느린 상태"가 아니라 **해당 부하에서 응답이 불가능한 상태**입니다. 따라서 개선 폭을 `59,989ms → 99ms`처럼 지연 시간 단축으로 읽으면 안 되고, **성공 요청 수와 에러율**로 읽어야 합니다. After는 31,659건을 전부 성공 처리했으며(초당 65.84건), 분포도 min 14 · med 29 · max 713ms로 타임아웃에 잘린 구간이 없습니다.

---

## 프로젝트 구조

```
src/main/java/ding/co/backendportfolio/
├── chapter2/          # 기본 CRUD, 회원/게시판
├── chapter3/          # 통계 쿼리 (Before)
├── chapter3improved/  # 통계 쿼리 (After — Materialized View)
├── chapter4/          # 동시성 제어 (비관적/낙관적/Named/Redis 락)
├── chapter5/          # N+1, Bulk Insert, 비동기 처리
├── chapter5improved/  # 위 항목들의 개선 버전
├── chapter6/          # Redis 캐시 (Before)
├── chapter6_improved/ # Redis 캐시 (After — Penetration/Avalanche/HotKey 대응)
└── global/            # 공통 설정 (JWT, Security, Exception)

k6-scripts/
├── chapter3/          # 시나리오 4 (통계 쿼리) 부하 테스트
├── chapter4/          # 시나리오 3 (동시성) 부하 테스트
├── chapter5/          # 시나리오 1, 2, 5 부하 테스트
├── chapter6/          # 시나리오 6A/6B/6C 부하 테스트
└── config.js          # 공통 환경변수

docs/
└── performance-improvements.md  # 시나리오별 상세 분석 (원인/해결/실측 결과)
```

---

## 로컬 실행

```bash
# 의존성 실행 (MySQL, Redis, Prometheus, Grafana)
docker-compose up -d

# 애플리케이션 실행
./gradlew bootRun
```

**환경 변수 (`application.yml` 기본값)**

- DB: `localhost:3306/backendportfolio`
- Redis: `localhost:6379`
- Grafana: `http://localhost:3000`

---

## k6 부하 테스트

```powershell
# 시나리오 3: 동시성 제어 (4개 락 전략 비교)
cd k6-scripts/chapter4
$env:BASE_URL="http://localhost:8080/api"
$res = Invoke-RestMethod -Method POST "$env:BASE_URL/chapter4/events/setup?maxParticipants=30"
$env:EVENT_ID = $res.eventId; $env:STRATEGY="pessimistic"
k6 run scenario3-concurrency.js

# 시나리오 4: 통계 쿼리 (Before/After 비교)
cd k6-scripts/chapter3
$env:STRATEGY="before"
k6 run stats-test.js

# 시나리오 6: Redis 캐시 (전체 자동 실행)
cd k6-scripts/chapter6
.\run-all.ps1 -BaseUrl "http://localhost:8080/api"
```
