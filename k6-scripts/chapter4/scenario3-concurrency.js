/**
 * 시나리오 3: 동시성 제어 부하 테스트
 *
 * [테스트 목적]
 * 선착순 이벤트(정원 30명)에 100명이 동시 요청을 보낼 때
 * 락 전략별로 처리량(RPS), 응답시간(p95), 에러율이 어떻게 다른지 비교한다.
 *
 * [왜 부하 테스트가 필수인가]
 * Race Condition은 단건 요청으로 재현 불가 — 반드시 동시 요청이 있어야 발생한다.
 * k6가 없으면 "정원 초과 0건"을 증명할 수단이 없다.
 *
 * [STRATEGY 옵션]
 *   pessimistic  → SELECT FOR UPDATE (즉시 DB 잠금)
 *   optimistic   → @Version 낙관적 락 + 재시도
 *   redis-named  → Redis setIfAbsent 분산 락
 *
 * [실행 방법]
 *   $env:BASE_URL="http://<YOUR_EC2_IP>:8080/api"
 *   $env:TEST_EMAIL="k6test@test.com"; $env:TEST_PASSWORD="password123"
 *
 *   # 이벤트 생성 (최초 1회)
 *   $res = Invoke-RestMethod -Method POST "$env:BASE_URL/chapter4/events/setup?maxParticipants=30"
 *   $env:EVENT_ID = $res.eventId
 *
 *   # 전략별 실행
 *   $env:STRATEGY="pessimistic"
 *   curl -X POST "$env:BASE_URL/chapter4/events/$env:EVENT_ID/reset"
 *   k6 run scenario3-concurrency.js --summary-export "results/scenario3_pessimistic.json"
 *
 *   $env:STRATEGY="optimistic"
 *   curl -X POST "$env:BASE_URL/chapter4/events/$env:EVENT_ID/reset"
 *   k6 run scenario3-concurrency.js --summary-export "results/scenario3_optimistic.json"
 *
 *   $env:STRATEGY="redis-named"
 *   curl -X POST "$env:BASE_URL/chapter4/events/$env:EVENT_ID/reset"
 *   k6 run scenario3-concurrency.js --summary-export "results/scenario3_redis-named.json"
 *
 * [모니터링 포인트 — Grafana]
 *   - JVM Threads: 100 VU 진입 시 스레드 수 증가 패턴 (전략별 차이)
 *   - HikariCP pending connections: redis-named 전략에서 DB 커넥션 경합 없음 확인
 *   - HTTP Request Duration (p95): 전략별 응답시간 비교
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Counter } from 'k6/metrics';
import { BASE_URL, TEST_EMAIL, TEST_PASSWORD } from '../config.js';

const STRATEGY = __ENV.STRATEGY || 'pessimistic';
const EVENT_ID  = __ENV.EVENT_ID  || '1';
const MAX_PARTICIPANTS = parseInt(__ENV.MAX_PARTICIPANTS || '30');

const errorRate    = new Rate('errors');
const joinSuccess  = new Counter('join_success');   // 200: 정상 참여
const joinFull     = new Counter('join_full');       // 400: 정원 초과 (정상 거절)

export const options = {
  stages: [
    // Phase 1: 준비 — 소수 VU로 JWT 토큰 발급 및 서버 워밍업
    { duration: '5s',  target: 10  },
    // Phase 2: 경쟁 발생 — 100 VU 순간 집중 (동시성 문제 재현 구간)
    { duration: '5s',  target: 100 },
    // Phase 3: 지속 부하 — 정원 초과 여부 및 락 안정성 검증
    { duration: '30s', target: 100 },
    // Phase 4: ramp-down
    { duration: '10s', target: 0   },
  ],
  thresholds: {
    // 비즈니스 에러(5xx)는 0% — 4xx(정원 초과)는 허용
    'errors':             ['rate<0.01'],
    // p95 3초 이내 (Redis Named Lock은 재시도 오버헤드로 다소 높을 수 있음)
    'http_req_duration':  ['p(95)<3000'],
  },
};

let authToken = null;

export function setup() {
  const loginRes = http.post(
    `${BASE_URL}/chapter2/members/login`,
    JSON.stringify({ email: TEST_EMAIL, password: TEST_PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  if (loginRes.status !== 200) {
    console.error(`[setup] 로그인 실패: ${loginRes.status}`);
    return { token: null };
  }
  return { token: loginRes.json('token') };
}

export default function (data) {
  const headers = {
    'Content-Type': 'application/json',
    ...(data.token ? { Authorization: `Bearer ${data.token}` } : {}),
  };

  const res = http.post(
    `${BASE_URL}/chapter4/events/${EVENT_ID}/join/${STRATEGY}`,
    null,
    { headers }
  );

  // 200: 참여 성공 / 400: 정원 초과 (의도된 거절) — 둘 다 정상
  // 500: 서버 에러 (Lock 실패, Connection Pool 고갈 등) — 비정상
  const isBusinessOk = check(res, {
    '200 참여 성공 또는 400 정원 초과': (r) => r.status === 200 || r.status === 400,
  });
  errorRate.add(!isBusinessOk);

  if (res.status === 200) joinSuccess.add(1);
  if (res.status === 400) joinFull.add(1);

  sleep(0.05); // 50ms — 현실적인 클라이언트 간격
}

export function teardown(data) {
  // 테스트 종료 후 실제 참여자 수 검증
  // join_success 카운터가 MAX_PARTICIPANTS를 초과하면 Race Condition 발생
  console.log(`\n========== 결과 요약 ==========`);
  console.log(`전략: ${STRATEGY}`);
  console.log(`정원: ${MAX_PARTICIPANTS}명`);
  console.log(`join_success는 k6 summary에서 확인 — MAX_PARTICIPANTS(${MAX_PARTICIPANTS}) 초과 여부가 핵심`);
  console.log(`================================\n`);
}
