# One patient through MediGraph: Ramesh Sharma

Ramesh Sharma, 58, from Lucknow, on warfarin for atrial fibrillation. Every request and response below was captured from a live run of the stack on 2026-10-25 by [`scripts/local/medigraph_demo.py`](../scripts/local/medigraph_demo.py) (run with `scripts/local/demo.sh --fresh`); nothing was typed in by hand. Calls go through the API gateway on `localhost:8080`. Tokens come from the gateway's local dev issuer and are cut short. Long arrays are trimmed, and each trim is marked.

Health ID issued in this run: `64119137706027`.

## 1. Registration at Hospital A (front desk), duplicate check, Health ID issued

```http
POST /api/v1/patients HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQiOi…
Idempotency-Key: e213b6ea-fc62-47b9-9bdb-62e79685acda
X-Correlation-Id: demo-977a12d056d0
Content-Type: application/json
```

```json
{
  "localMrn": "A-24BB92-1",
  "givenName": "Ramesh",
  "familyName": "Sharma",
  "birthDate": "1968-03-14",
  "gender": "male",
  "phone": "+91 98390 12345",
  "city": "Lucknow"
}
```

**201 Created** (260 ms)

```http
Content-Type: application/json
X-Correlation-Id: demo-977a12d056d0
```

```json
{
  "healthId": "64119137706027",
  "formattedHealthId": "6411-9137-7060-27",
  "recordId": "HOSP-A:A-24BB92-1",
  "decision": "NEW",
  "reviewTaskIds": []
}
```

> Health ID 64119137706027 (6411-9137-7060-27); Verhoeff check digit valid: true

```http
POST /api/v1/patients HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQiOi…
Idempotency-Key: 6a5ad895-4b47-4fa7-a835-d896f2893e00
X-Correlation-Id: demo-c2527db2bc26
Content-Type: application/json
```

```json
{
  "localMrn": "A-24BB92-2",
  "givenName": "Ramesh",
  "familyName": "Sharmaa",
  "birthDate": "1968-03-14",
  "gender": "male",
  "phone": "+91 98390 12345",
  "city": "Lucknow"
}
```

**201 Created** (31 ms)

```http
Content-Type: application/json
X-Correlation-Id: demo-c2527db2bc26
```

```json
{
  "healthId": "64119137706027",
  "formattedHealthId": "6411-9137-7060-27",
  "recordId": "HOSP-A:A-24BB92-2",
  "decision": "AUTO_LINK",
  "matchWeight": 35.13246868610685,
  "matchedRecordId": "HOSP-A:A-24BB92-1",
  "reviewTaskIds": []
}
```

> second desk entry with a typo ('Sharmaa'): decision AUTO_LINK, same Health ID: true

## 2. Ramesh gives Hospital A consent to see his records for treatment

```http
POST /api/v1/patients/64119137706027/consents HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQiOi…
Idempotency-Key: b3b176fc-3819-4ee3-bb14-cd70af0edb92
X-Correlation-Id: demo-3d760ca8e26d
Content-Type: application/json
```

```json
{
  "grantee": "HOSP-A",
  "categories": [
    "ENCOUNTERS",
    "CONDITIONS",
    "MEDICATIONS",
    "… 2 more items not shown"
  ],
  "purpose": "TREATMENT",
  "durationDays": 180
}
```

**201 Created** (411 ms)

```http
Content-Type: application/json
X-Correlation-Id: demo-3d760ca8e26d
```

```json
{
  "consent": {
    "consentId": "723c9a8c-0308-41c5-805d-00e5e84cea0d",
    "patientId": "64119137706027",
    "grantee": "HOSP-A",
    "categories": [
      "CONDITIONS",
      "OBSERVATIONS",
      "ENCOUNTERS",
      "… 2 more items not shown"
    ],
    "purpose": "TREATMENT",
    "issuedAt": "2026-10-25T10:19:19.244257656Z",
    "expiresAt": "2027-04-23T10:19:19.244257656Z",
    "signerKeyId": "consent-ed25519-4acc27cf50d653b1",
    "signature": "Oha4N5T5F9kn6U8VQqMSnufxT0/LxRGzLnOHpQ2wYwwtL4JcCJzZ2q3FS5OdgLDxpa/kEgDFtag4Uvmrg4ihAw=="
  },
  "signatureValid": true
}
```

