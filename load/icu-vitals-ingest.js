// Sustained vitals ingest through gateway -> icu-service -> Kafka Streams. Run: k6 run load/icu-vitals-ingest.js
import http from 'k6/http';
import { check } from 'k6';
import { BASE, token, auth, uuid } from './common.js';

export const options = {
  scenarios: { ingest: { executor: 'constant-arrival-rate', rate: 100, timeUnit: '1s', duration: '2m',
                         preAllocatedVUs: 30, maxVUs: 100 } },
  thresholds: { http_req_failed: ['rate<0.01'], 'http_req_duration{name:vitals}': ['p(99)<500'] },
};

const PATIENTS = (__ENV.HEALTH_IDS || '').split(',').filter((x) => x);   // register patients first, pass their ids

export function setup() {
  if (PATIENTS.length === 0) throw new Error('set HEALTH_IDS=<id1,id2,...> (registered via identity-mpi)');
  return { nurse: token('k6-nurse', ['NURSE']) };
}

export default function (data) {
  const p = PATIENTS[Math.floor(Math.random() * PATIENTS.length)];
  const t = Date.now() - 1000;
  const readings = [['PULSE', 70 + Math.random() * 60], ['RESPIRATION_RATE', 12 + Math.random() * 14],
                    ['SPO2', 90 + Math.random() * 9], ['SYSTOLIC_BP', 95 + Math.random() * 50],
                    ['TEMPERATURE', 36 + Math.random() * 2.5]]
    .map(([type, value]) => ({ readingId: uuid(), type, value, eventTimeMillis: t }));
  const r = http.post(`${BASE}/api/v1/icu/patients/${p}/vitals`, JSON.stringify({ readings }),
    Object.assign(auth(data.nurse), { tags: { name: 'vitals' } }));
  check(r, { 'vitals accepted': (x) => x.status >= 200 && x.status < 300 });
}
