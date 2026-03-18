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
| 3 | 동시성 제어 (100 concurrent) | Race Condition 발생 | 정원 초과 0건 | - |
| 4 | 통계 쿼리 (매 요청 집계) | 전체 집계 on-demand | 사전 집계 테이블 조회 | p95 목표 300ms |
| 5 | 비동기 처리 (외부 API 3개) | 응답시간 = 각 지연 합산 | 응답시간 = 최장 단일 지연 | 직렬 → 병렬 |
| 6B | Cache Avalanche | 31,145 DB calls / CV 0.81 | 24,263 DB calls / CV 0.36 | 22% 감소, 변동성 56% 감소 |
| 6C | Hot Key (동시 300) | 300 DB calls | 2 DB calls | 99.3% 감소 |

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
| **Grafana** (Dashboard #6756) | Spring Boot Statistics → HTTP Request Duration (path 필터) | p95 응답시간 |
| **Grafana** (Dashboard #14091) | HikariCP → Active Connections | DB 커넥션 사용량 |
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

`BatchContext` 모니터링으로 배치 실행 시간을 측정한 결과, JPA의 `save()` 반복 호출이 각각 독립적인 `PreparedStatement`를 생성하고 실행하며 1,440번의 DB 왕복을 유발하는 것을 확인했다.

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

### 측정 방법

**측정 목표**: 배치 실행 시간, DB 커넥션 점유 시간, 메모리 영향

| 툴 | 사용 방법 | 측정 지표 |
|----|----------|----------|
| **Grafana** (Dashboard #6756) | HTTP Request Duration (batch 엔드포인트 필터) | 단건 배치 실행 시간 |
| **Grafana** (Dashboard #4701) | JVM (Micrometer) → Heap Memory / GC Pause Time | 메모리 사용, GC 영향 |
| **k6** | 1 VU 단발 실행 (`k6 run --vus 1 --iterations 1`) → `http_req_duration` | 단건 실행 시간 측정 |
| **CloudWatch** | AWS/RDS → `CPUUtilization`, `DatabaseConnections` 시계열 | INSERT 구간 DB 부하 스파이크 |

Before/After 비교: `BatchContext` 로그의 실행 시간 + Grafana HTTP Request Duration 비교

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

**전략 3. Named Lock**
```java
// GET_LOCK('event:1', 3) → 3초 타임아웃으로 MySQL 사용자 정의 락 획득
// REQUIRES_NEW 트랜잭션으로 락과 데이터 트랜잭션 분리
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void joinEventWithNamedLock(Long eventId, Long memberId) { ... }
```
- 분산 환경(멀티 인스턴스)에서도 동작
- 별도 DB 커넥션 사용으로 커넥션 풀 고려 필요

### 개선 결과

| 전략 | 100 concurrent 결과 | 특징 |
|------|-------------------|------|
| 락 없음 (before) | 정원 초과 발생 | Race Condition |
| 비관적 락 | 정원 초과 0건 | 즉시 DB 잠금, 데드락 주의 |
| 낙관적 락 | 정원 초과 0건 | 재시도 로직 필요, 저충돌 최적 |
| Named Lock | 정원 초과 0건 | 분산 환경 적합 |

### 측정 방법

**측정 목표**: 처리량(RPS), DB 커넥션 경합, 오류율, 락 전략별 응답시간

| 툴 | 사용 방법 | 측정 지표 |
|----|----------|----------|
| **k6** | `chapter4-load-test.js` — 50→100 VU ramp-up, `errors` rate < 0.1 threshold | throughput, error rate |
| **k6** | `http_req_duration{scenario:order-search}` p95 < 400ms 임계값으로 락 전략별 비교 | 응답시간 분포 |
| **Grafana** (Dashboard #14091) | HikariCP → `hikaricp_connections_pending` (커넥션 대기 큐) | DB 커넥션 경합 |
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
| 응답시간 목표 | 미달 | p95 300ms 달성 |
| 데이터 신선도 | 실시간 | 최대 3분 지연 |

### 측정 방법

**측정 목표**: p95 응답시간, DB CPU, 처리량

| 툴 | 사용 방법 | 측정 지표 |
|----|----------|----------|
| **k6** | `order-load-test.js` — `/api/orders/stats?minAmount=500000` 엔드포인트, p95<300ms threshold | p95 latency, throughput, error rate |
| **k6** | Stage 1(50VU 2분) → Stage 2(100VU 3분) → Stage 3(down) 부하 패턴 | 고트래픽 시 응답 안정성 |
| **Grafana** (Dashboard #6756) | HTTP Request Duration (path: `/api/orders/stats` 필터) | Before/After p95 응답시간 |
| **Grafana** (Dashboard #6756) | HTTP Request Rate (RPS) | 처리량 비교 |
| **CloudWatch** | AWS/RDS → `CPUUtilization` (Before: 매 요청 집계 시 CPU 스파이크 vs After: 평탄) | DB CPU 부하 패턴 |
| **CloudWatch** | AWS/RDS → `DatabaseConnections` | 커넥션 사용 패턴 |

### 어필 포인트

**실시간성 vs DB 부하**라는 트레이드오프를 비즈니스 요구사항(통계는 약간의 지연 허용)에 맞게 판단했다. 스케줄링 + 반정규화로 집계 비용을 시간적으로 분산시키는 실무 패턴을 구현했다.

---

## 시나리오 5: 비동기 처리 최적화 (CompletableFuture)

### 문제 상황

영화 상세 조회 API가 3개의 외부 API(랭킹, 예매 가능 여부, 추천 영화)를 순차적으로 호출했다. 각 API의 응답 지연이 그대로 합산되어 전체 응답시간이 늘어났다.

```
// Before: 순차 호출
랭킹 API (300ms) → 예매 가능 여부 API (200ms) → 추천 영화 API (400ms)
총 응답시간 = 300 + 200 + 400 = 900ms
```

### 원인 분석

3개의 외부 API 호출이 서로 독립적임에도 불구하고 순차적으로 실행되었다. 각 API 호출은 이전 API의 결과를 필요로 하지 않으므로 병렬 처리가 가능한 구조였다.

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
| 호출 방식 | 순차 (직렬) | 병렬 (비동기) |
| 응답시간 계산 | 각 API 지연 합산 | 최장 단일 API 지연 |
| 예시 (300+200+400ms) | 900ms | **400ms** (55% 단축) |

### 측정 방법

**측정 목표**: 응답시간 (직렬 합산 vs 병렬 max), 스레드 풀 사용량

| 툴 | 사용 방법 | 측정 지표 |
|----|----------|----------|
| **k6** | 커스텀 스크립트 — `/api/movies/{id}` 엔드포인트, 20 VU / 2분 | p50/p95 응답시간 |
| **k6** | `http_req_duration` 히스토그램으로 Before(직렬) / After(비동기) 분포 비교 | 응답시간 분포 |
| **Grafana** (Dashboard #4701) | JVM Threads → tomcat-exec pool + async-task pool thread 수 | 스레드 사용량 |
| **Grafana** (Dashboard #6756) | HTTP Request Duration 시계열 | 응답시간 Before/After |
| **CloudWatch** | AWS/EC2 → `CPUUtilization` (비동기 처리로 인한 CPU 활용률 변화) | CPU 사용 패턴 |

`AsyncConfig`의 `corePoolSize`/`maxPoolSize` 설정값이 Grafana JVM Threads 패널에 실시간 반영됨

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
// NULL 결과도 캐싱 (TTL 5분)
if (bookFromDb == null) {
    redisTemplate.opsForValue().set(id.toString(), NULL_VALUE, 5, TimeUnit.MINUTES);
    return null;
}
```

#### 측정 방법

**측정 목표**: DB 호출 수, 캐시 미스 → DB 전파율

| 툴 | 사용 방법 | 측정 지표 |
|----|----------|----------|
| **k6** | 존재하지 않는 ID로 반복 요청 (300 VU, 1분) | DB call 수 (`db.select` 카운터) |
| **Grafana** (커스텀 패널) | `cache_miss_total` / `db_select_total` Counter (service 태그 필터) | 캐시 미스 → DB 전파율 |
| **Grafana** (Dashboard #763) | Redis Dashboard → Hit Rate (`redis_keyspace_hits / (hits+misses)`) | Redis 히트율 |

#### 어필 포인트

악의적/잘못된 요청으로부터 DB를 보호하는 방어적 캐시 설계. TTL을 짧게(5분) 설정해 실제로 데이터가 생성되었을 때 적절히 반영될 수 있도록 트레이드오프를 고려했다.

---

### 6-B. Cache Avalanche (캐시 쏠림)

#### 문제 상황

다수의 캐시 항목이 동일한 TTL로 설정되면 동시에 만료되고, 그 시점에 DB에 대량 쿼리가 한꺼번에 쏠리는 Cache Avalanche가 발생했다.

#### 원인 분석

테스트에서 1,000개의 Book 캐시를 동일 TTL로 설정 후 만료 직후 동시 요청을 발생시키자, DB 쿼리가 특정 시간에 집중되었다. 변동계수(CV)로 쿼리 부하의 시간적 분산을 측정했다.

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
| 총 DB 쿼리 수 | 31,145 | 24,263 | **22% 감소** |
| 변동계수 (CV) | 0.81 | 0.36 | **56% 감소** |

CV(변동계수) 0.81 → 0.36: DB 쿼리 부하의 시간적 편차가 절반 이하로 감소, 트래픽이 고르게 분산됨을 수치로 증명했다.

#### 측정 방법

**측정 목표**: DB 쿼리 스파이크 패턴, 부하 평탄화 정도

| 툴 | 사용 방법 | 측정 지표 |
|----|----------|----------|
| **k6** | 1,000개 캐시 만료 직후 300 VU 동시 요청 시나리오 | DB calls 총합, 시간대별 분포 |
| **Grafana** (커스텀 패널) | `rate(db_select_total[10s])` 시계열 — 고정 TTL vs Jitter | DB 쿼리 스파이크 패턴 |
| **Grafana** (Dashboard #763) | Redis Dashboard → Connected Clients, Ops/sec | Redis 부하 분포 |
| **CloudWatch** | AWS/RDS → `CPUUtilization` — TTL 만료 시점 CPU 스파이크 비교 | DB CPU 스파이크 |

CV(변동계수) = stddev(DB calls per second) / mean 으로 부하 평탄화 정도 정량화

#### 어필 포인트

`ThreadLocalRandom`으로 멀티스레드 환경에서 안전하게 지터를 생성하고, CV 지표를 통해 단순 쿼리 수 감소뿐만 아니라 부하 평탄화 효과를 정량화했다.

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
| DB 호출 수 (300 concurrent) | 300 | **2** | **99.3% 감소** |
| 변동계수 (CV) | 0.51 | **0.00** | 완전 평탄화 |

DB 호출 2건: 락 경합 과정에서 극히 드물게 발생한 2건으로, 이론적으로 1건이 목표.

#### 측정 방법

**측정 목표**: DB 호출 수 (분산 락 전후), Redis 락 키 동작 확인

| 툴 | 사용 방법 | 측정 지표 |
|----|----------|----------|
| **k6** | 동일 Book ID에 300 VU 동시 요청 (캐시 만료 직후 타이밍) | DB calls 총합 (`db.select` 카운터) |
| **Grafana** (커스텀 패널) | `db_select_total` Counter — 분산 락 전후 비교 (service 태그 필터) | DB 호출 수 (300 → 2) |
| **Grafana** (Dashboard #763) | Redis Dashboard → Connected Clients, Keyspace Hits | Redis 락 키 동작 확인 |
| **CloudWatch** | AWS/RDS → `DatabaseConnections` 피크값 비교 | DB 커넥션 폭발 방지 확인 |

#### 어필 포인트

실측 수치(300 → 2 DB calls)가 포트폴리오에서 가장 강력한 증거다. Cache Stampede 문제를 분산 락으로 해결하는 구체적 구현(Double-Checked Locking + 재시도 지터)을 제시하고, `setIfAbsent`의 원자성을 활용한 락 메커니즘을 이해하고 있음을 시연했다.

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
| **6756** | Spring Boot Statistics | HTTP req rate, p50/p95/p99 latency, error rate, DB pool |
| **14091** | HikariCP | active/idle/pending connections, acquisition time |
| **763** | Redis Dashboard | memory usage, ops/sec, hit rate, connected clients |
| **커스텀** | Query Count | `app.query.per_request` p95 (path별) |

### k6 스크립트 매핑

| 파일 | 대상 시나리오 | VU / Duration | 핵심 threshold |
|------|-------------|---------------|----------------|
| `load-test.js` | 시나리오 1, 2 (N+1, Bulk) | 100→300 VU / 23분 | p95 < 2000ms |
| `chapter4-load-test.js` | 시나리오 3 (동시성) | 50→100 VU / 8분 | errors < 0.1 |
| `order-load-test.js` | 시나리오 4 (통계 쿼리) | 50→100 VU / 8분 | p95 < 300ms |
| `k6-test.js` | 시나리오 5 (비동기) | 10 VU / 10분 | p95 < 500ms |
| `cpu-test.js` | CPU 부하 측정 | 0→50→0 VU / 30분 | — |
| `memory-test.js` | Memory 부하 측정 | 0→10→0 VU / 2분 | — |

### 로컬 환경 재현 절차

```bash
# 1. 인프라 구동 (MySQL, Redis, Prometheus, Grafana)
docker-compose up -d

# 2. Grafana 접속 (http://localhost:3001) → Dashboards → Import
#    Dashboard ID 입력: 4701, 6756, 14091, 763
#    Data Source: Prometheus (http://prometheus:9090)

# 3. 애플리케이션 구동 후 k6 부하 테스트 실행 예시
k6 run k6-scripts/order-load-test.js          # 시나리오 4
k6 run k6-scripts/chapter4-load-test.js       # 시나리오 3
k6 run --vus 1 --iterations 1 k6-scripts/load-test.js  # 시나리오 2 단발
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
