import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import { BASE_URL } from '../config.js';

// Cache Penetration: 존재하지 않는 ID 반복 요청
// 캐시에도 DB에도 없는 ID → DB까지 전파되는지 확인
// before: BookRedisService        → null 캐싱 없음 → 매 요청마다 DB 조회
// after:  ImprovedBookRedisService → NULL_VALUE sentinel (5분 TTL) → 첫 요청 후 캐시 히트

const STRATEGY    = __ENV.STRATEGY    || 'before';
// 존재하지 않는 ID (Book이 최대 1000개라고 가정, 9999 이상은 DB에 없음)
const NONEXISTENT_ID = parseInt(__ENV.NONEXISTENT_ID || '99999');

const BASE_PATH = STRATEGY === 'after' ? 'chapter6improved' : 'chapter6';

const errorRate = new Rate('errors');

export const options = {
  stages: [
    { duration: '10s', target: 100 },
    { duration: '1m',  target: 100 },
    { duration: '10s', target: 0   },
  ],
  thresholds: {
    errors: ['rate<0.05'],
    http_req_duration: ['p(95)<2000'],
  },
};

export function setup() {
  // 캐시 클리어 후 시작
  http.del(`${BASE_URL}/chapter6/books/cache`);
}

export default function () {
  const res = http.get(`${BASE_URL}/${BASE_PATH}/books/${NONEXISTENT_ID}/penetration`);

  // 존재하지 않는 ID → 200 (null body) 또는 404 모두 허용
  const success = check(res, {
    'no server error': (r) => r.status !== 500,
  });
  errorRate.add(!success);

  sleep(0.1);
}
