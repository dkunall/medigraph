// Shared helpers. Targets the docker-compose stack via the gateway (GATEWAY_DEV_ISSUER=true). Dev tokens only.
import http from 'k6/http';
import { check } from 'k6';

export const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export function token(subject, roles, facility = 'F1') {
  const r = http.post(`${BASE}/dev/token`, JSON.stringify({ subject, roles, facility }),
    { headers: { 'content-type': 'application/json' } });
  check(r, { 'dev token issued': (x) => x.status === 200 });
  return r.json('access_token');
}

export function auth(tok, extra = {}) {
  return { headers: Object.assign({ authorization: `Bearer ${tok}`, 'content-type': 'application/json' }, extra) };
}

export function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}