> the consent artefact is signed; signatureValid = true

## 3. Dr. Mehta records the warfarin prescription and the diagnosis; the record is encrypted and anchored on the ledger

```http
POST /api/v1/patients/64119137706027/records HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQiOi…
Idempotency-Key: c8790f4d-b3be-4e5b-92fa-2095be353b8e
X-Correlation-Id: demo-b1637b2074df
Content-Type: application/json
```

```json
{
  "resourceType": "MedicationRequest",
  "subject": {
    "reference": "Patient/64119137706027"
  },
  "medicationCodeableConcept": {
    "coding": [
      {
        "system": "http://www.nlm.nih.gov/research/umls/rxnorm",
        "code": "11289",
        "display": "warfarin"
      }
    ],
    "text": "warfarin"
  },
  "status": "active",
  "intent": "order",
  "dosageText": "5 mg once daily",
  "authoredOn": "2026-10-25",
  "requester": "dr-mehta"
}
```

**201 Created** (334 ms)

```http
Content-Type: application/json
X-Correlation-Id: demo-b1637b2074df
```

```json
{
  "recordId": "f50d01ee-ca84-4e2e-9b78-0068064fac2a",
  "healthId": "64119137706027",
  "resourceType": "MedicationRequest",
  "category": "MEDICATIONS",
  "contentHash": "cbd4b9dade195974bd18a49212ea1f7e8e63498deba5a0b4d60087205d0b170d",
  "ledgerEntryId": "rec:f50d01ee-ca84-4e2e-9b78-0068064fac2a"
}
```

```http
POST /api/v1/patients/64119137706027/records HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQiOi…
Idempotency-Key: dd3ca5f6-d99d-4253-b03a-bf565d9fa18d
X-Correlation-Id: demo-dea7b7cb2539
Content-Type: application/json
```

```json
{
  "resourceType": "Condition",
  "subject": {
    "reference": "Patient/64119137706027"
  },
  "code": {
    "coding": [
      {
        "system": "http://snomed.info/sct",
        "code": "49436004",
        "display": "Atrial fibrillation"
      }
    ],
    "text": "Atrial fibrillation"
  },
  "clinicalStatus": "active",
  "onsetDateTime": "2024-11-02"
}
```

**201 Created** (41 ms)

```http
Content-Type: application/json
X-Correlation-Id: demo-dea7b7cb2539
```

```json
{
  "recordId": "f92ccd2d-307e-470a-a542-da25096d8126",
  "healthId": "64119137706027",
  "resourceType": "Condition",
  "category": "CONDITIONS",
  "contentHash": "71aeb710f1c39efdbd5a7dd691c6d046d0e234a74a883810c374bdc9a52f6abd",
  "ledgerEntryId": "rec:f92ccd2d-307e-470a-a542-da25096d8126"
}
```

> rec:f50d01ee-ca84-4e2e-9b78-0068064fac2a was sealed into a ledger block 0.6s after the write (blocks are cut in batches)

```http
GET /api/v1/records/f50d01ee-ca84-4e2e-9b78-0068064fac2a/verification HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQiOi…
X-Correlation-Id: demo-66d781567200
```

**200 OK** (100 ms)

```http
Content-Type: application/json
X-Correlation-Id: demo-66d781567200
```

