# 4. Erasure by crypto-shredding

Status: accepted

## Context

Patients can ask for their data to be erased. Copies also exist in backups and replicas, which can't realistically be
rewritten.

## Decision

Each patient gets a random 256-bit data key (DEK). Records are encrypted with AES-256-GCM under it, with the record id
as AAD so a ciphertext can't be moved to another row. The DEK is stored only in wrapped form, under a
key-encryption key (KEK), with the patient id as AAD. Erasure deletes the wrapped DEK, so every copy of that patient's
ciphertext, backups included, becomes unreadable. KEK rotation rewraps DEKs and never re-encrypts records.

KEKs come from env vars or a secrets directory. A missing KEK is a startup error. The service never generates a
replacement KEK, because doing that silently would make existing data unreadable.

## Consequences

- Every read needs one extra unwrap per patient.
- If the KEK is lost, all data is lost. That's the same property that makes erasure work, so KEK backups matter.
- A Vault/KMS provider would be the production choice. It exists only as a documented stub behind
  `KeyEncryptionKeyProvider`.
