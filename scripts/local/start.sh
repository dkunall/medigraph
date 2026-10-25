#!/usr/bin/env bash
# Starts MediGraph locally: infra (PostGIS, Kafka, Redis) in Docker with published ports,
# every service as a plain JVM process on the host. Container-to-container networking is broken
# in the sandbox this was first run on (bridge traffic timed out), so this runs the app tier outside compose.
# All credentials below are the published local-dev defaults from docker-compose.yml.
set -euo pipefail
REPO=${REPO:-$(cd "$(dirname "$0")/../.." && pwd)}
RUN=${MEDIGRAPH_RUN:-$REPO/.run}   # logs, pids, generated dev keys
LOGS=$RUN/logs
mkdir -p "$LOGS" "$RUN/data"

infra() {
  docker rm -f mg-postgres mg-kafka mg-redis >/dev/null 2>&1 || true
  docker run -d --name mg-postgres -p 55432:5432 \
    -e POSTGRES_USER=medigraph -e POSTGRES_PASSWORD=medigraph-dev -e POSTGRES_DB=medigraph \
    -v "$REPO/deploy/postgres-init.sql:/docker-entrypoint-initdb.d/10-databases.sql:ro" \
    postgis/postgis:16-3.4 >/dev/null
  docker run -d --name mg-redis -p 6379:6379 redis:7.2-alpine >/dev/null
  docker run -d --name mg-kafka -p 9092:9092 \
    -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
    -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
    -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
    -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT \
    -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
    -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
    -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
    -e KAFKA_AUTO_CREATE_TOPICS_ENABLE=true -e "KAFKA_HEAP_OPTS=-Xmx512m -Xms512m" \
    apache/kafka:3.8.0 >/dev/null
  for i in $(seq 1 60); do
    docker exec mg-postgres pg_isready -U medigraph -d medigraph >/dev/null 2>&1 && break; sleep 1
  done
  sleep 3   # init script runs after the first ready signal
  for i in $(seq 1 30); do
    docker exec mg-postgres psql -U medigraph -d beds -c 'select 1' >/dev/null 2>&1 && break; sleep 1
  done
}

PG=jdbc:postgresql://localhost:55432
COMMON=(SPRING_PROFILES_ACTIVE=json
        JWK_SET_URI=http://localhost:8080/.well-known/jwks.json
        TOKEN_URI=http://localhost:8080/oauth2/token
        LEDGER_NODES=http://localhost:8085,http://localhost:8086,http://localhost:8087
        IDENTITY_URL=http://localhost:8081 CONSENT_URL=http://localhost:8083 RECORDS_URL=http://localhost:8082)
JVM=(-Xmx256m -XX:+UseSerialGC -Xss512k)

svc() {  # name jar-module env...
  local name=$1 module=$2; shift 2
  local jar; jar=$(ls "$REPO"/services/"$module"/target/"$module"-*.jar | head -1)
  ( cd "$RUN/data"; env "${COMMON[@]}" "$@" nohup java "${JVM[@]}" -jar "$jar" > "$LOGS/$name.log" 2>&1 &
    echo $! > "$RUN/$name.pid" )
}

wait_up() {
  local port=$1
  for i in $(seq 1 120); do
    curl -sf -m 2 "localhost:$port/actuator/health/readiness" >/dev/null && return 0; sleep 1
  done
  echo "port $port did not become ready" >&2; return 1
}

ledger() {  # n port
  local n=$1 port=$2 peers=()
  for m in 1 2 3; do [ "$m" = "$n" ] || peers+=("MEDIGRAPH_LEDGER_RAFT_PEERS_LEDGER$m=http://localhost:$((8084+m))"); done
  svc "ledger$n" ledger-service PORT=$port RAFT_NODE_ID=ledger$n \
    LEDGER_DB_URL="$PG/ledger$n?currentSchema=ledger" LEDGER_DB_PASSWORD=medigraph-dev "${peers[@]}"
}

start_one() {
  case $1 in
    gateway) svc gateway api-gateway GATEWAY_DEV_ISSUER=true ;;
    identity) svc identity identity-mpi-service IDENTITY_DB_URL="$PG/identity?currentSchema=identity" IDENTITY_DB_PASSWORD=medigraph-dev ;;
    ledger1|ledger2|ledger3) ledger "${1#ledger}" "$((8084+${1#ledger}))" ;;
    consent) svc consent consent-service CONSENT_DB_URL="$PG/consent?currentSchema=consent" CONSENT_DB_PASSWORD=medigraph-dev \
      CONSENT_SIGNING_KEY_PATH="$RUN/data/consent-signing-key.pem" CONSENT_GENERATE_KEY=true ;;
    records) svc records records-service RECORDS_DB_URL="$PG/records?currentSchema=records" RECORDS_DB_PASSWORD=medigraph-dev \
      KEK_SOURCE=env MEDIGRAPH_KEK_DEV1=ZGV2LW9ubHkta2VrLW5vdC1hLXNlY3JldC0wMDAwMDA= MEDIGRAPH_KEK_CURRENT=dev1 ;;
    prescription) svc prescription prescription-service PRESCRIPTION_DB_URL="$PG/prescription?currentSchema=prescription" PRESCRIPTION_DB_PASSWORD=medigraph-dev ;;
    icu) svc icu icu-service ICU_DB_URL="$PG/icu?currentSchema=icu" ICU_DB_PASSWORD=medigraph-dev \
      ICU_PROCESSOR=kafka KAFKA_BOOTSTRAP=localhost:9092 ;;
    beds) svc beds beds-service BEDS_DB_URL="$PG/beds?currentSchema=beds" BEDS_DB_PASSWORD=medigraph-dev \
      BEDS_LOCATOR=postgis BEDS_HOLD_STORE=redis BEDS_REDIS_HEALTH=true REDIS_HOST=localhost \
      BEDS_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/platform,classpath:db/postgis ;;
    *) echo "unknown service $1" >&2; return 1 ;;
  esac
}

declare -A PORT_OF=([gateway]=8080 [identity]=8081 [records]=8082 [consent]=8083 [prescription]=8084
                    [ledger1]=8085 [ledger2]=8086 [ledger3]=8087 [icu]=8088 [beds]=8089)

stop_one() {
  local f="$RUN/$1.pid"
  [ -f "$f" ] && kill "$(cat "$f")" 2>/dev/null || true
  for i in $(seq 1 30); do curl -s -m 1 "localhost:${PORT_OF[$1]}" >/dev/null 2>&1 || return 0; sleep 1; done
}

apps() {
  start_one gateway; wait_up 8080
  for s in identity ledger1 ledger2 ledger3 consent records prescription icu beds; do start_one $s; done
  for p in 8081 8082 8083 8084 8085 8086 8087 8088 8089; do wait_up $p; done
  echo "all services ready"
}

case "${1:-all}" in
  infra) infra ;;
  apps) apps ;;
  start) start_one "$2"; wait_up "${PORT_OF[$2]}" ;;
  stop) stop_one "$2" ;;
  restart) stop_one "$2"; start_one "$2"; wait_up "${PORT_OF[$2]}" ;;
  stopall) for s in beds icu prescription records consent ledger3 ledger2 ledger1 identity gateway; do stop_one $s; done ;;
  down) "$0" stopall; docker rm -f mg-postgres mg-kafka mg-redis >/dev/null 2>&1 || true ;;
  all) infra; apps ;;
esac
