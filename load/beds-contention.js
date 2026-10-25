// Many clients race for a few ICU beds. Invariant checked by thresholds: no 5xx, and every hold is 201 or 409
// (never an oversell). PATIENT_ID must be a Verhoeff-valid health id (default is the one from VerhoeffTest).
// Run: k6 run load/beds-contention.js
import http from 'k6/http';
import { check } from 'k6';
import { BASE, token, auth } from './common.js';

export const options = {
  scenarios: { race: { executor: 'constant-arrival-rate', rate: 200, timeUnit: '1s', duration: '60s',
                       preAllocatedVUs: 50, maxVUs: 200 } },
  thresholds: {
    'http_req_failed{expected_response:true}': ['rate<0.01'],
    'checks{kind:hold}': ['rate>0.999'],
    'http_req_duration{name:hold}': ['p(95)<250'],   // target, not a measured number
  },
};

export function setup() {
  const admin = token('k6-admin', ['ADMIN']);
  const doctor = token('k6-doctor', ['DOCTOR']);
  for (let i = 0; i < 5; i++) {
    http.put(`${BASE}/api/v1/hospitals/K6-H${i}`, JSON.stringify(
      { name: `k6 hospital ${i}`, lat: 12.97 + i * 0.01, lon: 77.59, icuBeds: 3, occupied: 0 }), auth(admin));
  }
  return { doctor };
}

export default function (data) {
  const near = http.get(`${BASE}/api/v1/beds/nearest?lat=12.97&lon=77.59&k=3&maxKm=30`,
    Object.assign(auth(data.doctor), { tags: { name: 'nearest' } }));
  check(near, { 'nearest 200': (r) => r.status === 200 }, { kind: 'nearest' });
  const h = `K6-H${Math.floor(Math.random() * 5)}`;
  const r = http.post(`${BASE}/api/v1/beds/holds`, JSON.stringify({ hospitalId: h, patientId: __ENV.PATIENT_ID || '91274503186205', ttlMinutes: 1 }),
    Object.assign(auth(data.doctor), { tags: { name: 'hold' }, responseCallback: http.expectedStatuses(201, 409) }));
  check(r, { 'hold is 201 or 409': (x) => x.status === 201 || x.status === 409 }, { kind: 'hold' });
  if (r.status === 201 && Math.random() < 0.8) {
    http.del(`${BASE}/api/v1/beds/holds/${r.json('holdId')}`, null, Object.assign(auth(data.doctor), { tags: { name: 'release' } }));
  }
}
