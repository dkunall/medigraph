# A quick benchmark (single machine, dev setup)

These are numbers from one run on a shared dev box, not a capacity estimate. The load generator, all ten JVMs (gateway,
seven services, three ledger nodes), PostgreSQL, Kafka and Redis were on the same 8 vCPU / 16 GB VM (Intel Xeon, about 2.6 GB of RAM free and a
load average of 5–8 during the run), so the client and the servers were competing for the same cores. Expect
different numbers anywhere else.

Run on 2026-10-25 with [autocannon](https://github.com/mcollina/autocannon) 8 via
[`load/autocannon-beds.js`](../load/autocannon-beds.js). Each case had a 10 s warm-up and then a 30 s measured run,
against the stack started by `scripts/local/start.sh` (services as plain JVMs, default heap settings from that script).
The dataset is tiny: three hospitals in Kanpur.

| Case | Load | Requests (30 s) | Throughput | Latency |
|---|---|---:|---:|---|
| Gateway → beds `GET /api/v1/beds/nearest` | open loop, fixed 40 req/s, 4 connections | 1,200, all 200 | 40 req/s (as set) | client p50 2 ms, p90 4 ms, p99 10 ms, max 15 ms |
| beds-service direct, `GET /api/v1/beds/nearest` | closed loop, 16 connections | 194,044, all 200 | ~6,470 req/s | mean 1.41 ms in the service; ~2.5 ms round trip (16 / throughput) |
| beds-service direct, `GET /api/v1/hospitals/{id}/beds` | closed loop, 16 connections | 221,863, all 200 | ~7,400 req/s | mean 1.18 ms in the service; ~2.2 ms round trip |

How to read it:

- The gateway was only measured below its rate limit. It allows 50 req/s per client (token bucket, burst 100), so a
  closed-loop run with one token just measures the limiter returning 429s. A real throughput number for the gateway
  needs many client identities or a raised limit; I haven't done that.
- For the direct runs, the request counts match the service's own Micrometer counter
  (`http_server_requests_seconds_count`) to within the handful of requests made before and after the window, and the
  "in the service" mean comes from that counter's `_sum` divided by its `_count` over the window.
- autocannon's own percentiles for the two closed-loop runs (p50 around 145 ms) are not in the table. They can't be
  right: with 16 connections, 145 ms per request would cap throughput at about 110 req/s, not 6,000+. My guess is
  the single-threaded load generator was starved for CPU and its timers were measuring its own queueing. I'd use a
  load generator on a separate machine before trusting any tail-latency numbers.
- Nothing here covers writes (holds, records, ledger), which go through PostgreSQL transactions and, for the
  ledger, Raft. The k6 scripts in `load/` are written for those but haven't been run yet.
