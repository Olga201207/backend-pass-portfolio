import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import { BASE_URL } from '../config.js';

// 환경변수 STRATEGY: lazy | fetch-join | batch-size | entity-graph (기본: lazy)
const STRATEGY = __ENV.STRATEGY || 'lazy';

const STRATEGY_PATH = {
  'lazy':         'lazy',
  'fetch-join':   'fetch-join',
  'batch-size':   'batch-size',
  'entity-graph': 'entity-graph',
};

const path = STRATEGY_PATH[STRATEGY] || 'lazy';

const errorRate = new Rate('errors');

export const options = {
  stages: [
    { duration: '30s', target: 30  },
    { duration: '2m',  target: 30  },
    { duration: '30s', target: 0   },
  ],
  thresholds: {
    http_req_duration: ['p(95)<2000'],
    errors: ['rate<0.05'],
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/chapter5/hotels/${path}`);

  check(res, {
    'status 200': (r) => r.status === 200,
  }) || errorRate.add(1);

  sleep(1);
}
