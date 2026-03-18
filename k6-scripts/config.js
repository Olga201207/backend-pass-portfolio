export const BASE_URL      = __ENV.BASE_URL      || 'http://localhost:8080/api';
export const HOST_URL      = __ENV.BASE_URL
  ? __ENV.BASE_URL.replace('/api', '')
  : 'http://localhost:8080';
export const VU_MAX        = parseInt(__ENV.VU_MAX        || '100');
export const DURATION      = __ENV.DURATION      || '3m';
export const P95_MS        = parseInt(__ENV.P95_MS        || '500');
export const ERROR_RATE    = parseFloat(__ENV.ERROR_RATE  || '0.1');
export const TEST_EMAIL    = __ENV.TEST_EMAIL    || 'user1@test.com';
export const TEST_PASSWORD = __ENV.TEST_PASSWORD || 'password123';
