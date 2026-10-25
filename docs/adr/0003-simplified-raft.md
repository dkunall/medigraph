# 3. A small Raft implementation instead of a blockchain or an external store

Status: accepted

## Context

The ledger needs one agreed order of blocks and has to survive a node going down. The participants are a known set of
hospitals, so Byzantine fault tolerance and proof-of-work are overkill. I could have used etcd or Kafka for ordering,
but writing the consensus part myself was one of the reasons for doing the project.

## Decision

`medigraph-ledger` has a Raft node (election, log matching, majority commit with the current-term rule, and a no-op
on election). It is a deterministic state machine driven by `tick()` and `step(message)`. The tests run it over an
in-process network with partitions and message loss. `ledger-service` stores term, vote and log in PostgreSQL and
sends RPCs over HTTP under `/internal/raft`.

Left out on purpose: snapshots and log compaction, membership changes, pre-vote and leader leases.

## Consequences

- Three nodes tolerate one failure. A restarted node reloads its state and catches up from the leader.
- Raft is crash-tolerant only. If a majority colludes, history can be rewritten. Tampering by a single node, or
  directly in a database, is still detected by the hash chain and Merkle proofs.
- The log grows forever until compaction exists.
- The gateway currently points at `ledger1`, and followers redirect writes, so losing `ledger1` affects external
  reads until the gateway learns about leaders.
