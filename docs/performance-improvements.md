# 백엔드 성능 개선 포트폴리오.

## 개요

> "문제를 발견하고 → 원인을 분석하며 → 해결한 결과를 개선 수치와 함께 보여줘야 설득력이 높아진다"

본 문서는 APM/모니터링 툴을 통한 **관측 → 병목 발견 → 개선**의 흐름으로 실제 코드에서 발생한 성능 문제를 해결한 시나리오를 정리한다.

---

## 시나리오 요약

| # | 시나리오 | Before | After | 개선율 |
|---|---------|--------|-------|--------|
| 1 | N+1 쿼리 (5개 호텔) | 6 queries | 1 query | 83% 감소 |
| 2 | Bulk Insert (1440건) | 1,440회 개별 INSERT | 배치 단위 처리 | DB 왕복 대폭 감소 |
| 3 | 동시성 제어 (100 VU / 50s) | Race Condition 발생 | 4개 전략 모두 join_success=30, 에러 0% | 비관적 p95 311ms / Redis Named p95 1,640ms |
| 4 | 통계 쿼리 (매 요청 집계) | 전체 집계 on-demand | 사전 집계 테이블 조회 | p95 목표 300ms |
| 5 | 비동기 처리 (외부 API 4개) | avg 838ms / p95 927ms | avg 504ms / p95 606ms | 40% 감소 |
| 6A | Cache Penetration (100 VU) | avg 49ms / p95 88ms (매 요청 DB 조회) | avg 17ms / p95 30ms (sentinel 캐시 히트) | 응답시간 65% 감소, DB 호출 99%+ 감소 |
| 6B | Cache Avalanche (300 VU) | avg 182ms / p95 492ms | avg 129ms / p95 308ms | p95 37% 감소, DB 스파이크 완화 |
| 6C | Hot Key (300 VU, 동일 ID) | avg 89ms / errors 0% / DB 300 calls | avg 90ms / errors 0% / DB 2 calls | DB 호출 99.3% 감소 (응답시간은 캐시 히트 비율로 유사) |

---

## 시나리오 1: N+1 쿼리 문제 해결

### 문제 상황

Hotel 목록 조회 API에서 `@OneToMany` 연관관계를 Lazy Loading으로 설정했을 때, 호텔 목록을 가져온 후 각 호텔의 Room 목록을 개별 쿼리로 조회하는 N+1 문제가 발생했다.

- 5개 호텔 조회 시: `1(호텔 목록) + 5(각 호텔의 Room 조회) = 6 queries`
- 100개 호텔 조회 시: 101 queries로 선형 증가

### 원인 분석

`QueryCountInterceptor`를 구현해 요청당 SQL 쿼리 수를 `MeterRegistry`에 기록하고, Grafana 대시보드의 `app.query.per_request` 메트릭으로 특정 엔드포인트에서 쿼리가 폭발적으로 증가하는 것을 관측했다.

```java
// QueryCountInterceptor: 요청 완료 시 쿼리 수를 Micrometer 메트릭으로 기록
DistributionSummary summary = DistributionSummary.builder("app.query.per_request")
        .tag("path", ctx.getBestMatchPath())
        .tag("http_method", ctx.getHttpMethod())
        .tag("query_type", queryType.name())
        .publishPercentiles(0.5, 0.95)
        .register(meterRegistry);
summary.record(count);
```

### 해결 방법

3가지 전략을 직접 구현하고 성능/유지보수성 트레이드오프를 비교했다.

**전략 1. BatchSize**
```java
@BatchSize(size = 2)
@OneToMany(mappedBy = "hotel", fetch = FetchType.LAZY)
private List<RoomBatchSize> rooms = new ArrayList<>();
```
- 6쿼리 → `1 + ceil(5/2) = 4쿼리`
- Room을 배치 크기(2) 단위로 `IN` 절로 묶어 조회
- 페이징 쿼리와 함께 사용 가능하나, 메모리 기반 페이징 리스크 없음

**전략 2. Fetch Join (JPQL)**
```java
@Query("SELECT DISTINCT h FROM HotelImproved h JOIN FETCH h.rooms")
List<HotelImproved> findAllWithRooms();
```
- 6쿼리 → **1쿼리** (단일 JOIN 쿼리)
- 컬렉션 페치 조인 시 `DISTINCT` 필수, 페이징(`LIMIT/OFFSET`) 사용 불가

**전략 3. EntityGraph**
```java
@EntityGraph(attributePaths = {"rooms"})
List<HotelEntityGraph> findAll();
```
- 6쿼리 → **1쿼리** (어노테이션 기반, JPQL 미사용)
- 유지보수성 우수, 복잡한 조인 조건에는 Fetch Join 선호

### 개선 결과

| 전략 | 쿼리 수 (5개 호텔) | 비고 |
|------|-----------------|------|
| Lazy Loading (before) | 6 queries | N+1 발생 |
| BatchSize(2) | 4 queries | IN절 배치 처리 |
| Fetch Join | **1 query** | 페이징 불가 |
| EntityGraph | **1 query** | 어노테이션 기반 |

### 측정 방법

**측정 목표**: 요청당 쿼리 수, 응답시간, DB 커넥션 사용량

