# 1. Hexagonal layout in every service

Status: accepted

## Context

There are eight services, written over a few weeks, and several of them have more than one adapter for the same
thing (Kafka or in-memory, Redis or JDBC, PostGIS or plain SQL). Without a fixed layout, framework code tends to leak
into the business rules, and swapping an adapter turns into a refactor.

## Decision

Every service uses `domain`, `application` (with `port.out`), `adapter.in.web`, `adapter.out.*` and `config`. Only
`config` knows the concrete adapters. `medigraph-arch-rules` turns this into ArchUnit tests, and every service runs
them. The rules are: no Spring, servlet, JPA or Jackson types in domain or application; no references between inbound
and outbound adapters; outbound ports must be interfaces; no field injection.

## Consequences

Adding a second adapter is just a new class plus a conditional bean. The cost is more packages and some mapping
between web records and domain types. The rules break the build, so nobody has to remember them.
