# 5. Probabilistic patient matching with a review queue

Status: accepted

## Context

Hospitals register the same person with typos, transliterations, a changed surname or a new phone number. Exact
matching creates duplicates. Matching too loosely merges different people, which is worse.

## Decision

- Blocking: candidates must share a key with the new record (Soundex of the surname plus birth year, or the phone
  number). The lookup uses an inverted index.
- Scoring: Fellegi-Sunter log-likelihood weights per field, using Jaro-Winkler for names. The m/u probabilities are
  hand-set defaults.
- Two thresholds. Above the upper one, records are linked automatically. Between the two, a steward reviews the pair.
  If a record scores above the lower threshold against more than one cluster, that also goes to review, so a record
  never silently bridges two clusters.
- Linked records are kept in a Union-Find, so merges stay transitive. An erased record stays in the structure so old
  links can still be explained, but it is never a candidate again.

## Consequences

- Match decisions can be explained field by field.
- Pairs that share no blocking key are never compared.
- The m/u values aren't learned. Running EM on labelled pairs would be the next step.
- The index lives in memory and is rebuilt from PostgreSQL at startup, which is fine at this scale.
