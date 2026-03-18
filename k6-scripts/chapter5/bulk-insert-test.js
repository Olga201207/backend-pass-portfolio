import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';
import { BASE_URL } from '../../config.js';

// 환경변수 STRATEGY: individual | batch (기본: individual)
const STRATEGY = __ENV.STRATEGY || 'individual';
const COUNT     = __ENV.COUNT    || '1440';

const errorRate = new Rate('errors');

// 단발 실행 (1 VU, 1 iteration) — 실행시간 자체가 지표
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    http_req_duration: ['p(95)<60000'], // 60초 이내
    errors: ['rate<0.01'],
  },
};

export default function () {
  // 측정 전 기존 데이터 삭제
  const delRes = http.del(`${BASE_URL}/chapter5/bulk`);
  check(delRes, { 'delete ok': (r) => r.status === 200 });

  // INSERT 실행
  const insertRes = http.post(
    `${BASE_URL}/chapter5/bulk/${STRATEGY}?count=${COUNT}`,
    null,
    { timeout: '120s' }
  );

  check(insertRes, {
    'insert status 200': (r) => r.status === 200,
  }) || errorRate.add(1);
}

export function handleSummary(data) {
  const ts = new Date().toISOString().slice(0, 16).replace(/[T:]/g, '-');
  return {
    [`results/bulk-insert-${STRATEGY}-${ts}.json`]: JSON.stringify(data),
  };
}