```json
{
  "recordId": "f50d01ee-ca84-4e2e-9b78-0068064fac2a",
  "valid": true,
  "reason": "ok",
  "storedHash": "cbd4b9dade195974bd18a49212ea1f7e8e63498deba5a0b4d60087205d0b170d",
  "recomputedHash": "cbd4b9dade195974bd18a49212ea1f7e8e63498deba5a0b4d60087205d0b170d",
  "proof": {
    "entryId": "rec:f50d01ee-ca84-4e2e-9b78-0068064fac2a",
    "blockHeight": 1,
    "blockHash": "3444676c99dfbb391c37443868bc7b6d484f42c4a47431c302a828e1b0aa4f91",
    "merkleRoot": "ff32192e5ca7aaa094fcc8726378be166f0988987b63c2b930113429b67087df",
    "merkleProof": {
      "leafIndex": 0,
      "leafCount": 2,
      "leafHashHex": "886fefb8d10ecbeab17c76b2d5a3b62c4437d3cd9f74685d2dc7cc1ca1e33a9e",
      "steps": [
        {
          "siblingHex": "5efd492d5e574f4b34e4d089d59ee9cbe2c5d9e327f0d76c6c0c7952102df41b",
          "siblingOnLeft": false
        }
      ]
    }
  }
}
```

> the stored hash matches the ledger: valid = true

```http
GET /api/v1/ledger/entries/rec:f50d01ee-ca84-4e2e-9b78-0068064fac2a/proof HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQiOi…
X-Correlation-Id: demo-7a42ed46c984
```

**200 OK** (10 ms)

```http
Content-Type: application/json
X-Correlation-Id: demo-7a42ed46c984
```

```json
{
  "entryId": "rec:f50d01ee-ca84-4e2e-9b78-0068064fac2a",
  "blockHeight": 1,
  "blockHash": "3444676c99dfbb391c37443868bc7b6d484f42c4a47431c302a828e1b0aa4f91",
  "merkleRoot": "ff32192e5ca7aaa094fcc8726378be166f0988987b63c2b930113429b67087df",
  "merkleProof": {
    "leafIndex": 0,
    "leafCount": 2,
    "leafHashHex": "886fefb8d10ecbeab17c76b2d5a3b62c4437d3cd9f74685d2dc7cc1ca1e33a9e",
    "steps": [
      {
        "siblingHex": "5efd492d5e574f4b34e4d089d59ee9cbe2c5d9e327f0d76c6c0c7952102df41b",
        "siblingOnLeft": false
      }
    ]
  }
}
```

> row in postgres (type|category|key_version|ciphertext bytes|first 24 bytes hex|position of 'warfarin'): MedicationRequest|MEDICATIONS|1|368|a32e4328dd2ca7a9201bd7593e3b17f0a2d3ef09ea149205|0

## 4. Ramesh collapses in Kanpur and is brought to Hospital B's emergency room; the doctor uses break-glass

```http
GET /api/v1/patients/64119137706027/records?purpose=EMERGENCY HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQiOi…
X-Correlation-Id: demo-d8e0434c615e
```

**403 Forbidden** (104 ms)

```http
Content-Type: application/problem+json
X-Correlation-Id: demo-d8e0434c615e
```

```json
{
  "type": "https://medigraph.dev/problems/access-denied",
  "title": "Forbidden",
  "status": 403,
  "detail": "no consent or break-glass grant covers [ENCOUNTERS, CONDITIONS, MEDICATIONS, OBSERVATIONS, ALLERGIES] for EMERGENCY",
  "instance": "/api/v1/patients/64119137706027/records",
  "correlationId": "demo-d8e0434c615e"
}
```

```http
POST /api/v1/patients/64119137706027/break-glass HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQiOi…
Idempotency-Key: bccd902e-502f-4dae-92c8-8af80debe771
X-Correlation-Id: demo-0f60cb1a3e07
Content-Type: application/json
```

```json
{
  "reason": "Unconscious on arrival, suspected intracranial bleed; need current anticoagulants"
}
```

**201 Created** (30 ms)

```http
Content-Type: application/json
X-Correlation-Id: demo-0f60cb1a3e07
```

```json
{
  "grantId": "e918ee12-73e8-4c25-8db7-fdc64f47909a",
  "scope": [
    "ALLERGIES",
    "MEDICATIONS",
    "CONDITIONS"
  ],
  "expiresAt": "2026-10-25T14:19:27.584642244Z",
  "reasonHash": "ea7f4fc07f5a1b65c444bc77bdf6c9489cd71dbdea53c48e883cf7b38fb1c111"
}
```

