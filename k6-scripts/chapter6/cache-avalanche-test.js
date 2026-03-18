import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import { BASE_URL } from '../../config.js';

// 환경변수 STRATEGY: before | after (기본: before)
// before: /api/chapter6/books/{id}/avalanche         (고정 TTL)
// after:  /api/chapter6improved/books/{id}/avalanche (Jitter 적용)
const STRATEGY   = __ENV.STRATEGY   || 'before';
const TOTAL_BOOKS = parseInt(__ENV.TOTAL_BOOKS || '1000');

const BASE_PATH = STRATEGY === 'after' ? 'chapter6improved' : 'chapter6';

const errorRate = new Rate('errors');

export const options = {
  stages: [
    { duration: '10s', target: 300 },  // 캐시 만료 직후 급격한 부하
    { duration: '20s', target: 300 },
    { duration: '10s', target: 0   },
  ],
  thresholds: {
    errors: ['rate<0.05'],
    http_req_duration: ['p(95)<3000'],
  },
};

export function setup() {
  // 캐시 클리어 (TTL 만료 시나리오 재현)
  http.del(`${BASE_URL}/chapter6/books/cache`);
}

export default function () {
  // 1000개 Book에 고르게 요청 분산 (Avalanche 패턴)
  const bookId = Math.floor(Math.random() * TOTAL_BOOKS) + 1;
  const res = http.get(`${BASE_URL}/${BASE_PATH}/books/${bookId}/avalanche`);

  check(res, {
    'status 200': (r) => r.status === 200,
  }) || errorRate.add(1);

  sleep(0.05);
}

export function handleSummary(data) {
  const ts = new Date().toISOString().slice(0, 16).replace(/[T:]/g, '-');
  return {
    [`results/cache-avalanche-${STRATEGY}-${ts}.json`]: JSON.stringify(data),
  };
}
