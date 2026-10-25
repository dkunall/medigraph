// Single-machine dev benchmark. Reads a dev JWT from $TOKEN_FILE (default ./bench.tok) (minted from the gateway's local issuer).
const autocannon = require(require('child_process').execSync('npm root -g').toString().trim() + '/autocannon');
const fs = require('fs');
const { execSync } = require('child_process');
// server-side truth: beds-service Micrometer counters for the endpoint under test
function serverStats(uri) {
  const txt = execSync('curl -s localhost:8089/actuator/prometheus').toString();
  const pick = (name) => txt.split('\n').filter(l => l.startsWith(name + '{') && l.includes('uri="' + uri + '"') && l.includes('status="200"'))
    .reduce((a, l) => a + parseFloat(l.split(' ').pop()), 0);
  return { count: pick('http_server_requests_seconds_count'), sum: pick('http_server_requests_seconds_sum') };
}
const token = fs.readFileSync(process.env.TOKEN_FILE || 'bench.tok', 'utf8').trim();
const headers = { Authorization: 'Bearer ' + token, Accept: 'application/json' };
const path = '/api/v1/beds/nearest?lat=26.4499&lon=80.3319&k=3';
const runs = [
  { title: 'gateway -> beds nearest, 1 client at a fixed 40 req/s (under the 50/s per-client limit)',
    uri: '/api/v1/beds/nearest', url: 'http://localhost:8080' + path, connections: 4, overallRate: 40, duration: 30 },
  { title: 'beds-service direct (no gateway), nearest, 16 connections, closed loop',
    uri: '/api/v1/beds/nearest', url: 'http://localhost:8089' + path, connections: 16, duration: 30 },
  { title: 'beds-service direct (no gateway), GET /hospitals/{id}/beds (Redis + Postgres), 16 connections',
    uri: '/api/v1/hospitals/{hospitalId}/beds', url: 'http://localhost:8089/api/v1/hospitals/KNP-REGENCY/beds', connections: 16, duration: 30 },
];
(async () => {
  for (const r of runs) {
    // warm-up so JIT and connection pools are not in the numbers
    await autocannon({ url: r.url, headers, connections: r.connections, duration: 10, overallRate: r.overallRate });
    const before = serverStats(r.uri);
    const res = await autocannon({ url: r.url, headers, connections: r.connections, duration: r.duration, overallRate: r.overallRate });
    const after = serverStats(r.uri);
    const n = after.count - before.count;
    const s = res.statusCodeStats || {};
    console.log(JSON.stringify({ title: r.title, durationS: r.duration, connections: r.connections,
      requests: res.requests.total, reqPerSecAvg: res.requests.average,
      serverSide: { requestsSeen: n, meanMsInsideBedsService: +(1000 * (after.sum - before.sum) / n).toFixed(2) },
      littleLawRoundTripMs: +(1000 * r.connections / res.requests.average).toFixed(2),
      clientLatencyMs: { p50: res.latency.p50, p90: res.latency.p90, p99: res.latency.p99, max: res.latency.max },
      statusCodes: Object.fromEntries(Object.entries(s).map(([k, v]) => [k, v.count])), errors: res.errors, timeouts: res.timeouts }));
  }
})();
