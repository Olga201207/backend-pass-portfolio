import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import { BASE_URL } from '../config.js';

// STRATEGY: before | after (기본: before)
// before → GET /api/chapter3/orders/stats        (매 요청마다 GROUP BY 집계)
// after  → GET /api/chapter3improved/orders/stats (사전 집계 테이블 단순 조회)
const STRATEGY = __ENV.STRATEGY || 'before';

const BASE_PATH = STRATEGY === 'after'
  ? `${BASE_URL}/chapter3improved/orders/stats`
  : `${BASE_URL}/chapter3/orders/stats`;

const errorRate = new Rate('errors');

export const options = {
  stages: [
    { duration: '1m', target: 50 },   // ramp-up
    { duration: '2m', target: 50 },   // 50 VU 유지
    { duration: '1m', target: 100 },  // ramp-up
    { duration: '3m', target: 100 },  // 100 VU 유지
    { duration: '1m', target: 0 },    // ramp-down
  ],
  thresholds: {
    errors: ['rate<0.05'],
    http_req_duration: ['p(95)<3000'],
  },
};

export default function () {
  const res = http.get(`${BASE_PATH}?minAmount=500000&size=20`, {
    headers: { 'Content-Type': 'application/json' },
  });

  const success = check(res, {
    'stats status 200': (r) => r.status === 200,
  });
  errorRate.add(!success);

  sleep(1);
}
