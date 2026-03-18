import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import { BASE_URL } from '../../config.js';

// 환경변수 STRATEGY: sync | async (기본: sync)
const STRATEGY = __ENV.STRATEGY || 'sync';
const MOVIE_ID  = __ENV.MOVIE_ID  || '1';

const BASE_PATH = STRATEGY === 'async' ? 'chapter5improved' : 'chapter5';

const errorRate = new Rate('errors');

export const options = {
  stages: [
    { duration: '30s', target: 20 },
    { duration: '2m',  target: 20 },
    { duration: '30s', target: 0  },
  ],
  thresholds: {
    http_req_duration: ['p(95)<1500'],  // sync ~900ms, async ~400ms
    errors: ['rate<0.05'],
  },
};

export default function () {
  const res = http.get(
    `${BASE_URL}/${BASE_PATH}/movies/${MOVIE_ID}?userId=1`
  );

  check(res, {
    'status 200': (r) => r.status === 200,
    'p95 < 1500ms': (r) => r.timings.duration < 1500,
  }) || errorRate.add(1);

  sleep(0.5);
}

export function handleSummary(data) {
  const ts = new Date().toISOString().slice(0, 16).replace(/[T:]/g, '-');
  return {
    [`results/async-${STRATEGY}-${ts}.json`]: JSON.stringify(data),
  };
}
