import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import { BASE_URL, TEST_EMAIL, TEST_PASSWORD } from '../config.js';

// 환경변수 STRATEGY: pessimistic | optimistic | named (기본: pessimistic)
const STRATEGY = __ENV.STRATEGY || 'pessimistic';
const EVENT_ID  = __ENV.EVENT_ID  || '1';

const errorRate = new Rate('errors');

export const options = {
  stages: [
    { duration: '10s', target: 100 },  // 10초 안에 100 VU로 증가
    { duration: '30s', target: 100 },  // 100 VU 30초 유지
    { duration: '10s', target: 0 },    // 종료
  ],
  thresholds: {
    errors: ['rate<0.05'],             // 에러율 5% 미만
    http_req_duration: ['p(95)<3000'], // p95 3초 이내
  },
};

let token = null;

export function setup() {
  const loginRes = http.post(`${BASE_URL}/members/login`, JSON.stringify({
    email: TEST_EMAIL,
    password: TEST_PASSWORD,
  }), { headers: { 'Content-Type': 'application/json' } });

  if (loginRes.status === 200) {
    return { token: loginRes.json('token') };
  }
  return { token: null };
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

  check(res, {
    'join status 200 or 400': (r) => r.status === 200 || r.status === 400,
  }) || errorRate.add(1);

  sleep(0.1);
}
