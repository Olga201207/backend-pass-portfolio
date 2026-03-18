import http from 'k6/http';
import { check, sleep } from 'k6';
import { HOST_URL } from '../config.js';

const BASE_URL = HOST_URL;

export const options = {
  stages: [
    { duration: '10m', target: 50 },  // 동시 사용자 수 감소
    { duration: '10m', target: 10 },  // 부하 유지
    { duration: '10m', target: 0 },   // 정리
  ]
};

export default function () {
  // 요청별 타임아웃 설정
  const params = {
    timeout: '30s',  // 30초 타임아웃
  };

  // CPU 집약적인 작업 실행 (파라미터 감소)
  const heavyRes = http.get(`${BASE_URL}/api/chapter2/test/load/cpu/heavy/2`, params);
  check(heavyRes, { 'heavy CPU task status was 200': (r) => r.status === 200 });

  // 피보나치 수 감소
  const fibRes = http.get(`${BASE_URL}/api/chapter2/test/load/fibonacci/40`, params);
  check(fibRes, { 'fibonacci status was 200': (r) => r.status === 200 });

  // 소수 범위 감소
  const primeRes = http.get(`${BASE_URL}/api/chapter2/test/load/prime/50000`, params);
  check(primeRes, { 'prime status was 200': (r) => r.status === 200 });

  sleep(2); // 대기 시간 증가
}

// 결과 처리
export function handleSummary(data) {
  const timestamp = new Date().toISOString().slice(0, 16).replace(/[T:]/g, '-');
  return {
    [`results/cpu-test-${timestamp}.json`]: JSON.stringify(data),
  };
}