```http
GET /api/v1/patients/64119137706027/records?purpose=EMERGENCY HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQiOi…
X-Correlation-Id: demo-b8b1e29c2b89
```

**200 OK** (91 ms)

```http
Content-Type: application/json
X-Correlation-Id: demo-b8b1e29c2b89
```

```json
{
  "healthId": "64119137706027",
  "records": [
    {
      "recordId": "f92ccd2d-307e-470a-a542-da25096d8126",
      "category": "CONDITIONS",
      "resourceType": "Condition",
      "contentHash": "71aeb710f1c39efdbd5a7dd691c6d046d0e234a74a883810c374bdc9a52f6abd",
      "facility": "HOSP-A",
      "createdAt": "2026-10-25T10:19:19.651523Z",
      "resource": {
        "resourceType": "Condition",
        "subject": {
          "reference": "Patient/64119137706027"
        },
        "code": {
          "coding": [
            {
              "system": "http://snomed.info/sct",
              "code": "49436004",
              "display": "Atrial fibrillation"
            }
          ],
          "text": "Atrial fibrillation"
        },
        "clinicalStatus": "active",
        "onsetDateTime": "2024-11-02"
      }
    },
    {
      "recordId": "f50d01ee-ca84-4e2e-9b78-0068064fac2a",
      "category": "MEDICATIONS",
      "resourceType": "MedicationRequest",
      "contentHash": "cbd4b9dade195974bd18a49212ea1f7e8e63498deba5a0b4d60087205d0b170d",
      "facility": "HOSP-A",
      "createdAt": "2026-10-25T10:19:19.594923Z",
      "resource": {
        "resourceType": "MedicationRequest",
        "subject": {
          "reference": "Patient/64119137706027"
        },
        "medicationCodeableConcept": {
          "coding": [
            {
              "system": "http://www.nlm.nih.gov/research/umls/rxnorm",
              "code": "11289",
              "display": "warfarin"
            }
          ],
          "text": "warfarin"
        },
        "status": "active",
        "intent": "order",
        "dosageText": "5 mg once daily",
        "authoredOn": "2026-10-25",
        "requester": "dr-mehta"
      }
    }
  ],
  "decisions": {
    "ENCOUNTERS": "DENIED",
    "CONDITIONS": "BREAK_GLASS",
    "MEDICATIONS": "BREAK_GLASS",
    "OBSERVATIONS": "DENIED",
    "ALLERGIES": "BREAK_GLASS"
  },
  "withheld": [
    "ENCOUNTERS",
    "OBSERVATIONS"
  ],
  "quarantined": []
}
```

```http
GET /api/v1/patients/64119137706027/notifications HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQiOi…
X-Correlation-Id: demo-b55df6572dd1
```

**200 OK** (23 ms)

```http
Content-Type: application/json
X-Correlation-Id: demo-b55df6572dd1
```

```json
[
  {
    "kind": "BREAK_GLASS",
    "message": "Emergency access to your records by dr-khan at HOSP-B until 2026-10-25T14:19:27.584642244Z",
    "createdAt": "2026-10-25T10:19:27.585158Z"
  }
]
```

## 5. Dr. Khan orders aspirin (typed 'asprin'); the system corrects the name and blocks the warfarin interaction

```http
GET /api/v1/drugs/resolution?name=asprin HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQiOi…
X-Correlation-Id: demo-def9fcc3a254
```

**200 OK** (189 ms)

```http
Content-Type: application/json
X-Correlation-Id: demo-def9fcc3a254
```

```json
{
  "kind": "CORRECTED",
  "input": "asprin",
  "drug": "aspirin",
  "suggestions": [
    "aspirin"
  ]
}
```

```http
POST /api/v1/patients/64119137706027/prescriptions HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQiOi…
Idempotency-Key: 5fa68b78-3270-4a87-881f-3cd70d54ab0c
X-Correlation-Id: demo-4298de6b1677
Content-Type: application/json
```

```json
{
  "drug": "asprin",
  "dosage": "300 mg stat",
  "purpose": "EMERGENCY"
}
```

**200 OK** (220 ms)

