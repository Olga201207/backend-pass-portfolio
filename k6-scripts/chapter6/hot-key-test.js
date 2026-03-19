import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import { BASE_URL } from '../config.js';

// 환경변수 STRATEGY: before | after (기본: before)
// before: /api/chapter6/books/{id}         (락 없음)
// after:  /api/chapter6improved/books/{id} (분산 락)
const STRATEGY = __ENV.STRATEGY || 'before';
const BOOK_ID   = __ENV.BOOK_ID   || '1';  // 동일 ID에 집중 요청

const BASE_PATH = STRATEGY === 'after' ? 'chapter6improved' : 'chapter6';

const errorRate = new Rate('errors');

export const options = {
  vus: 300,
  duration: '30s',
  thresholds: {
    errors: ['rate<0.05'],
    http_req_duration: ['p(95)<3000'],
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/${BASE_PATH}/books/${BOOK_ID}`);

  const success = check(res, {
    'status 200': (r) => r.status === 200,
  });
  errorRate.add(!success);

  sleep(0.1);
}
