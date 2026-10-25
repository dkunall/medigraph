# Architecture notes

## Request path

A request comes in through `api-gateway`. The gateway checks the JWT, applies the per-client token bucket, finds the
route in a path trie, and forwards the request through a circuit breaker. It only forwards an allow-list of headers,
so cookies and `X-Forwarded-*` from the client are dropped, and it sets `X-Forwarded-For` from the socket. The
downstream service checks the JWT again. Nothing behind the gateway assumes the gateway already did that.

## Writing a record

1. `records` resolves the Health ID with `identity-mpi`. A merged (provisional) ID resolves to the surviving one.
2. It encrypts the body under the patient's data key (AES-256-GCM, with the record id as AAD) and stores the
   ciphertext.
3. In the same transaction, it writes an outbox row for the ledger anchor (`sha256(body)` plus metadata) and one for
   the domain event.
4. The outbox relay picks up rows with `FOR UPDATE SKIP LOCKED` and posts the anchor to the ledger leader. A retry
   is safe because the ledger skips entry ids it has already applied.
5. The ledger leader batches entries into a Merkle block. Once a majority has the block, every replica applies it,
   and anyone can then ask for an inclusion proof.

## Reading a record

`records` asks `consent` for a decision covering the requester, purpose and categories. Consent grants are
Ed25519-signed and re-verified on use, so a scope widened directly in the database fails verification. Each category
is either decrypted and returned or listed as `withheld`. A break-glass read is allowed for clinicians, but it is
logged on the ledger and the patient gets a notification.

## Erasure

`records` deletes the patient's wrapped data key and asks `identity-mpi` to scrub the demographics. The ciphertext
stays put but can no longer be decrypted. The ledger keeps only hashes, so it still verifies.

## Ledger replicas

There are three `ledger-service` processes, each with its own database. Raft term, vote and log are written to
PostgreSQL before any reply goes out. Applied blocks are stored too, and on restart the chain is rebuilt and fully
re-verified before the node rejoins. If persisting an applied block fails, the replica stops serving until it is
restarted. That is better than serving a chain that disagrees with its database.

## Ports and adapters

| Port | Default adapter | Other adapter |
|---|---|---|
| ICU vitals processor | in-memory engine | Kafka Streams (`ICU_PROCESSOR=kafka`) |
| Bed hold store | JDBC | Redis (`BEDS_HOLD_STORE=redis`) |
| Hospital locator | JDBC haversine with bounding-box prefilter | PostGIS KNN (`BEDS_LOCATOR=postgis`) |
| KEK provider | env vars | secrets directory, Vault transit (stub) |
| Outbox event sink | log | Kafka (`medigraph.outbox.events-sink=kafka`) |

The in-memory and Kafka ICU processors share `News2WindowEngine`, so both have the same windowing, lateness and
alert-cooldown behaviour.