```http
Content-Type: application/json
X-Correlation-Id: demo-4298de6b1677
```

```json
{
  "prescriptionId": "6953d8c2-0ba7-49fe-9611-8bafa01175cc",
  "healthId": "64119137706027",
  "outcome": "BLOCKED",
  "status": "NOT_ISSUED",
  "resolution": {
    "kind": "CORRECTED",
    "input": "asprin",
    "drug": "aspirin",
    "suggestions": [
      "aspirin"
    ]
  },
  "alerts": [
    {
      "type": "INTERACTION",
      "severity": "MAJOR",
      "involving": "warfarin",
      "message": "aspirin + warfarin: additive bleeding risk"
    }
  ],
  "checkedAgainst": {
    "currentMedications": [
      "warfarin"
    ],
    "allergies": []
  },
  "notice": "Sample dataset for software demonstration only. Not clinical advice."
}
```

## 6. On the ward at Hospital B, bedside monitor readings trigger a NEWS2 early-warning alert

```http
POST /api/v1/icu/patients/64119137706027/vitals HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQiOi…
Idempotency-Key: da49ab30-eb0a-4372-b53c-bfd3c923de5b
X-Correlation-Id: demo-61489c814395
Content-Type: application/json
```

```json
{
  "readings": [
    {
      "readingId": "mon7-24BB92-0",
      "type": "RESPIRATION_RATE",
      "value": 27,
      "eventTimeMillis": 1792922848118
    },
    {
      "readingId": "mon7-24BB92-1",
      "type": "SPO2",
      "value": 90,
      "eventTimeMillis": 1792922849118
    },
    {
      "readingId": "mon7-24BB92-2",
      "type": "SUPPLEMENTAL_O2",
      "value": 1,
      "eventTimeMillis": 1792922850118
    },
    "… 4 more items not shown"
  ]
}
```

**202 Accepted** (441 ms)

```http
Content-Type: application/json
X-Correlation-Id: demo-61489c814395
```

```json
{
  "accepted": 7
}
```

```http
POST /api/v1/icu/patients/64119137706027/vitals HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQiOi…
Idempotency-Key: 53b1c6be-d883-4832-8e34-607b7f799b89
X-Correlation-Id: demo-de34356143fe
Content-Type: application/json
```

```json
{
  "readings": [
    {
      "readingId": "mon7-24BB92-now",
      "type": "PULSE",
      "value": 124,
      "eventTimeMillis": 1792923568118
    }
  ]
}
```

**202 Accepted** (36 ms)

```http
Content-Type: application/json
X-Correlation-Id: demo-de34356143fe
```

```json
{
  "accepted": 1
}
```

> the alert was queryable 1.1s after the closing reading (polled once a second)

```http
GET /api/v1/icu/patients/64119137706027/alerts HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQiOi…
X-Correlation-Id: demo-c7b8441c6cfc
```

**200 OK** (22 ms)

```http
Content-Type: application/json
X-Correlation-Id: demo-c7b8441c6cfc
```

```json
[
  {
    "patientId": "64119137706027",
    "windowStart": "2026-10-25T10:05:00Z",
    "windowEnd": "2026-10-25T10:10:00Z",
    "score": 17,
    "risk": "HIGH",
    "reason": "NEW"
  }
]
```

## 7. He needs an ICU bed: find the nearest hospital with one free and hold it

```http
PUT /api/v1/hospitals/KNP-HALLET HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQiOi…
Idempotency-Key: 750d2d8a-e6a8-4093-83a4-1892fb1a5aef
X-Correlation-Id: demo-2ef1840f925b
Content-Type: application/json
```

```json
{
  "name": "LLR (Hallet) Hospital",
  "lat": 26.4808,
  "lon": 80.3062,
  "icuBeds": 24,
  "occupied": 24
}
```

**200 OK** (264 ms)

```http
Content-Type: application/json
X-Correlation-Id: demo-2ef1840f925b
```

```json
{
  "shard": "beds-shard-3",
  "hospitalId": "KNP-HALLET"
}
```

