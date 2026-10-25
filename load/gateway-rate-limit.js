// One subject exceeds its token bucket: expect 429 + Retry-After after the burst, never a 5xx.
// Run: k6 run -e RATE_LIMIT_BURST=100 load/gateway-rate-limit.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE, token, auth } from './common.js';

const limited = new Counter('rate_limited');
export const options = {
  scenarios: { flood: { executor: 'constant-arrival-rate', rate: 300, timeUnit: '1s', duration: '20s',
                        preAllocatedVUs: 50, maxVUs: 150 } },
  thresholds: { 'checks': ['rate>0.999'], 'rate_limited': ['count>0'] },
};

export function setup() {
  return { doctor: token('k6-flood', ['DOCTOR']) };
}

export default function (data) {
  const r = http.get(`${BASE}/api/v1/beds/nearest?lat=12.97&lon=77.59`,
    Object.assign(auth(data.doctor), { responseCallback: http.expectedStatuses(200, 429) }));
  if (r.status === 429) limited.add(1);
  check(r, { '200, or 429 with Retry-After': (x) => x.status === 200 || (x.status === 429 && !!x.headers['Retry-After']) });
}
