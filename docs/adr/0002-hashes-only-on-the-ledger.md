# 2. Only hashes go on the ledger

Status: accepted

## Context

I want third parties to be able to check that a record hasn't changed since it was written. An append-only log does
that well. Health data, though, has to be erasable on request, and an append-only log can't forget anything.

## Decision

The ledger stores `sha256` of each record plus non-identifying metadata (entry id, type, timestamp). Record bodies
stay in the records database, encrypted. Entries are batched into Merkle blocks, and each block header carries the
previous block's hash.

## Consequences

- Verifying a record means hashing what the database returns and checking a Merkle proof against a block header.
  It doesn't need the rest of the block.
- Erasing a record means making the stored data unreadable (see ADR 4). The ledger is never touched.
- A hash of low-entropy data could be brute-forced, so anchors hash the full record body, never a single short
  field.