> also registered KNP-REGENCY (16 ICU beds, 14 occupied) and KNP-RAMA (10 beds, 9 occupied)

```http
GET /api/v1/beds/nearest?lat=26.4499&lon=80.3319&k=3 HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQiOi…
X-Correlation-Id: demo-1be0a24615d3
```

**200 OK** (39 ms)

```http
Content-Type: application/json
X-Correlation-Id: demo-1be0a24615d3
```

```json
[
  {
    "hospitalId": "KNP-REGENCY",
    "name": "Regency Hospital",
    "distanceKm": 6.25,
    "freeIcuBeds": 2,
    "shard": "beds-shard-2"
  },
  {
    "hospitalId": "KNP-RAMA",
    "name": "Rama Medical College",
    "distanceKm": 13.71,
    "freeIcuBeds": 1,
    "shard": "beds-shard-3"
  }
]
```

```http
POST /api/v1/beds/holds HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQiOi…
Idempotency-Key: 23b61fac-c075-4813-9fd0-9b7dcce2ad9b
X-Correlation-Id: demo-f0017bdc9f4f
Content-Type: application/json
```

```json
{
  "hospitalId": "KNP-REGENCY",
  "patientId": "64119137706027",
  "ttlMinutes": 30
}
```

**201 Created** (872 ms)

```http
Content-Type: application/json
X-Correlation-Id: demo-f0017bdc9f4f
```

```json
{
  "holdId": "aba98469-36c1-41ac-b525-8559329e18f3",
  "hospitalId": "KNP-REGENCY",
  "patientId": "64119137706027",
  "createdAt": "2026-10-25T10:19:30.054741245Z",
  "expiresAt": "2026-10-25T10:49:30.054741245Z",
  "status": "ACTIVE"
}
```

```http
POST /api/v1/beds/holds/aba98469-36c1-41ac-b525-8559329e18f3/confirm HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQiOi…
Idempotency-Key: 0e182532-a290-4204-b40f-9e02840cfa5d
X-Correlation-Id: demo-c63becbad210
```

**200 OK** (70 ms)

```http
Content-Type: application/json
X-Correlation-Id: demo-c63becbad210
```

```json
{
  "holdId": "aba98469-36c1-41ac-b525-8559329e18f3",
  "hospitalId": "KNP-REGENCY",
  "patientId": "64119137706027",
  "createdAt": "2026-10-25T10:19:30.054Z",
  "expiresAt": "2026-10-25T10:49:30.054Z",
  "status": "CONFIRMED"
}
```

```http
GET /api/v1/hospitals/KNP-REGENCY/beds HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQiOi…
X-Correlation-Id: demo-2af47ee409b7
```

**200 OK** (16 ms)

```http
Content-Type: application/json
X-Correlation-Id: demo-2af47ee409b7
```

```json
{
  "hospitalId": "KNP-REGENCY",
  "total": 16,
  "occupied": 15,
  "held": 0,
  "free": 1,
  "version": 2,
  "shard": "beds-shard-2"
}
```

## 8. Months later Ramesh asks for his data to be erased (crypto-shred)

```http
DELETE /api/v1/patients/64119137706027/data HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQiOi…
Idempotency-Key: f6c9fa82-0cd7-412d-86ff-d4c340b9908f
X-Correlation-Id: demo-d2d43cdf567c
```

**200 OK** (88 ms)

```http
Content-Type: application/json
X-Correlation-Id: demo-d2d43cdf567c
```

```json
{
  "healthId": "64119137706027",
  "keysDestroyed": 1,
  "registrationsScrubbed": 2,
  "ledgerEntryId": "shred:64119137706027:1792923571109"
}
```

```http
GET /api/v1/patients/64119137706027/records HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQiOi…
X-Correlation-Id: demo-60d95baf1a35
```

**410 Gone** (72 ms)

```http
Content-Type: application/problem+json
X-Correlation-Id: demo-60d95baf1a35
```

```json
{
  "type": "https://medigraph.dev/problems/crypto-shredded",
  "title": "Gone",
  "status": 410,
  "detail": "this patient's data keys were destroyed at their request; the data is permanently unreadable",
  "instance": "/api/v1/patients/64119137706027/records",
  "correlationId": "demo-60d95baf1a35"
}
```