| 툴 | 사용 방법 | 측정 지표 |
|----|----------|----------|
| **Grafana** (커스텀 패널) | PromQL: `app_query_per_request_sum / app_query_per_request_count` (path 태그 필터) | 요청당 평균/p95 쿼리 수 |
| **Grafana** (Dashboard #19004) | Spring Boot Observability → HTTP Request Duration (path 필터) | p95 응답시간 |
| **Grafana** (Dashboard #19004) | Spring Boot Observability → HikariCP 패널 | DB 커넥션 사용량 |
| **k6** | `load-test.js` 변형 — hotel 엔드포인트로 교체, 30 VU / 3분 | throughput, p95 latency |
| **CloudWatch** | AWS/RDS → `DatabaseConnections`, `CPUUtilization` | DB 부하 |

Before/After 비교: 동일 VU 수로 실행 후 Grafana `app.query.per_request` p95 값 스크린샷

### 어필 포인트

단순히 "N+1을 해결했다"에 그치지 않고, 3가지 접근법의 트레이드오프(메모리 사용량, 페이징 제약, 코드 유지보수성)를 직접 구현하고 비교했다. 실무에서 상황에 따라 적합한 전략을 선택할 수 있는 근거를 `QueryCountInterceptor` + Grafana 메트릭으로 수치 기반으로 제시했다.

---

## 시나리오 2: 대용량 Bulk Insert 최적화

### 문제 상황

지하철 통계 데이터 1,440건을 JPA `repository.save()` 반복 호출로 INSERT하면 1,440번의 개별 DB 왕복이 발생했다. 각 INSERT마다 네트워크 레이턴시와 트랜잭션 오버헤드가 누적되어 대용량 배치 처리에 심각한 성능 저하를 초래했다.

### 원인 분석

k6로 `POST /api/chapter5/bulk/individual`(1,440회 `save()`)와 `POST /api/chapter5/bulk/batch`(`batchUpdate()`)를 각각 1회 실행하여 응답시간을 직접 비교했다. JPA의 `save()` 반복 호출이 각각 독립적인 `PreparedStatement`를 생성하고 실행하며 1,440번의 DB 왕복을 유발하는 것이 응답시간 차이로 확인됐다.

```java
// Before: 1,440번 개별 INSERT
for (SubwayStats stats : statsList) {
    repository.save(stats); // 매 호출마다 DB 왕복 발생
}
```

### 해결 방법

`JdbcTemplate.batchUpdate()`를 사용해 모든 INSERT를 하나의 배치로 묶어 단일 DB 왕복으로 처리했다.

```java
// After: JdbcTemplate.batchUpdate()로 배치 처리
@Transactional
public void bulkInsertImproved(List<SubwayStats> statsList) {
    String sql = """
            INSERT INTO ch5_subway_stats (station_name, boarding_count, exiting_count, time)
            VALUES (?, ?, ?, ?)
            """;

    jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
        @Override
        public void setValues(PreparedStatement ps, int i) throws SQLException {
            SubwayStats stats = statsList.get(i);
            ps.setString(1, stats.getStationName());
            ps.setInt(2, stats.getBoardingCount());
            ps.setInt(3, stats.getExitingCount());
            ps.setTimestamp(4, Timestamp.valueOf(stats.getTime()));
        }

        @Override
        public int getBatchSize() {
            return statsList.size();
        }
    });
}
```

DELETE도 동일하게 최적화했다. `entityManager.flush()`로 영속성 컨텍스트의 변경 내용을 DB에 반영한 후, `deleteAllInBatch()`로 단일 `DELETE` 쿼리를 실행하고 `entityManager.clear()`로 영속성 컨텍스트를 초기화했다.

```java
@Transactional
public void bulkDeleteImproved() {
    entityManager.flush();
    repository.deleteAllInBatch(); // DELETE FROM ch5_subway_stats 단일 쿼리
    entityManager.clear();
}
```

### 개선 결과

| 항목 | Before | After |
|------|--------|-------|
| INSERT 방식 | 1,440회 개별 `save()` | `batchUpdate()` 단일 배치 |
| DB 왕복 횟수 | 1,440회 | 1회 (배치 크기 = 전체) |
| DELETE 방식 | 개별 `delete()` | `deleteAllInBatch()` 단일 쿼리 |

### k6 실측 결과 (EC2 운영 환경, 1 VU / 1 iteration, 1,440건)

| 지표 | Before (`saveAll`) | After (`batchUpdate`) | 개선율 |
|------|-------------------|----------------------|--------|
| avg 응답시간 | 1.33s | **77ms** | **94% 감소** |
| p(95) 응답시간 | 2.47s | **115ms** | **95% 감소** |
| 전체 실행시간 | 2.7s | **0.2s** | **93% 감소** |
| 에러율 | 0% | 0% | - |

> 측정 결과 파일: `k6-scripts/chapter5/results/scenario2_bulk-insert-*.json`

### 측정 방법

**측정 목표**: 배치 실행 시간

| 툴 | 사용 방법 | 측정 지표 |
|----|----------|----------|
| **k6** | 1 VU / 1 iteration 단발 실행 → `http_req_duration` Before/After 비교 | 단건 실행 시간 |

> **Grafana 미사용 이유**: 1 VU / 1 iteration 테스트는 요청이 2건(DELETE + POST)에 불과해 Prometheus 수집 주기(5s)보다 짧게 종료된다. 시계열 그래프에 데이터가 남지 않으므로 k6 터미널 출력이 유일한 측정 근거다.

Before/After 비교: k6 터미널의 `http_req_duration` avg / p(95) 수치 직접 비교

### 어필 포인트

ORM 편의성(`save()`)이 가진 성능 함정을 인지하고, 대용량 처리 시에는 JDBC 레벨 직접 제어가 필요하다는 판단을 내렸다. `flush()/clear()` 패턴을 통해 영속성 컨텍스트 메모리 누수도 함께 방지했다.

---

## 시나리오 3: 동시성 제어 - 이벤트 선착순 참여

### 문제 상황

선착순 이벤트 참여 API에 100명이 동시에 요청을 보낼 때, 여러 트랜잭션이 동시에 잔여 정원을 읽고 참여자 수를 증가시키면서 Race Condition이 발생했다. 정원이 30명인 이벤트에 30명 이상이 참여하는 데이터 정합성 문제가 생겼다.

### 원인 분석

`ConcurrentTestUtil`로 32개 스레드 풀 기반 100 concurrent 시나리오를 재현했다. 비동기 실행 후 10초 타임아웃으로 결과를 수집하여 참여자 수가 정원을 초과하는 것을 확인했다.

```java
// ConcurrentTestUtil: 동시성 시나리오 재현
ExecutorService executor = Executors.newFixedThreadPool(32);
CountDownLatch latch = new CountDownLatch(100);
// 100명 동시 참여 요청 → Race Condition 발생 확인
```

### 해결 방법

3가지 락 전략을 구현하고 상황별 적합성을 비교했다.

**전략 1. 비관적 락 (Pessimistic Lock)**
```java
@Transactional
public void joinEventPessimistic(Long eventId, Long memberId) {
    // SELECT ... FOR UPDATE → 즉시 배타락 획득
    EventWithLock event = eventRepository.findByIdWithPessimisticLock(eventId)
            .orElseThrow(...);
    event.increaseParticipants();
    // 트랜잭션 종료 시까지 락 유지 → 다른 트랜잭션 대기
}
```
- 충돌이 잦은 환경에서 효율적
- 데드락 위험: `saveAndFlush()` 불필요 (비관적 락이 이미 순서 보장)

**전략 2. 낙관적 락 (Optimistic Lock)**
```java
@Transactional
public void joinEventOptimistic(Long eventId, Long memberId) {
    // @Version 필드 기반, 커밋 시점에 버전 충돌 감지
    EventWithLock event = eventRepository.findByIdWithOptimisticLock(eventId)
            .orElseThrow(...);
    event.increaseParticipants();
    eventRepository.saveAndFlush(event); // INSERT 전 UPDATE 강제 실행으로 데드락 방지
}
// Facade에서 ObjectOptimisticLockingFailureException 시 50ms 대기 후 재시도
```
- DB 락 없이 낮은 충돌 환경에서 처리량 극대화
- `saveAndFlush()` 필수: JPA 특성상 INSERT 먼저 실행되면 FK 공유락으로 데드락 발생 방지

**전략 3-A. MySQL Named Lock (시도 → 실패)**
```java
// GET_LOCK('event:1', 3) → 3초 타임아웃으로 MySQL 사용자 정의 락 획득
// REQUIRES_NEW 트랜잭션으로 락과 데이터 트랜잭션 분리
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void joinEventWithNamedLock(Long eventId, Long memberId) { ... }

// Facade: while(true) + sleep(50ms) 재시도 루프
@Transactional  // ← 이것이 문제
public void joinEvent(Long eventId, Long memberId) {
    while (true) {
        try {
            eventWithLockService.joinEventWithNamedLock(eventId, memberId);
            return;
        } catch (Exception e) {
            Thread.sleep(50);  // 커넥션 점유 중 대기
        }
    }
}
```

**MySQL Named Lock 실패 원인: DB 커넥션 풀 고갈**

MySQL Named Lock은 `GET_LOCK()`/`RELEASE_LOCK()`이 **세션(커넥션) 단위**로 동작한다. 이를 정상 동작시키려면 락 획득과 해제가 **동일한 DB 커넥션**에서 이루어져야 한다.

문제는 `@Transactional` + `while(true) + sleep(50ms)` 재시도 루프의 조합이었다:

1. Facade의 `@Transactional`이 트랜잭션 시작 시 커넥션 1개를 획득
2. while 루프 내에서 락 획득 실패 시 `sleep(50ms)` 대기 → **커넥션을 점유한 채로 대기**
3. `REQUIRES_NEW` 서비스 호출도 추가 커넥션 1개를 요구
4. 100 VU × 2 커넥션 = 200개 필요 → HikariCP 기본 풀(10개)이 즉시 고갈
5. 커넥션 대기 timeout(30초) 후 500 에러 → **에러율 84.77%**, p95 = 1m 0s (timeout)

`@Transactional`을 제거하면 세션 일관성이 깨져 `RELEASE_LOCK()`이 다른 커넥션에서 실행되어 락 해제가 불가능해지는 딜레마가 있었다.

**전략 3-B. Redis Named Lock (해결)**

Named Lock의 개념(분산 락)은 유지하되, MySQL 세션 의존성 문제를 Redis `setIfAbsent`(원자적 SET NX)로 해결했다.

```java
// RedisNamedLockEventFacade: @Transactional 없음 → 커넥션 점유 없음
public void joinEvent(Long eventId, Long memberId) throws InterruptedException {
    String lockKey = "event-lock:" + eventId;
    for (int retry = 0; retry < MAX_RETRY; retry++) {  // 최대 100회
        if (!distributedLockProvider.tryLock(lockKey, 5_000)) {
            Thread.sleep(50);  // 커넥션 미점유 상태로 대기
            continue;
        }
        try {
            eventWithLockService.joinEventWithNamedLock(eventId, memberId);
            return;
        } catch (IllegalStateException e) {
            throw e;  // 정원 초과 → 즉시 실패 (재시도 안 함)
        } finally {
            distributedLockProvider.releaseLock(lockKey);
        }
    }
}
```

- Redis `setIfAbsent`는 세션과 무관하게 원자적으로 동작 → 커넥션 풀 문제 없음
- while 루프 중 DB 커넥션 미점유 → HikariCP 부하 없음
- 분산 환경(멀티 인스턴스)에서도 동일하게 동작

### 개선 결과

| 전략 | 100 concurrent 결과 | 특징 |
|------|-------------------|------|
| 락 없음 (before) | 정원 초과 발생 | Race Condition |
| 비관적 락 | 정원 초과 0건 | 즉시 DB 잠금, 데드락 주의 |
| 낙관적 락 | 정원 초과 0건 | 재시도 로직 필요, 저충돌 최적 |
| MySQL Named Lock | 정원 초과 0건 | DB 커넥션 유지 필요, 단일 인스턴스 적합 |
| Redis Named Lock | 정원 초과 0건 | 분산 환경 적합, DB 커넥션 무관 |

### k6 실측 결과 (EC2 운영 환경, 100 VU / 50s, `scenario3-concurrency.js`)

| 전략 | join_success | avg | p(95) | RPS | 에러율(5xx) | threshold |
|------|-------------|-----|-------|-----|------------|-----------|
| 비관적 락 | **30** ✅ | 165ms | 311ms | 288.7/s | 0% | ✅ |
| 낙관적 락 | **30** ✅ | 199ms | 384ms | 275.8/s | 0% | ✅ |
| MySQL Named Lock | **30** ✅ | 515ms | 872ms | 129.7/s | 0% | ✅ |
| Redis Named Lock | **30** ✅ | 600ms | 1,640ms | 114.1/s | 0% | ✅ |

> `http_req_failed` 수치가 99%로 높게 보이는 것은 k6가 기본적으로 4xx 응답을 HTTP 실패로 집계하기 때문이다. 정원 초과 시 반환하는 400 응답이 대부분을 차지하며, 이는 의도된 동작이다. 비즈니스 로직 기준 에러율(`errors` 커스텀 메트릭)은 모두 0%이며, **4개 전략 모두 join_success = 정확히 30 — Race Condition 없이 정원 초과 0건을 증명**했다.

**전략별 특성 분석**
- **비관적 락**: DB X-lock(`SELECT FOR UPDATE`)으로 순차 처리. 100 VU 중 정원 30명 이후 요청은 즉시 거절 → RPS 288.7로 가장 높은 처리량. 단, 멀티 인스턴스 환경에서도 DB 단에서 직렬화되므로 분산 환경에도 적합.
- **낙관적 락**: `@Version` 체크로 충돌 감지, 실패 시 재시도. 정원이 이미 차면 재시도 없이 즉시 400 반환 → avg 199ms로 빠름. 충돌 빈도가 낮은 환경(저경합)에 최적.
- **MySQL Named Lock**: Advisory Lock으로 직렬화. 커넥션을 락 보유 시간 동안 점유하므로 높은 동시성에서 커넥션 대기 발생 → avg 515ms, RPS 129.7/s. 단일 인스턴스·중간 트래픽 환경에서 사용.
- **Redis Named Lock**: Redis `SET NX`로 분산 락 구현. 락 획득 재시도(최대 40회 × 5~20ms) 오버헤드로 p95 1,640ms. DB 커넥션 풀과 완전히 독립적으로 동작 → **다중 인스턴스·MSA 환경에서 유일한 선택지**.

### Grafana 모니터링 관측 (시나리오 3)

k6 실행 중 Grafana 대시보드(#4701 JVM, #19004 Spring Boot Observability)에서 다음을 관측했다.

**비관적 락 (16:51 KST 구간)**
- CPU Usage: 순간 99% spike → 즉시 하강. `SELECT FOR UPDATE`로 DB가 순차 처리하므로 CPU 급등 후 빠르게 안정화.
- Threads: 100 VU 진입 시 ~110개로 증가, ramp-down 후 즉시 감소. 대기 없이 빠른 처리를 반영.

**Redis Named Lock + 낙관적 락 (18:35, 18:45 KST 구간)**
- CPU Usage: 2개의 독립적인 spike — 각 전략이 순차적으로 실행된 것을 시각적으로 구분 가능.
- Threads: Redis Named Lock 구간에서 재시도 루프로 인해 스레드가 더 오래 활성화되는 패턴. 낙관적 락은 짧은 spike 후 즉시 감소.

> 측정 결과 파일: `k6-scripts/chapter4/results/scenario3_concurrency-*.json`

### k6 실행 명령어

```powershell
cd k6-scripts/chapter4
$env:BASE_URL="http://<YOUR_EC2_IP>:8080/api"
$env:TEST_EMAIL="k6test@test.com"; $env:TEST_PASSWORD="password123"

# 이벤트 생성 (최초 1회)
$res = Invoke-RestMethod -Method POST "$env:BASE_URL/chapter4/events/setup?maxParticipants=30"
$env:EVENT_ID = $res.eventId

# 비관적 락
$env:STRATEGY="pessimistic"
Invoke-RestMethod -Method POST "$env:BASE_URL/chapter4/events/$env:EVENT_ID/reset"
k6 run scenario3-concurrency.js --summary-export "results/scenario3_pessimistic.json"

# 낙관적 락
$env:STRATEGY="optimistic"
Invoke-RestMethod -Method POST "$env:BASE_URL/chapter4/events/$env:EVENT_ID/reset"
k6 run scenario3-concurrency.js --summary-export "results/scenario3_optimistic.json"

# MySQL Named Lock
$env:STRATEGY="named"
Invoke-RestMethod -Method POST "$env:BASE_URL/chapter4/events/$env:EVENT_ID/reset"
k6 run scenario3-concurrency.js --summary-export "results/scenario3_named.json"

# Redis Named Lock
$env:STRATEGY="redis-named"
Invoke-RestMethod -Method POST "$env:BASE_URL/chapter4/events/$env:EVENT_ID/reset"
k6 run scenario3-concurrency.js --summary-export "results/scenario3_redis-named.json"
```

### 측정 방법

**측정 목표**: 처리량(RPS), DB 커넥션 경합, 오류율, 락 전략별 응답시간

| 툴 | 사용 방법 | 측정 지표 |
|----|----------|----------|
| **k6** | `chapter4-load-test.js` — 50→100 VU ramp-up, `errors` rate < 0.1 threshold | throughput, error rate |
| **k6** | `http_req_duration{scenario:order-search}` p95 < 400ms 임계값으로 락 전략별 비교 | 응답시간 분포 |
| **Grafana** (Dashboard #19004) | Spring Boot Observability → HikariCP 패널 → `hikaricp_connections_pending` | DB 커넥션 경합 |
| **Grafana** (Dashboard #4701) | JVM Threads → peak thread count | 스레드 사용량 |
| **CloudWatch** | AWS/RDS → `DatabaseConnections` (락 전략별 피크값 비교) | DB 연결 수 |

락 전략 3종 각각에 대해 동일 k6 스크립트 실행 → error rate + p95 비교 테이블 작성

### 어필 포인트

단순히 "락을 걸었다"가 아닌, **처리량 vs 안전성 vs 분산 환경 호환성**의 트레이드오프를 분석하고 케이스별 선택 근거를 제시했다. 낙관적 락에서 `saveAndFlush()` 순서 제어로 데드락을 방지하는 JPA 내부 동작 이해도를 함께 보여줬다.

---

## 시나리오 4: 통계 쿼리 최적화 - Materialized View 패턴

### 문제 상황

주문 통계 API(`GET /orders/statistics`)가 매 요청마다 전체 주문 테이블을 집계하는 쿼리(`GROUP BY`, `SUM`, `COUNT`)를 실행했다. k6 부하 테스트(100 VUs, 3분)에서 고트래픽 시 DB CPU가 급증하고 p95 응답시간이 목표치를 초과했다.

### 원인 분석

k6 부하 테스트로 통계 API의 응답시간 분포를 측정하자, 동시 요청이 증가할수록 집계 쿼리 실행 시간이 선형으로 늘어나는 것을 확인했다. 수백만 건의 주문 데이터에 대해 매 요청마다 동일한 집계를 반복하는 비효율이 원인이었다.

```sql
-- Before: 매 요청마다 실행되는 집계 쿼리
SELECT m.email, COUNT(o.id), SUM(o.total_amount), AVG(o.total_amount)
FROM ch3_order o JOIN ch2_member m ON o.member_id = m.id
GROUP BY m.email;
```

### 해결 방법

집계 결과를 별도 테이블(`ch3_order_stats`)에 저장하고 스케줄러로 주기적으로 갱신하는 **Materialized View 패턴**을 적용했다.

```java
// 1. 3분마다 통계 갱신 스케줄러
@Scheduled(cron = "0 */3 * * * *")
@Transactional
public void refreshOrderStatistics() {
    orderStatsRepository.refreshOrderStats(); // INSERT ... ON DUPLICATE KEY UPDATE
}

// 2. API 요청 시 사전 집계 테이블에서 단순 조회
@Transactional(readOnly = true)
public Page<OrderStatisticsResponse> getOrderStatistics(Long minAmount, Pageable pageable) {
    return orderStatsRepository.findByTotalAmountGreaterThanEqual(minAmount, pageable)
            .map(this::toOrderStatisticsResponse);
}
```

`ON DUPLICATE KEY UPDATE`를 사용한 upsert로 증분 갱신 없이 전체 재집계를 안전하게 처리했다.

### 개선 결과

| 항목 | Before | After |
|------|--------|-------|
| 쿼리 방식 | 매 요청마다 전체 집계 | 사전 집계 테이블 단순 조회 |
| DB 부하 | 요청 수에 비례한 집계 부하 | 3분 주기 1회 집계로 고정 |
| 응답시간 목표 | 미달 | p95 99ms 달성 |
| 데이터 신선도 | 실시간 | 최대 3분 지연 |

### k6 실측 결과 (EC2 운영 환경, 50→100 VU / 8분)

| 지표 | Before (on-demand 집계) | After (사전 집계 테이블) | 개선율 |
|------|------------------------|------------------------|--------|
| avg 응답시간 | 32,836ms | **42ms** | **99.9% 감소** |
| p(95) 응답시간 | 60,000ms (timeout) | **99ms** | **99.8% 감소** |
| 에러율 | 100% (전 요청 타임아웃) | **0%** | 완전 정상화 |
| RPS | 2.0/s (타임아웃 병목) | **65.8/s** | - |

> 측정 결과 파일: `k6-scripts/chapter3/results/scenario4_stats-*.json`

### k6 실행 명령어

```powershell
cd k6-scripts/chapter3
$env:BASE_URL="http://<YOUR_EC2_IP>:8080/api"

# Before (on-demand 집계)
$env:STRATEGY="before"
k6 run stats-test.js --summary-export "results/scenario4_stats-before.json"

# After (사전 집계 테이블)
$env:STRATEGY="after"
k6 run stats-test.js --summary-export "results/scenario4_stats-after.json"
```

**발견된 한계**: `ch3_order_stats`에 `total_amount` 인덱스가 없으면 사전 집계 테이블도 full scan 발생. 인덱스 추가 필수. 또한 스케줄러 refresh(3분 주기, ~10s 소요) 실행 중에는 write lock으로 읽기 지연이 발생할 수 있음. 완전한 해결을 위해서는 shadow table swap 또는 read replica 분리가 필요하다.

### CloudWatch / JVM 모니터링 관측 (시나리오 4)

**CloudWatch RDS CPU (Before)**
- Before 테스트(100 VU, on-demand 집계): RDS CPU Utilization이 **50~90%까지 급등**, 약 45분간 고부하 지속.
- 매 요청마다 `GROUP BY` + `SUM`/`COUNT` 집계가 실행되어 DB가 bottleneck이 됨을 CloudWatch로 직접 확인.
- EC2 CPU는 상대적으로 평탄(20~30%) — 병목이 애플리케이션이 아닌 **DB**에 있음을 입증.

**CloudWatch RDS CPU Before/After 비교 (빨간 수직선 기준)**
- 빨간선 왼쪽(Before): RDS CPU 50~100% 진동
- 빨간선 오른쪽(After): RDS CPU **거의 0%** — 사전 집계 테이블 단순 조회로 DB 부하 사라짐.

**JVM 대시보드 (#4701)**
- Before 구간: JVM Heap 사용량 ~770 MiB peak (집계 결과 객체 생성으로 GC 압박), process CPU 93% peak.
- After 구간: Heap 사용량 정상화(~300 MiB), CPU 급격히 감소.
- Threads: Before 구간에서 peak 150~180개 (30초 타임아웃 대기 스레드 적체), After 구간에서 정상 30~40개.

### 측정 방법

**측정 목표**: p95 응답시간, DB CPU, 처리량

| 툴 | 사용 방법 | 측정 지표 |
|----|----------|----------|
| **k6** | `order-load-test.js` — `/api/orders/stats?minAmount=500000` 엔드포인트, p95<300ms threshold | p95 latency, throughput, error rate |
| **k6** | Stage 1(50VU 2분) → Stage 2(100VU 3분) → Stage 3(down) 부하 패턴 | 고트래픽 시 응답 안정성 |
| **Grafana** (Dashboard #19004) | HTTP Request Duration (path: `/api/orders/stats` 필터) | Before/After p95 응답시간 |
| **Grafana** (Dashboard #19004) | HTTP Request Rate (RPS) | 처리량 비교 |
| **CloudWatch** | AWS/RDS → `CPUUtilization` (Before: 매 요청 집계 시 CPU 스파이크 vs After: 평탄) | DB CPU 부하 패턴 |
| **CloudWatch** | AWS/RDS → `DatabaseConnections` | 커넥션 사용 패턴 |

### 어필 포인트

**실시간성 vs DB 부하**라는 트레이드오프를 비즈니스 요구사항(통계는 약간의 지연 허용)에 맞게 판단했다. 스케줄링 + 반정규화로 집계 비용을 시간적으로 분산시키는 실무 패턴을 구현했다.

---

## 시나리오 5: 비동기 처리 최적화 (CompletableFuture)

### 문제 상황

영화 상세 조회 API가 4개의 독립적인 외부 호출(랭킹, 예매 가능 여부, 추천 영화, 사용자 관심도 이벤트)을 순차적으로 실행했다. 각 호출이 200ms씩 대기하므로 응답시간이 그대로 합산됐다.

```
// Before: 순차 호출 (각 200ms sleep)
getRanking(200ms) → isAvailable(200ms) → getRecommendedMovieIds(200ms) → emitUserInterest(200ms)
총 응답시간 ≈ 800ms + 서버 처리 오버헤드
```

특히 `emitUserInterest()`는 결과를 응답에 사용하지 않는 fire-and-forget 이벤트임에도 불구하고 동기 호출로 사용자 응답을 200ms 추가로 지연시켰다.

### 원인 분석

4개의 호출이 서로 독립적임에도 불구하고 순차적으로 실행되었다. 각 호출은 이전 결과를 필요로 하지 않으므로 병렬 처리가 가능한 구조였다.

### 해결 방법

`CompletableFuture`를 사용해 3개의 API 호출을 병렬로 실행하고, 모든 결과가 필요한 시점에 `.join()`으로 수집했다.

```java
public MovieDetailResult getMovieDetail(Long userNo, Long movieId) {
    Movie movie = movieRepository.findById(movieId).orElseThrow();

    // 3개 API를 동시에 비동기 호출 시작
    CompletableFuture<Integer> ranking = rankingClient.getRanking(movieId);
    CompletableFuture<Boolean> isAvailable = bookingClient.isAvailable(movieId);
    CompletableFuture<List<Long>> recommendedMovieIds = recommendClient.getRecommendedMovieIds(userNo);

    // 사용자 관심도 이벤트 발행 (fire-and-forget)
    interestEmitter.emitUserInterest(userNo, movieId);

    // 결과가 필요한 시점에 join (모든 Future가 병렬 실행 중)
    return MovieDetailResult.builder()
            .movieId(movie.getId())
            .title(movie.getTitle())
            .ranking(ranking.join())           // 300ms
            .isAvailable(isAvailable.join())   // 200ms
            .recommendedMovieIds(recommendedMovieIds.join()) // 400ms
            .build();
    // 전체 응답시간 = max(300, 200, 400) = 400ms
}
```

다수의 사용자에게 푸시 알림을 보낼 때는 `CompletableFuture.allOf()`로 모든 비동기 작업을 병렬 처리하고, `.exceptionally()`로 개별 실패가 전체를 중단시키지 않도록 처리했다.

```java
List<CompletableFuture<Void>> futures = bookingIdByUserNo.entrySet().stream()
        .map(entry -> moviePushNotificationClient.sendBookingConfirmation(...)
                .exceptionally(ex -> { log.warn("푸시 알림 실패", ex); return null; }))
        .toList();

CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
```

### 개선 결과

| 항목 | Before | After |
|------|--------|-------|
| 호출 방식 | 순차 (직렬) | 병렬 (비동기 CompletableFuture) |
| 응답시간 계산 | 4개 API 지연 합산 | 최장 단일 API 지연 (interestEmitter 제외) |
| 이론값 (각 200ms × 4) | ~800ms | ~200ms (75% 단축) |
| `emitUserInterest` | 동기, 응답 전 실행 | `@Async` fire-and-forget, 응답에 영향 없음 |

### k6 실측 결과 (EC2 운영 환경, 20 VU / 3분)

| 지표 | Before (순차 동기) | After (CompletableFuture 병렬) | 개선율 |
|------|-----------------|-------------------------------|--------|
| avg 응답시간 | 838ms | **504ms** | **40% 감소** |
| p(95) 응답시간 | 927ms | **606ms** | **35% 감소** |
| min 응답시간 | 815ms | **216ms** | - |
| 에러율 | 0% | 0% | - |
| threshold | ✅ | ✅ | - |

> After의 min 216ms는 async 스레드가 즉시 할당됐을 때의 이론값(~200ms)에 근접한 결과다.
> avg가 이론값(~250ms)보다 높은 504ms인 이유: `AsyncConfig`의 `maxPoolSize=30`인데 20 VU × 3 병렬 호출 = 최대 60개 async 스레드 동시 필요 → 큐 대기 발생. corePoolSize/maxPoolSize를 높이면 추가 개선 가능.

> 측정 결과 파일: `k6-scripts/chapter5/results/scenario5_async-*.json`

### k6 실행 명령어

```powershell
cd k6-scripts/chapter5
$env:BASE_URL="http://<YOUR_EC2_IP>:8080/api"
$env:MOVIE_ID="1"

# 영화 데이터 시딩 (최초 1회)
curl -X POST "http://<YOUR_EC2_IP>:8080/api/chapter5/movies/setup?count=100"

# Before (순차 동기)
$env:STRATEGY="sync"
k6 run async-test.js --summary-export "results/scenario5_async-before.json"

# After (CompletableFuture 병렬)
$env:STRATEGY="async"
k6 run async-test.js --summary-export "results/scenario5_async-after.json"
```

### Grafana 모니터링 관측 (시나리오 5)

**HTTP Statistics (Spring Boot Observability #19004) — 22:10~22:20 KST**

- `/api/chapter5/movies/{movieId}` (sync): Response Time avg **828ms**, max 891ms
- `/api/chapter5improved/movies/{movieId}` (async): Response Time avg **455ms**, max 553ms, min **210ms**
- Request Count 차트에서 두 엔드포인트가 시간 순서로 명확히 분리되어 확인됨

**JVM Threads (#4701) — 22:05~22:30 KST**

| 구간 | live 스레드 수 | 의미 |
|------|-------------|------|
| 테스트 전 (baseline) | ~25개 | JVM 기본 스레드만 |
| sync 구간 (22:11~22:15) | ~35개 | tomcat-exec 20개 추가 |
| async 구간 (22:15~22:19) | **~55개** | tomcat-exec + `AsyncThread-*` 풀 추가 활성화 |
| 테스트 종료 후 | ~45개 | `corePoolSize=15` 스레드가 idle 상태로 대기 유지 |

async 구간에서 스레드가 ~20개 추가되는 것이 CompletableFuture 병렬 처리의 메커니즘을 직접 입증한다. 또한 종료 후에도 스레드가 줄어들지 않는 것은 `corePoolSize=15` 설정으로 인해 executor 스레드가 상시 대기 중임을 보여준다 — 이 상시 대기가 첫 번째 async 요청의 스레드 생성 지연을 제거한다.

> **CloudWatch EC2 CPU 미사용 이유**: 시나리오 5의 "외부 API 호출"은 실제로 `Thread.sleep(200ms)`로 구현된 시뮬레이션이다. CPU를 사용하지 않는 I/O wait이므로 EC2 CPU Utilization은 sync/async 모두 3~4%로 측정되어 의미 있는 차이가 없다.

### 측정 방법

**측정 목표**: 응답시간 (직렬 합산 vs 병렬 max), 스레드 풀 사용량

| 툴 | 사용 방법 | 측정 지표 |
|----|----------|----------|
| **k6** | `async-test.js` — `STRATEGY=sync|async`, 20 VU / 3분, `p(95)<1500ms` threshold | p50/p95 응답시간 |
| **k6** | `http_req_duration` Before(직렬) / After(비동기) 비교 | 응답시간 분포 |
| **Grafana** (Dashboard #19004) | HTTP Statistics → Response Time (path: `/movies/{id}` 필터) | 응답시간 Before/After |
| **Grafana** (Dashboard #4701) | JVM Threads → live/daemon/peak 추이 | 비동기 스레드 풀 활성화 패턴 |

`AsyncConfig`의 `corePoolSize(15)`/`maxPoolSize(30)` 설정값이 Grafana JVM Threads 패널에 실시간 반영됨

### 어필 포인트

Blocking I/O 병목을 인식하고 `CompletableFuture`로 Non-blocking 비동기 전환을 적용했다. `allOf`/`join` 조합으로 결과 수집 시점을 명확히 제어하고, `exceptionally`로 부분 실패 내성을 구현한 실무 수준의 비동기 패턴을 시연했다.

---

## 시나리오 6: Redis 캐시 3종 문제 해결

### 6-A. Cache Penetration (캐시 침투)

#### 문제 상황

존재하지 않는 Book ID로 반복 요청이 들어올 때, Redis에 캐시된 값이 없어 매번 DB까지 조회하는 Cache Penetration이 발생했다. 악의적인 요청 또는 잘못된 클라이언트 코드로 인해 DB에 불필요한 부하가 지속됐다.

#### 해결 방법

DB 조회 결과가 `null`인 경우에도 `NULL_VALUE("__NULL__")` 센티넬 값을 캐시에 저장하고, 조회 시 센티넬 값이면 `null`을 반환하도록 처리했다.

```java
// Before: BookRedisService — null이면 캐시 저장 안 함 → 매 요청마다 DB 조회
if (bookFromDb != null) {
    redisTemplate.opsForValue().set(id.toString(), serialized, 1, TimeUnit.HOURS);
}
return bookFromDb; // null이면 캐시 없음 → 다음 요청도 DB 조회

// After: ImprovedBookRedisService — null이어도 sentinel 캐싱 (TTL 5분)
if (bookFromDb == null) {
    redisTemplate.opsForValue().set(id.toString(), NULL_VALUE, 5, TimeUnit.MINUTES);
    return null; // 5분간 DB 조회 없음
}
```

#### k6 실행 명령어

```powershell
cd k6-scripts/chapter6
$env:BASE_URL="http://<YOUR_EC2_IP>:8080/api"

# 데이터 시딩 (최초 1회 — 6A/B/C 공통)
curl -X POST "http://<YOUR_EC2_IP>:8080/api/chapter6/books/setup?count=1000"

# Before (BookRedisService: null 캐싱 없음 → 매 요청마다 DB 조회)
# 엔드포인트: GET /api/chapter6/books/{id}/penetration
$env:STRATEGY="before"; $env:NONEXISTENT_ID="99999"
k6 run cache-penetration-test.js --summary-export "results/scenario6a_penetration-before.json"

# After (ImprovedBookRedisService: NULL_VALUE sentinel 5분 TTL → 첫 요청 이후 캐시 히트)
# 엔드포인트: GET /api/chapter6improved/books/{id}/penetration
$env:STRATEGY="after"
k6 run cache-penetration-test.js --summary-export "results/scenario6a_penetration-after.json"
```

> k6 자체 지표(응답시간, 에러율)보다 **Grafana `db_select_total` 카운터**가 핵심 증거다. Before는 100 VU × 1분20초 내내 DB 호출이 지속되고, After는 초기 1회 이후 `db.select` 카운터가 멈추는 것을 확인한다.

#### k6 실측 결과 (EC2 운영 환경, 100 VU / 1분 20초)

| 지표 | Before (`BookRedisService`) | After (`ImprovedBookRedisService`) | 개선율 |
|------|-----------------------------|------------------------------------|--------|
| avg 응답시간 | 49ms | **17ms** | **65% 감소** |
| p(95) 응답시간 | 88ms | **30ms** | **66% 감소** |
| 에러율 | 0% | 0% | - |
| threshold | ✅ | ✅ | - |

핵심 차이는 응답시간이 아니라 **DB 호출 패턴**이다. Before는 100 VU × 80초 = 약 5,000회 이상 모두 DB까지 전파됐고, After는 첫 요청 1회만 DB 조회 후 5분간 캐시 히트. Grafana `db_select_total{service="BookRedisService"}` vs `{service="ImprovedBookRedisService"}` 카운터 비교가 핵심 증거다.

> 측정 결과 파일: `k6-scripts/chapter6/results/scenario6a_penetration-*.json`

#### 측정 방법

**측정 목표**: DB 호출 수, 캐시 미스 → DB 전파율

| 툴 | 사용 방법 | 측정 지표 |
|----|----------|----------|
| **k6** | 존재하지 않는 ID(99999)로 반복 요청, 100 VU / 1분 20초 | 응답시간, 에러율 |
| **Grafana** (커스텀 패널) | `db_select_total` Counter (service=`book-penetration` 태그) | DB 호출 수 Before vs After |
| **Grafana** (Dashboard #763) | Redis Dashboard → Keyspace Hits/Misses | Redis 히트율 변화 |

#### 어필 포인트

악의적/잘못된 요청으로부터 DB를 보호하는 방어적 캐시 설계. TTL을 짧게(5분) 설정해 실제로 데이터가 생성되었을 때 적절히 반영될 수 있도록 트레이드오프를 고려했다.

---

### 6-B. Cache Avalanche (캐시 쏠림)

#### 문제 상황

다수의 캐시 항목이 동일한 TTL로 설정되면 동시에 만료되고, 그 시점에 DB에 대량 쿼리가 한꺼번에 쏠리는 Cache Avalanche가 발생했다.

#### 원인 분석

테스트에서 1,000개의 Book 캐시를 동일 TTL로 설정 후 만료 직후 동시 요청을 발생시키자, DB 쿼리가 특정 시간에 집중되었다. Grafana `db_select_total` 카운터로 특정 구간에 DB 호출이 폭발적으로 증가하는 패턴을 관측했다.

#### 해결 방법

`TTL_BASE(1,000ms) + ThreadLocalRandom(0~500ms)` 지터를 추가해 캐시 만료 시점을 분산시켰다.

```java
private static final long TTL_BASE = 1_000; // 1초
public static final int JITTER_RANGE = 500;

// 캐시 저장 시 랜덤 지터 적용
long ttl = TTL_BASE + ThreadLocalRandom.current().nextLong(JITTER_RANGE);
redisTemplate.opsForValue().set(id.toString(), serialized, ttl, TimeUnit.MILLISECONDS);
```

#### 개선 결과

| 지표 | Before (고정 TTL) | After (Jitter 적용) | 개선율 |
|------|-----------------|-------------------|--------|
| avg 응답시간 | 182ms | **129ms** | **29% 감소** |
| p(95) 응답시간 | 492ms | **308ms** | **37% 감소** |

고정 TTL에서는 1,000개 캐시가 동시에 만료되며 DB 호출이 특정 구간에 집중된다. Jitter 적용 후 만료 시점이 분산되어 동시 DB 요청 수가 줄고 응답시간이 개선됐다.

#### 측정 방법

**측정 목표**: DB 쿼리 스파이크 패턴, 부하 평탄화 정도

| 툴 | 사용 방법 | 측정 지표 |
|----|----------|----------|
| **k6** | 1,000개 캐시 만료 직후 300 VU 동시 요청 시나리오 | DB calls 총합, 시간대별 분포 |
| **Grafana** (커스텀 패널) | `rate(db_select_total[10s])` 시계열 — 고정 TTL vs Jitter | DB 쿼리 스파이크 패턴 |
| **Grafana** (Dashboard #763) | Redis Dashboard → Connected Clients, Ops/sec | Redis 부하 분포 |
| **CloudWatch** | AWS/RDS → `CPUUtilization` — TTL 만료 시점 CPU 스파이크 비교 | DB CPU 스파이크 |

Grafana `increase(db_select_total[2m])` 패널로 Before(고정 TTL) vs After(Jitter) 구간의 DB 호출 패턴을 시각적으로 비교

#### k6 실행 명령어

```powershell
cd k6-scripts/chapter6
$env:BASE_URL="http://<YOUR_EC2_IP>:8080/api"
# (데이터 시딩은 6A에서 이미 완료)

# Before (고정 TTL=1,000ms → 동시 만료 → DB 쏠림)
# setup()이 자동으로 캐시 클리어 후 시작
$env:STRATEGY="before"; $env:TOTAL_BOOKS="1000"
k6 run cache-avalanche-test.js --summary-export "results/scenario6b_avalanche-before.json"

# After (TTL=1,000~1,500ms 지터 → 만료 분산)
$env:STRATEGY="after"
k6 run cache-avalanche-test.js --summary-export "results/scenario6b_avalanche-after.json"
```

> Grafana `increase(db_select_total{service="CacheAvalancheService"}[2m])` vs `increase(...ImprovedCacheAvalancheService...)[2m])` 패널로 Before/After DB 호출 패턴을 비교한다.

#### 측정 방법

**측정 목표**: DB 쿼리 스파이크 패턴, 부하 평탄화 정도

| 툴 | 사용 방법 | 측정 지표 |
|----|----------|----------|
| **k6** | 캐시 클리어 후 300 VU → 1,000개 Book에 분산 요청, 40초 | 응답시간, 에러율 |
| **Grafana** (커스텀 패널) | `increase(db_select_total{service="CacheAvalancheService"}[2m])` vs `ImprovedCacheAvalancheService` | DB 호출 수 Before/After 비교 |
| **Grafana** (Dashboard #763) | Redis Dashboard → Connected Clients, Ops/sec | Redis 부하 분포 |
| **CloudWatch** | AWS/RDS → `CPUUtilization` — TTL 만료 시점 CPU 스파이크 비교 | DB CPU 스파이크 |

#### k6 실측 결과 (EC2 운영 환경, 300 VU / 40초)

| 지표 | Before (고정 TTL=1,000ms) | After (TTL + Jitter 0~500ms) | 개선율 |
|------|--------------------------|------------------------------|--------|
| avg 응답시간 | 182ms | **129ms** | **29% 감소** |
| p(95) 응답시간 | 492ms | **308ms** | **37% 감소** |
| 에러율 | 0% | 0% | - |

고정 TTL(1,000ms)에서는 1,000개 캐시가 동시 만료되며 특정 시점에 DB 호출이 집중된다. Jitter 적용(1,000~1,500ms) 후 만료 시점이 분산되어 동시 DB 요청이 줄고 응답시간 및 p95가 개선됐다. Grafana `db_select_total` 패널에서 Before의 급격한 spike vs After의 완만한 분포를 시각적으로 확인할 수 있다.

> 측정 결과 파일: `k6-scripts/chapter6/results/scenario6b_avalanche-*.json`

#### 어필 포인트

`ThreadLocalRandom`으로 멀티스레드 환경에서 안전하게 지터를 생성하고, Grafana `db_select_total` 패널로 캐시 만료 집중 현상이 완화되는 것을 시각적으로 증명했다. p95 37% 감소(492ms → 308ms)가 수치 근거다.

---

### 6-C. Hot Key (분산 락 기반 Cache Stampede 방지)

#### 문제 상황

인기 있는 Book(Hot Key)의 캐시가 만료될 때 300개의 동시 요청이 모두 캐시 미스를 확인하고 DB에 동시 쿼리를 발사하는 Cache Stampede가 발생했다. 캐시의 보호 목적이 무의미해지는 상황이었다.

#### 원인 분석

300개 요청이 동시에 `redisTemplate.opsForValue().get(key)` → `null` 확인 → DB 조회 흐름을 타면서, DB에 300개의 동일한 SELECT 쿼리가 동시에 발생했다.

#### 해결 방법

Redis `setIfAbsent` 기반 분산 락 + **Double-Checked Locking** 패턴을 적용했다.

```java
private static final long LOCK_TIMEOUT_MS = 400;   // 락 타임아웃
private static final int  MAX_RETRY_COUNT = 40;     // 최대 재시도 횟수
private static final long RETRY_DELAY_MS = 5;       // 재시도 대기
private static final long RETRY_DELAY_JITTER_MS = 15; // 재시도 지터

public Book findBookByIdWithLock(Long id) throws JsonProcessingException {
    String key = id.toString();

    // 1차 캐시 확인
    String cached = redisTemplate.opsForValue().get(key);
    if (cached != null) return deserialize(cached);

    // 캐시 미스 → 분산 락 획득 시도
    String lockKey = "lock-book:" + id;
    for (int retry = 0; retry < MAX_RETRY_COUNT; retry++) {
        boolean lockAcquired = distributedLockProvider.tryLock(lockKey, LOCK_TIMEOUT_MS);

        if (!lockAcquired) {
            // 락 holder가 이미 캐시를 채웠을 수 있으므로 재확인
            cached = redisTemplate.opsForValue().get(key);
            if (cached != null) return deserialize(cached); // 캐시 히트 → 즉시 리턴
            long jitter = ThreadLocalRandom.current().nextLong(RETRY_DELAY_JITTER_MS);
            sleep(RETRY_DELAY_MS + jitter); // 5~20ms 대기 후 재시도
            continue;
        }

        try {
            // Double-Checked Locking: 락 획득 후 캐시 재확인
            cached = redisTemplate.opsForValue().get(key);
            if (cached != null) return deserialize(cached); // 이미 다른 스레드가 캐시 갱신

            // 최종적으로 캐시 없을 때만 DB 조회
            Book book = bookRepository.findById(id).orElse(null);
            setCacheWithJitter(key, book); // TTL(500ms) + Jitter(0~10ms)
            return book;
        } finally {
            distributedLockProvider.releaseLock(lockKey); // 락 반드시 해제
        }
    }
    throw new IllegalStateException("Failed to acquire lock after " + MAX_RETRY_COUNT);
}
```

#### 개선 결과

| 지표 | Before (락 없음) | After (분산 락 적용) | 개선율 |
|------|---------------|-------------------|--------|
| DB 호출 수 (300 concurrent) | ~300 | **2** | **99.3% 감소** |

DB 호출 2건: 분산 락으로 1개 스레드만 DB 조회하고 캐시를 채운 후 나머지는 캐시 히트. 이론적으로 1건이 목표이나 락 경합 타이밍에 따라 극히 드물게 2건 발생.

#### k6 실행 명령어

```powershell
cd k6-scripts/chapter6
$env:BASE_URL="http://<YOUR_EC2_IP>:8080/api"
# (데이터 시딩은 6A에서 이미 완료)

# Before: 캐시 클리어 후 동일 ID(1번 Book)에 300 VU 집중 → 캐시 미스 → DB 300건 동시 조회
curl -X DELETE "http://<YOUR_EC2_IP>:8080/api/chapter6/books/cache"
$env:STRATEGY="before"; $env:BOOK_ID="1"
k6 run hot-key-test.js --summary-export "results/scenario6c_hotkey-before.json"

# After: 캐시 클리어 후 동일 ID에 300 VU 집중 → 분산 락으로 DB 1~2건만 조회
curl -X DELETE "http://<YOUR_EC2_IP>:8080/api/chapter6/books/cache"
$env:STRATEGY="after"; $env:BOOK_ID="1"
k6 run hot-key-test.js --summary-export "results/scenario6c_hotkey-after.json"
```

> **캐시 클리어 필수**: 캐시가 이미 채워진 상태면 Before/After 모두 DB를 조회하지 않아 차이가 보이지 않는다. 각 실행 직전 `DELETE /api/chapter6/books/cache`로 Redis를 비워야 Stampede 상황이 재현된다.

#### 측정 방법

**측정 목표**: DB 호출 수 (분산 락 전후), Redis 락 키 동작 확인

| 툴 | 사용 방법 | 측정 지표 |
|----|----------|----------|
| **k6** | 동일 Book ID에 300 VU 동시 요청, 캐시 클리어 직후 | 응답시간, 에러율 |
| **Grafana** (커스텀 패널) | `db_select_total` Counter — Before(300) vs After(1~2) | DB 호출 수 감소 |
| **Grafana** (Dashboard #763) | Redis Dashboard → Keyspace Hits/Misses, Connected Clients | 락 키 동작 확인 |
| **CloudWatch** | AWS/RDS → `DatabaseConnections` 피크값 비교 | DB 커넥션 폭발 방지 |

#### k6 실측 결과 (EC2 운영 환경, 300 VU / 30초)

| 지표 | Before (락 없음) | After (분산 락 적용) | 비고 |
|------|----------------|-------------------|------|
| avg 응답시간 | 89ms | **90ms** | 거의 동일 |
| p(95) 응답시간 | 174ms | **209ms** | 거의 동일 |
| 에러율 | 0% | **0%** | - |
| DB 호출 수 (초기 burst) | ~300 | **2** | **99.3% 감소** ✅ |
| threshold (errors<5%) | ✅ | ✅ | - |

**응답시간이 거의 같은 이유**: TTL 500ms 안에서 대부분의 요청은 캐시 히트라 두 전략 모두 비슷한 평균 응답시간을 보인다. 핵심 차이는 **Grafana `db_select_total` DB 호출 수** — Before는 300 VU가 동시에 DB를 조회하고, After는 1~2건만 DB에 도달한다. k6 응답시간보다 Grafana 카운터 패널이 이 시나리오의 핵심 증거다.

> 측정 결과 파일: `k6-scripts/chapter6/results/scenario6c_hotkey-*.json`

#### 어필 포인트

실측 수치(300 → 2 DB calls)가 포트폴리오에서 가장 강력한 증거다. Cache Stampede 문제를 분산 락으로 해결하는 구체적 구현(Double-Checked Locking + 재시도 지터)을 제시하고, `setIfAbsent`의 원자성을 활용한 락 메커니즘을 이해하고 있음을 시연했다. 또한 초기 구현에서 락 획득 실패 시 캐시를 재확인하지 않는 문제를 발견하고 수정한 과정 — 락 실패 경로에서도 캐시를 먼저 확인해 재시도 슬롯 낭비를 제거 — 이 현업 표준 패턴에 대한 이해를 보여준다.

---

## 성능 측정 재현 가이드

### 핵심 메트릭 (커스텀 Micrometer)

| 메트릭 | 용도 | 관련 시나리오 |
|--------|------|-------------|
| `app.query.per_request` | 요청당 SQL 쿼리 수 (p50, p95) — 태그: path, http_method, query_type | N+1 감지 및 검증 |
| `cache.hit` / `cache.miss` | 캐시 히트율 측정 — 태그: service | Redis 캐시 3종 |
| `db.select` | 실제 DB 조회 횟수 — 태그: service | Hot Key, Cache Avalanche |

### Grafana 대시보드 ID

| Dashboard ID | 이름 | 주요 패널 |
|-------------|------|----------|
| **4701** | JVM (Micrometer) | Heap/Non-heap memory, GC pause, JVM threads |
| **19004** | Spring Boot Observability | HTTP req rate, p50/p95/p99 latency, error rate, DB pool |
| **14091** | Redis Dashboard | memory usage, ops/sec, hit rate, connected clients (※ HikariCP 전용 대시보드 미사용) |
| **763** | Redis Dashboard (시나리오 6A/B/C 전용) | memory usage, ops/sec, hit rate, connected clients |
| **커스텀** | Query Count | `app.query.per_request` p95 (path별) |

### k6 핵심 용어

부하 테스트 결과를 읽을 때 반복적으로 등장하는 용어를 정리한다.

#### 부하 설정

| 용어 | 설명 | 예시 |
|------|------|------|
| **VU (Virtual Users)** | 동시에 요청을 보내는 가상 사용자 수. 실제 유저가 브라우저 탭을 열어 반복 요청하는 것을 시뮬레이션한다. VU가 높을수록 서버 동시 처리 압력이 증가한다. | `30 VU` = 30명이 동시에 요청 중 |
| **Stages (ramp-up/down)** | 부하를 점진적으로 올리고 내리는 단계. 급격한 트래픽 증가로 인한 튀는 오차를 줄이고 실제 트래픽 패턴과 유사하게 만든다. | `30s ramp-up → 2m 유지 → 30s ramp-down` |
| **Iterations** | VU들이 `default function`을 완료한 총 횟수. `RPS × 측정시간`과 근사하며, 값이 높을수록 더 많은 요청을 처리했음을 의미한다. | `4104 iterations` |
| **Duration** | 테스트 총 실행 시간 | `3m` |

#### 응답시간 분해

k6의 `http_req_duration`은 아래 세 구간의 합이다.

```
http_req_duration = http_req_sending + http_req_waiting + http_req_receiving
```

| 지표 | 의미 | N+1 시나리오에서의 의미 |
|------|------|----------------------|
| **http_req_duration** | 클라이언트 관점 전체 응답시간 | 사용자가 체감하는 지연 |
| **http_req_waiting (TTFB)** | 요청 전송 완료 → 첫 바이트 수신까지. **서버 처리시간** (DB 쿼리 + 비즈니스 로직) | N+1 해결 효과가 가장 명확히 드러나는 지표 |
| **http_req_receiving** | 응답 바디를 모두 받는 데 걸린 시간. 응답 크기에 비례 | Fetch Join은 JOIN 결과 직렬화로 이 값이 증가 |
| **http_req_sending** | 요청을 서버로 전송하는 시간. GET이면 사실상 0 | 무시해도 되는 값 |
| **http_req_blocked** | TCP 새 연결 대기 시간. keep-alive 재사용 시 0 | 간헐적 스파이크만 확인 |

#### 응답시간 통계

| 용어 | 설명 | 해석 가이드 |
|------|------|------------|
| **p50 (중앙값, med)** | 전체 요청 중 50%가 이 시간 이하 | 전형적인 응답속도. avg보다 이상치 영향 적음 |
| **p95** | 전체 요청 중 95%가 이 시간 이하 | 포트폴리오의 **핵심 SLO 지표**. "느린 사용자 경험" 대표값 |
| **p99** | 전체 요청 중 99%가 이 시간 이하 | 최악의 케이스. p95보다 더 보수적 |
| **avg (평균)** | 전체 응답시간 평균 | 이상치(outlier)에 민감 — p95와 함께 봐야 함 |
| **max** | 측정 중 가장 느린 응답 | 단발성 이상치일 수 있어 단독 판단 지양 |

> **왜 p95를 쓰는가?** avg는 극단적으로 느린 요청 1건이 전체 평균을 왜곡할 수 있다. p95는 "상위 5% 최악 케이스를 제외한 사용자 경험"을 나타내어 SLO/SLA 지표로 산업 표준처럼 쓰인다.

#### 처리량

| 용어 | 설명 |
|------|------|
| **RPS (Requests Per Second)** | 초당 처리한 요청 수. `iterations / 측정시간(초)`로 계산. VU가 동일할 때 RPS가 높을수록 서버 처리 효율이 좋다는 의미 |
| **Throughput** | RPS와 동의어로 혼용. Grafana에서는 `rate(http_requests_total[1m])`으로 확인 |

#### 검증 및 임계값

| 용어 | 설명 |
|------|------|
| **Checks** | 응답 검증 (예: `status === 200`). `passes/fails`로 집계. checks 실패 = 비즈니스 오류 |
| **http_req_failed** | HTTP 레벨 실패율 (4xx/5xx). `value: 0` = 실패 없음 |
| **Thresholds** | 테스트 통과/실패 기준. `false` = **위반 없음 = 통과(✅)**, `true` = **위반 = 실패(❌)** |
| **errors (커스텀)** | k6 스크립트에서 직접 집계하는 비즈니스 에러율. HTTP 200이어도 응답 내용이 잘못되면 증가 |

> **Thresholds 해석 주의**: k6 JSON 결과에서 `"p(95)<2000": false`는 **"임계값 위반이 false" = 통과**를 의미한다. 직관과 반대이므로 주의.

#### iteration_duration vs http_req_duration

```
iteration_duration = http_req_duration + sleep(ms)
```

k6 스크립트에 `sleep(1)`이 있으면 `iteration_duration ≈ http_req_duration + 1000ms`가 된다.
응답시간을 볼 때는 반드시 `http_req_duration`을 기준으로 삼아야 한다.

---

### k6 스크립트 매핑

| 시나리오 | k6 스크립트 | Before 엔드포인트 | After 엔드포인트 | VU / Duration | 핵심 threshold |
|---------|-----------|----------------|----------------|---------------|----------------|
| 1 N+1 | `chapter5/n-plus-one-test.js` | `GET /api/chapter5/hotels/lazy` | `GET /api/chapter5/hotels/fetch-join` | 30 VU / 3분 | p95 < 2000ms |
| 2 Bulk | `chapter5/bulk-insert-test.js` | `POST /api/chapter5/bulk/individual` | `POST /api/chapter5/bulk/batch` | 1 VU / 1 iter | http_req_duration 비교 |
| 3 동시성 | `chapter4/concurrency-test.js` | (락 없음) | `POST /api/chapter4/events/{id}/join/pessimistic` | 100 VU / 50초 | errors < 0.05 |
| 4 통계 | `chapter3/before/order-test.js`, `chapter3/after/order-test.js` | `GET /api/chapter3/orders/stats` | `GET /api/chapter3improved/orders/stats` | 50→100 VU / 8분 | p95 < 300ms |
| 5 비동기 | `chapter5/async-test.js` | `GET /api/chapter5/movies/{id}` | `GET /api/chapter5improved/movies/{id}` | 20 VU / 3분 | p95 < 1500ms |
| 6C Hot Key | `chapter6/hot-key-test.js` | `GET /api/chapter6/books/{id}` | `GET /api/chapter6improved/books/{id}` | 300 VU / 30초 | errors < 0.05 |
| 6B Avalanche | `chapter6/cache-avalanche-test.js` | `GET /api/chapter6/books/{id}/avalanche` | `GET /api/chapter6improved/books/{id}/avalanche` | 300 VU / 40초 | errors < 0.05 |
| 6A Penetration | `chapter6/cache-penetration-test.js` | `GET /api/chapter6/books/{id}/penetration` (없는 ID) | `GET /api/chapter6improved/books/{id}/penetration` (없는 ID) | 100 VU / 1분 20초 | errors < 0.05 |

#### Before/After 전환 방법 (STRATEGY 환경변수)

```bash
# 시나리오 1: N+1
cd k6-scripts/chapter5
STRATEGY=lazy       ./run.sh n-plus-one   # Before
STRATEGY=fetch-join ./run.sh n-plus-one   # After (Fetch Join)
STRATEGY=batch-size ./run.sh n-plus-one   # After (BatchSize)

# 시나리오 2: Bulk Insert
STRATEGY=individual ./run.sh bulk-insert  # Before
STRATEGY=batch      ./run.sh bulk-insert  # After

# 시나리오 3: 동시성
cd k6-scripts/chapter4
STRATEGY=pessimistic ./run.sh             # After (Pessimistic Lock)
STRATEGY=optimistic  ./run.sh             # After (Optimistic Lock)
STRATEGY=named       ./run.sh             # After (Named Lock)

# 시나리오 5: 비동기
cd k6-scripts/chapter5
STRATEGY=sync  ./run.sh async             # Before
STRATEGY=async ./run.sh async             # After

# 시나리오 6C: Hot Key
cd k6-scripts/chapter6
STRATEGY=before ./run.sh hot-key          # Before (락 없음)
STRATEGY=after  ./run.sh hot-key          # After (분산 락)

# 시나리오 6B: Cache Avalanche
STRATEGY=before ./run.sh cache-avalanche  # Before (고정 TTL)
STRATEGY=after  ./run.sh cache-avalanche  # After (Jitter)

# EC2 환경 오버라이드
BASE_URL=http://<EC2_IP>:8080/api STRATEGY=after ./run.sh hot-key
```

#### 데이터 시딩 (최초 1회)

```bash
# HTTP API로 시딩 (앱 기동 후)
curl -X POST "http://localhost:8080/api/chapter5/hotels/setup?hotelCount=100"
curl -X POST "http://localhost:8080/api/chapter5/movies/setup?count=100"
curl -X POST "http://localhost:8080/api/chapter6/books/setup?count=1000"

# 또는 SQL 직접 실행
mysql -u portfolio_user -p portfolio < src/main/resources/chapter5/mock-data.sql
mysql -u portfolio_user -p portfolio < src/main/resources/chapter6/mock-data.sql
```

### 로컬 환경 재현 절차

```bash
# 1. 인프라 구동 (MySQL, Redis, Prometheus, Grafana)
docker-compose up -d

# 2. Grafana 접속 (http://localhost:3001) → Dashboards → Import
#    Dashboard ID 입력: 4701, 19004, 763
#    (14091은 Redis 대시보드 — chapter6 시나리오 전용)
#    Data Source: Prometheus (http://prometheus:9090)

# 3. 데이터 시딩 (최초 1회)
curl -X POST "http://localhost:8080/api/chapter5/hotels/setup?hotelCount=100"
curl -X POST "http://localhost:8080/api/chapter5/movies/setup?count=100"
curl -X POST "http://localhost:8080/api/chapter6/books/setup?count=1000"

# 4. k6 부하 테스트 실행 예시
cd k6-scripts/chapter3 && ./run.sh before                # 시나리오 4 Before
cd k6-scripts/chapter3 && ./run.sh after                 # 시나리오 4 After
cd k6-scripts/chapter4 && STRATEGY=pessimistic ./run.sh  # 시나리오 3
cd k6-scripts/chapter5 && STRATEGY=lazy ./run.sh n-plus-one     # 시나리오 1 Before
cd k6-scripts/chapter5 && STRATEGY=fetch-join ./run.sh n-plus-one # 시나리오 1 After
cd k6-scripts/chapter5 && STRATEGY=individual ./run.sh bulk-insert # 시나리오 2 Before
cd k6-scripts/chapter5 && STRATEGY=batch ./run.sh bulk-insert      # 시나리오 2 After
cd k6-scripts/chapter5 && STRATEGY=sync ./run.sh async   # 시나리오 5 Before
cd k6-scripts/chapter5 && STRATEGY=async ./run.sh async  # 시나리오 5 After
cd k6-scripts/chapter6 && STRATEGY=before ./run.sh hot-key          # 시나리오 6C Before
cd k6-scripts/chapter6 && STRATEGY=after ./run.sh hot-key           # 시나리오 6C After
cd k6-scripts/chapter6 && STRATEGY=before ./run.sh cache-avalanche  # 시나리오 6B Before
cd k6-scripts/chapter6 && STRATEGY=after ./run.sh cache-avalanche   # 시나리오 6B After
```

### 운영 환경 (AWS CloudWatch)

- 대시보드: CloudWatch → `portfolio-dashboard`
- **EC2**: `AWS/EC2` → `CPUUtilization` / `CWAgent` → `mem_used_percent`, `swap_used_percent`
- **RDS**: `AWS/RDS` → `CPUUtilization`, `DatabaseConnections`, `FreeableMemory`, `FreeStorageSpace`
- 수집 주기: 60초 (Enhanced Monitoring 60s)

### 관측 → 병목 → 개선 흐름

```
Grafana 대시보드
    ├── app.query.per_request 급증 감지
    │       └── → QueryCountInterceptor 로그 확인
    │               └── → N+1 발생 엔드포인트 특정 → Fetch Join/BatchSize 적용
    │
    ├── db.select 카운터 이상 급증 감지
    │       └── → 캐시 히트율(cache.hit/miss) 확인
    │               └── → Hot Key / Avalanche 패턴 식별 → 분산 락 / 지터 적용
    │
    └── API 응답시간 p95 목표 초과
            └── → 통계 API 집계 쿼리 확인
                    └── → Materialized View 패턴 적용
```

---

*각 시나리오는 실제 코드(`src/main/java/ding/co/backendportfolio/`)와 테스트(`src/test/java/`)에서 Before/After를 직접 확인할 수 있다.*
