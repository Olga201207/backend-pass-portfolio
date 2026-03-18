import http from 'k6/http';
import { check, sleep } from 'k6';
import { HOST_URL } from '../config.js';

export const options = {
  stages: [
    { duration: '30s', target: 20 },
    { duration: '1m', target: 20 },
    { duration: '30s', target: 0 },
  ],
};

export default function () {
  const baseUrl = HOST_URL;

  // 피보나치
  const fibRes = http.get(`${baseUrl}/api/chapter2/test/load/fibonacci/45`);
  check(fibRes, { 'fibonacci status was 200': (r) => r.status === 200 });

  // 소수 찾기
  const primeRes = http.get(`${baseUrl}/api/chapter2/test/load/prime/10000`);
  check(primeRes, { 'prime status was 200': (r) => r.status === 200 });

  // 행렬 곱셈
  const matrixRes = http.get(`${baseUrl}/api/chapter2/test/load/matrix/100`);
  check(matrixRes, { 'matrix status was 200': (r) => r.status === 200 });

  // 정렬
  const sortRes = http.get(`${baseUrl}/api/chapter2/test/load/sort/100000`);
  check(sortRes, { 'sort status was 200': (r) => r.status === 200 });

  sleep(1);
}

// 결과 처리
export function handleSummary(data) {
  const timestamp = new Date().toISOString().slice(0, 16).replace(/[T:]/g, '-');
  return {
    [`results/resource-test-${timestamp}.json`]: JSON.stringify(data),
  };
}
