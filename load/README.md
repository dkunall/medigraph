# Load scripts (k6)

I haven't run these yet, so there are no load numbers anywhere in the repo. The latency thresholds in the scripts
are targets, not results. They expect the compose stack to be up.

| Script | What it stresses | Pass condition |
|---|---|---|
| `beds-contention.js` | 200 req/s racing for 15 beds across 5 hospitals | every hold is 201 or 409, no 5xx |
| `icu-vitals-ingest.js` | 100 batches/s (5 readings each) into Kafka Streams | <1 % errors |
| `gateway-rate-limit.js` | one subject at 300 req/s against a 100-token bucket | 429 + Retry-After, no 5xx |

```bash
docker compose up --build -d
k6 run load/beds-contention.js
k6 run -e HEALTH_IDS=<id1,id2> load/icu-vitals-ingest.js
k6 run load/gateway-rate-limit.js
```