```http
GET /api/v1/records/f50d01ee-ca84-4e2e-9b78-0068064fac2a/verification HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQiOi…
X-Correlation-Id: demo-8fd29e8b73b1
```

**410 Gone** (158 ms)

```http
Content-Type: application/problem+json
X-Correlation-Id: demo-8fd29e8b73b1
```

```json
{
  "type": "https://medigraph.dev/problems/crypto-shredded",
  "title": "Gone",
  "status": 410,
  "detail": "this patient's data keys were destroyed at their request; the data is permanently unreadable",
  "instance": "/api/v1/records/f50d01ee-ca84-4e2e-9b78-0068064fac2a/verification",
  "correlationId": "demo-8fd29e8b73b1"
}
```

```http
GET /api/v1/ledger/entries/rec:f50d01ee-ca84-4e2e-9b78-0068064fac2a/proof HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQiOi…
X-Correlation-Id: demo-3a3233fdb087
```

**200 OK** (30 ms)

```http
Content-Type: application/json
X-Correlation-Id: demo-3a3233fdb087
```

```json
{
  "entryId": "rec:f50d01ee-ca84-4e2e-9b78-0068064fac2a",
  "blockHeight": 1,
  "blockHash": "3444676c99dfbb391c37443868bc7b6d484f42c4a47431c302a828e1b0aa4f91",
  "merkleRoot": "ff32192e5ca7aaa094fcc8726378be166f0988987b63c2b930113429b67087df",
  "merkleProof": {
    "leafIndex": 0,
    "leafCount": 2,
    "leafHashHex": "886fefb8d10ecbeab17c76b2d5a3b62c4437d3cd9f74685d2dc7cc1ca1e33a9e",
    "steps": [
      {
        "siblingHex": "5efd492d5e574f4b34e4d089d59ee9cbe2c5d9e327f0d76c6c0c7952102df41b",
        "siblingOnLeft": false
      }
    ]
  }
}
```

```http
GET /api/v1/ledger/verify HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQiOi…
X-Correlation-Id: demo-8fb2456b1800
```

**200 OK** (15 ms)

```http
Content-Type: application/json
X-Correlation-Id: demo-8fb2456b1800
```

```json
{
  "valid": true,
  "reason": "ok"
}
```

> postgres after erasure (ciphertext rows | live data keys | key tombstones): 3|0|1

## 9. What happens without a token, and with the wrong role

```http
GET /api/v1/patients/64119137706027/records HTTP/1.1
Host: localhost:8080
X-Correlation-Id: demo-d0feb3f30d34
```

**401 Unauthorized** (19 ms)

```http
Content-Type: application/problem+json
X-Correlation-Id: demo-d0feb3f30d34
WWW-Authenticate: Bearer realm="medigraph"
```

```json
{
  "type": "https://medigraph.dev/problems/unauthenticated",
  "title": "Unauthorized",
  "status": 401,
  "detail": "missing, expired or invalid bearer token",
  "instance": "/api/v1/patients/64119137706027/records",
  "correlationId": "demo-d0feb3f30d34"
}
```

```http
POST /api/v1/patients/64119137706027/prescriptions HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJraWQiOi…
Idempotency-Key: 84de216e-01ef-4b65-aac9-6a2ed04d8b65
X-Correlation-Id: demo-90110fe72d92
Content-Type: application/json
```

```json
{
  "drug": "paracetamol",
  "dosage": "1 g",
  "purpose": "TREATMENT"
}
```

**403 Forbidden** (43 ms)

```http
Content-Type: application/problem+json
X-Correlation-Id: demo-90110fe72d92
```

```json
{
  "type": "https://medigraph.dev/problems/forbidden",
  "title": "Forbidden",
  "status": 403,
  "detail": "your role is not allowed to perform this operation",
  "instance": "/api/v1/patients/64119137706027/prescriptions",
  "correlationId": "demo-90110fe72d92"
}
```

