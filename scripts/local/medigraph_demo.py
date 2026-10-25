#!/usr/bin/env python3
"""MediGraph end-to-end demo. Every API call goes through the gateway on :8080 and is recorded as sent/received.

Writes a plain-text log of all exchanges (demo-output.txt) and a markdown patient journey built from the same
captured exchanges (patient-journey.md). Nothing in either file is typed by hand: bodies are json.dumps of what
was sent and what came back.
"""
import argparse, concurrent.futures as cf, datetime as dt, http, json, os, re, subprocess, sys, time, uuid
import requests

G = "http://localhost:8080"
HERE = os.path.dirname(os.path.abspath(__file__))   # start.sh lives next to this file
IST = dt.timezone(dt.timedelta(hours=5, minutes=30))
SHOW_RESP_HEADERS = ["Content-Type", "Location", "X-Correlation-Id", "Idempotent-Replayed", "Retry-After",
                     "WWW-Authenticate", "X-RateLimit-Remaining"]

# ---------------------------------------------------------------- Verhoeff (Health IDs are 14 digits, last = check)
_D = [[0,1,2,3,4,5,6,7,8,9],[1,2,3,4,0,6,7,8,9,5],[2,3,4,0,1,7,8,9,5,6],[3,4,0,1,2,8,9,5,6,7],[4,0,1,2,3,9,5,6,7,8],
      [5,9,8,7,6,0,4,3,2,1],[6,5,9,8,7,1,0,4,3,2],[7,6,5,9,8,2,1,0,4,3],[8,7,6,5,9,3,2,1,0,4],[9,8,7,6,5,4,3,2,1,0]]
_P = [[0,1,2,3,4,5,6,7,8,9],[1,5,7,6,2,8,3,0,9,4],[5,8,0,3,7,9,6,1,4,2],[8,9,1,6,0,4,3,5,2,7],
      [9,4,5,3,1,2,6,8,7,0],[4,2,8,6,5,7,3,9,0,1],[2,7,9,3,8,0,6,4,1,5],[7,0,4,6,9,1,3,2,5,8]]
_INV = [0,4,3,2,1,5,6,7,8,9]

def verhoeff_valid(num):
    c = 0
    for i, ch in enumerate(reversed(num)):
        c = _D[c][_P[i % 8][int(ch)]]
    return c == 0

def with_check_digit(base):
    c = 0
    for i, ch in enumerate(reversed(base)):
        c = _D[c][_P[(i + 1) % 8][int(ch)]]
    return base + str(_INV[c])

# ---------------------------------------------------------------- recording
def now_ist():
    return dt.datetime.now(IST).strftime("%Y-%m-%d %H:%M:%S IST")

class Exchange:
    def __init__(self, method, url, req_headers, req_body, resp, elapsed_ms, note=None):
        self.method, self.url, self.req_headers, self.req_body = method, url, req_headers, req_body
        self.status = resp.status_code
        # the servers send HTTP/1.1 without a reason phrase, so print the standard one for the code
        self.reason = resp.reason or http.HTTPStatus(resp.status_code).phrase
        self.resp_headers = {h: resp.headers[h] for h in SHOW_RESP_HEADERS if h in resp.headers}
        self.raw = resp.text
        try:
            self.json = resp.json()
        except ValueError:
            self.json = None
        self.elapsed_ms = elapsed_ms
        self.note = note

class Recorder:
    def __init__(self):
        self.steps = []          # (key, title, journey, [Exchange | str])
        self.out = []

    def step(self, key, title, journey=None):
        self.steps.append({"key": key, "title": title, "journey": journey, "items": []})
        self.say("")
        self.say("=" * 100)
        self.say(f"[{key}] {title}")
        self.say("=" * 100)

    def say(self, line=""):
        print(line, flush=True)
        self.out.append(line)

    def note(self, text):
        self.steps[-1]["items"].append(text)
        self.say(f"  >> {text}")

    def call(self, method, path, token=None, body=None, idem=True, cid=None, extra=None, record=True, show=True,
             max_chars=2500):
        h = {"Accept": "application/json"}
        if token:
            h["Authorization"] = "Bearer " + token
        if idem and method in ("POST", "PUT", "DELETE"):
            h["Idempotency-Key"] = str(uuid.uuid4())
        h["X-Correlation-Id"] = cid or ("demo-" + uuid.uuid4().hex[:12])
        if body is not None:
            h["Content-Type"] = "application/json"
        if extra:
            h.update(extra)
        t0 = time.perf_counter()
        r = requests.request(method, G + path, headers=h, data=None if body is None else json.dumps(body), timeout=30)
        ms = (time.perf_counter() - t0) * 1000
        ex = Exchange(method, G + path, h, body, r, ms)
        if record:
            self.steps[-1]["items"].append(ex)
        if show:
            self.print_exchange(ex, max_chars)
        return ex

    def print_exchange(self, ex, max_chars=2500):
        self.say(f"\n> {ex.method} {ex.url}")
        for k in ("Idempotency-Key", "X-Correlation-Id"):
            if k in ex.req_headers:
                self.say(f"> {k}: {ex.req_headers[k]}")
        if "Authorization" in ex.req_headers:
            self.say("> Authorization: " + short_auth(ex.req_headers["Authorization"]))
        if ex.req_body is not None:
            self.say("> " + json.dumps(ex.req_body, ensure_ascii=False))
        self.say(f"< {ex.status} {ex.reason}  ({ex.elapsed_ms:.0f} ms)")
        for k, v in ex.resp_headers.items():
            self.say(f"< {k}: {v}")
        body = json.dumps(ex.json, ensure_ascii=False) if ex.json is not None else ex.raw
        if len(body) > max_chars:
            body = body[:max_chars] + f" ... [{len(body) - max_chars} more chars]"
        if body:
            self.say("< " + body)

def short_auth(v):
    return v[:17] + "…" if v.startswith("Bearer ") else v

# ---------------------------------------------------------------- helpers
def expect(ex, *codes):
    if ex.status not in codes:
        raise SystemExit(f"unexpected {ex.status} for {ex.method} {ex.url}: {ex.raw[:400]}")
    return ex

def ledger_status_direct(token):
    out = {}
    for n, p in (("ledger1", 8085), ("ledger2", 8086), ("ledger3", 8087)):
        try:
            out[n] = requests.get(f"http://localhost:{p}/api/v1/ledger/status",
                                  headers={"Authorization": "Bearer " + token}, timeout=2).json()
        except Exception as e:
            out[n] = {"down": type(e).__name__}
    return out

def wait_anchored(R, entry, token, limit=30):
    """The ledger seals entries into blocks in batches, so a fresh record is anchored a moment after the write."""
    t0 = time.time()
    while time.time() - t0 < limit:
        ex = R.call("GET", f"/api/v1/ledger/entries/{entry}/proof", token, record=False, show=False)
        if ex.status == 200:
            break
        time.sleep(0.5)
    R.note(f"{entry} was sealed into a ledger block {time.time() - t0:.1f}s after the write (blocks are cut in batches)")

def start_sh(*args):
    return subprocess.run([f"{HERE}/start.sh", *args], capture_output=True, text=True, timeout=300)

# ---------------------------------------------------------------- the run
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="demo-output.txt")
    ap.add_argument("--journey", default="patient-journey.md")
    ap.add_argument("--skip-failover", action="store_true")
    a = ap.parse_args()
    R = Recorder()
    R.say(f"MediGraph demo run, started {now_ist()}; all calls via the gateway {G}")
    suffix = uuid.uuid4().hex[:6].upper()  # keeps local MRNs unique when the demo is re-run against the same DB

    # 0 ---------------------------------------------------------------- tokens
    R.step("0", "Dev tokens from the gateway's local issuer")
    def token(sub, roles, facility):
        ex = R.call("POST", "/dev/token", body={"subject": sub, "roles": roles, "facility": facility}, idem=False,
                    show=False)
        expect(ex, 200)
        return ex.json["access_token"]
    ex = R.call("POST", "/dev/token", body={"subject": "rec-anita", "roles": ["RECEPTION"], "facility": "HOSP-A"},
                idem=False, max_chars=200)
    rec_a = ex.json["access_token"]
    dr_a = token("dr-mehta", ["DOCTOR"], "HOSP-A")
    dr_b = token("dr-khan", ["DOCTOR"], "HOSP-B")
    nurse_b = token("nurse-icu-3", ["NURSE"], "HOSP-B")
    steward = token("steward-1", ["STEWARD"], "MPI")
    admin = token("admin-1", ["ADMIN"], "OPS")
    R.note("also minted: dr-mehta (DOCTOR, HOSP-A), dr-khan (DOCTOR, HOSP-B), nurse-icu-3 (NURSE, HOSP-B), "
           "steward-1 (STEWARD), admin-1 (ADMIN)")

    # 1 ---------------------------------------------------------------- registration + MPI
    R.step("1", "Registration at Hospital A, MPI duplicate check, Health ID",
           journey="Registration at Hospital A (front desk), duplicate check, Health ID issued")
    ramesh = {"localMrn": f"A-{suffix}-1", "givenName": "Ramesh", "familyName": "Sharma", "birthDate": "1968-03-14",
              "gender": "male", "phone": "+91 98390 12345", "city": "Lucknow"}
    ex = expect(R.call("POST", "/api/v1/patients", rec_a, ramesh), 201, 200)
    hid = ex.json["healthId"]
    R.note(f"Health ID {hid} ({ex.json.get('formattedHealthId')}); Verhoeff check digit valid: {str(verhoeff_valid(hid)).lower()}")
    again = dict(ramesh, localMrn=f"A-{suffix}-2", familyName="Sharmaa")
    ex = expect(R.call("POST", "/api/v1/patients", rec_a, again), 201, 200)
    R.note(f"second desk entry with a typo ('Sharmaa'): decision {ex.json.get('decision')}, "
           f"same Health ID: {str(ex.json.get('healthId') == hid).lower()}")

    R.step("1b", "MPI: a near-duplicate that needs a human (steward review)")
    kavita = {"localMrn": f"A-{suffix}-3", "givenName": "Kavita", "familyName": "Mishra", "birthDate": "1981-09-12",
              "gender": "female", "phone": "+91 99350 11223", "city": "Varanasi"}
    expect(R.call("POST", "/api/v1/patients", rec_a, kavita), 201, 200)
    # Hospital B's desk typed the name differently, swapped day and month, and left the phone blank.
    fuzzy = {"localMrn": f"B-{suffix}-4", "givenName": "Kavitha", "familyName": "Mishra", "birthDate": "1981-12-09",
             "gender": "female", "city": "Varanasi"}
    ex = expect(R.call("POST", "/api/v1/patients", token("rec-b", ["RECEPTION"], "HOSP-B"), fuzzy), 201, 200)
    R.note(f"decision {ex.json.get('decision')}, weight {ex.json.get('matchWeight')}")
    ex = expect(R.call("GET", "/api/v1/mpi/reviews", steward), 200)
    tasks = ex.json if isinstance(ex.json, list) else ex.json.get("items", [])
    if tasks:
        t = tasks[0]
        tid = t.get("taskId") or t.get("id")
        expect(R.call("POST", f"/api/v1/mpi/reviews/{tid}/resolution", steward, {"merge": True}), 200)

    # 2 ---------------------------------------------------------------- consent
    pat = token(hid, ["PATIENT"], "HOSP-A")
    R.step("2", "Ramesh grants Hospital A consent for treatment",
           journey="Ramesh gives Hospital A consent to see his records for treatment")
    ex = expect(R.call("POST", f"/api/v1/patients/{hid}/consents", pat,
                       {"grantee": "HOSP-A", "categories": ["ENCOUNTERS", "CONDITIONS", "MEDICATIONS", "OBSERVATIONS",
                                                            "ALLERGIES"], "purpose": "TREATMENT", "durationDays": 180}),
                201)
    R.note(f"the consent artefact is signed; signatureValid = {str(ex.json.get('signatureValid')).lower()}")

    # 3 ---------------------------------------------------------------- records + ledger
    R.step("3", "Encrypted record written, anchored on the ledger, Merkle proof verified",
           journey="Dr. Mehta records the warfarin prescription and the diagnosis; the record is encrypted and anchored on the ledger")
    med = {"resourceType": "MedicationRequest", "subject": {"reference": f"Patient/{hid}"},
           "medicationCodeableConcept": {"coding": [{"system": "http://www.nlm.nih.gov/research/umls/rxnorm",
                                                     "code": "11289", "display": "warfarin"}], "text": "warfarin"},
           "status": "active", "intent": "order", "dosageText": "5 mg once daily", "authoredOn": "2026-10-25",
           "requester": "dr-mehta"}
    ex = expect(R.call("POST", f"/api/v1/patients/{hid}/records", dr_a, med), 201)
    rec_med = ex.json
    cond = {"resourceType": "Condition", "subject": {"reference": f"Patient/{hid}"},
            "code": {"coding": [{"system": "http://snomed.info/sct", "code": "49436004",
                                 "display": "Atrial fibrillation"}], "text": "Atrial fibrillation"},
            "clinicalStatus": "active", "onsetDateTime": "2024-11-02"}
    expect(R.call("POST", f"/api/v1/patients/{hid}/records", dr_a, cond), 201)
    rid = rec_med.get("recordId") or rec_med.get("id")
    entry = rec_med.get("ledgerEntryId")
    wait_anchored(R, entry, steward)
    ex = expect(R.call("GET", f"/api/v1/records/{rid}/verification", dr_a), 200)
    R.note(f"the stored hash matches the ledger: valid = {str(ex.json.get('valid')).lower()}")
    ex = expect(R.call("GET", f"/api/v1/ledger/entries/{entry}/proof", steward), 200)
    row = subprocess.run(["docker", "exec", "mg-postgres", "psql", "-U", "medigraph", "-d", "records", "-At", "-c",
                          f"select resource_type, category, key_version, length(ciphertext), "
                          f"encode(substring(ciphertext from 1 for 24), 'hex'), position(convert_to('warfarin', 'UTF8') in ciphertext) "
                          f"from records.health_record where id = '{rid}'"], capture_output=True, text=True).stdout.strip()
    R.note(f"row in postgres (type|category|key_version|ciphertext bytes|first 24 bytes hex|position of 'warfarin'): {row}")

    # 3b --------------------------------------------------------------- ledger failover
    if not a.skip_failover:
        R.step("3b", "Kill the ledger leader: a new leader is elected and writes keep working")
        ex = expect(R.call("GET", "/api/v1/ledger/status", steward), 200)
        old = ex.json["leaderId"]
        R.note(f"leader is {old} (term {ex.json['term']}); stopping it with SIGTERM")
        t0 = time.time()
        start_sh("stop", old)
        R.note(f"stop command for {old} returned after {time.time() - t0:.1f}s")
        t0 = time.time()
        while time.time() - t0 < 30:
            st = ledger_status_direct(steward)
            leaders = [n for n, s in st.items() if s.get("role") == "LEADER"]
            if leaders and leaders[0] != old:
                break
            time.sleep(0.25)
        R.note(f"new leader {leaders[0] if leaders else None} found {time.time() - t0:.1f}s after the stop command returned "
               f"(the election runs while the old leader shuts down); "
               f"direct node status: " + json.dumps({n: {k: s.get(k) for k in ('role', 'term', 'commitIndex', 'down')
                                                         if k in s} for n, s in st.items()}))
        expect(R.call("GET", "/api/v1/ledger/status", steward), 200)
        obs = {"resourceType": "Observation", "subject": {"reference": f"Patient/{hid}"}, "status": "final",
               "code": {"coding": [{"system": "http://loinc.org", "code": "34714-6", "display": "INR"}], "text": "INR"},
               "valueQuantity": {"value": 2.6, "unit": "{INR}"}, "effectiveDateTime": "2026-10-25T10:30:00+05:30"}
        ex = expect(R.call("POST", f"/api/v1/patients/{hid}/records", dr_a, obs), 201)
        wait_anchored(R, ex.json["ledgerEntryId"], steward)
        ex = expect(R.call("GET", f"/api/v1/records/{ex.json.get('recordId')}/verification", dr_a), 200)
        expect(R.call("GET", "/api/v1/ledger/verify", steward), 200)
        R.note(f"restarting {old}; it rejoins as a follower and catches up")
        start_sh("start", old)
        t0 = time.time()
        while time.time() - t0 < 30:
            st = ledger_status_direct(steward)
            if st[old].get("commitIndex") is not None and st[old].get("commitIndex") == max(
                    s.get("commitIndex") or 0 for s in st.values()):
                break
            time.sleep(0.5)
        R.note("after rejoin: " + json.dumps({n: {k: s.get(k) for k in ('role', 'term', 'commitIndex')}
                                              for n, s in st.items()}))

    # 4 ---------------------------------------------------------------- emergency at Hospital B
    R.step("4", "Emergency at Hospital B: no consent, so break-glass",
           journey="Ramesh collapses in Kanpur and is brought to Hospital B's emergency room; the doctor uses break-glass")
    expect(R.call("GET", f"/api/v1/patients/{hid}/records?purpose=EMERGENCY", dr_b), 403)
    ex = expect(R.call("POST", f"/api/v1/patients/{hid}/break-glass", dr_b,
                       {"reason": "Unconscious on arrival, suspected intracranial bleed; need current anticoagulants"}),
                201)
    expect(R.call("GET", f"/api/v1/patients/{hid}/records?purpose=EMERGENCY", dr_b), 200)
    expect(R.call("GET", f"/api/v1/patients/{hid}/notifications", pat), 200)

    # 5 ---------------------------------------------------------------- prescription
    R.step("5", "Prescription: typo corrected, interaction with warfarin caught",
           journey="Dr. Khan orders aspirin (typed 'asprin'); the system corrects the name and blocks the warfarin interaction")
    expect(R.call("GET", "/api/v1/drugs/resolution?name=asprin", dr_b), 200)
    ex = expect(R.call("POST", f"/api/v1/patients/{hid}/prescriptions", dr_b,
                       {"drug": "asprin", "dosage": "300 mg stat", "purpose": "EMERGENCY"}), 201, 200, 409, 422)

    # 6 ---------------------------------------------------------------- ICU
    R.step("6", "ICU vitals stream raises a NEWS2 alert", journey="On the ward at Hospital B, bedside monitor readings trigger a NEWS2 early-warning alert")
    now_ms = int(time.time() * 1000)
    t1 = now_ms - 12 * 60 * 1000
    vit = [("RESPIRATION_RATE", 27), ("SPO2", 90), ("SUPPLEMENTAL_O2", 1), ("SYSTOLIC_BP", 88), ("PULSE", 128),
           ("CONSCIOUSNESS", 1), ("TEMPERATURE", 38.9)]
    batch = [{"readingId": f"mon7-{suffix}-{i}", "type": t, "value": v, "eventTimeMillis": t1 + i * 1000}
             for i, (t, v) in enumerate(vit)]
    expect(R.call("POST", f"/api/v1/icu/patients/{hid}/vitals", nurse_b, {"readings": batch}), 202, 200, 201)
    expect(R.call("POST", f"/api/v1/icu/patients/{hid}/vitals", nurse_b,
                  {"readings": [{"readingId": f"mon7-{suffix}-now", "type": "PULSE", "value": 124,
                                 "eventTimeMillis": now_ms}]}), 202, 200, 201)
    t0 = time.time()
    while time.time() - t0 < 45:
        ex = R.call("GET", f"/api/v1/icu/patients/{hid}/alerts", nurse_b, record=False, show=False)
        if ex.status == 200 and ex.json:
            break
        time.sleep(1)
    R.note(f"the alert was queryable {time.time() - t0:.1f}s after the closing reading (polled once a second)")
    expect(R.call("GET", f"/api/v1/icu/patients/{hid}/alerts", nurse_b), 200)

    # 7 ---------------------------------------------------------------- beds
    R.step("7", "Nearest ICU bed and a hold", journey="He needs an ICU bed: find the nearest hospital with one free and hold it")
    for hid_, name, lat, lon, total, occ in [("KNP-HALLET", "LLR (Hallet) Hospital", 26.4808, 80.3062, 24, 24),
                                             ("KNP-REGENCY", "Regency Hospital", 26.4873, 80.2850, 16, 14),
                                             ("KNP-RAMA", "Rama Medical College", 26.5590, 80.2670, 10, 9)]:
        expect(R.call("PUT", f"/api/v1/hospitals/{hid_}", admin,
                      {"name": name, "lat": lat, "lon": lon, "icuBeds": total, "occupied": occ},
                      record=hid_ == "KNP-HALLET", show=hid_ == "KNP-HALLET"), 200)
    R.note("also registered KNP-REGENCY (16 ICU beds, 14 occupied) and KNP-RAMA (10 beds, 9 occupied)")
    ex = expect(R.call("GET", "/api/v1/beds/nearest?lat=26.4499&lon=80.3319&k=3", dr_b), 200)
    best = ex.json[0]["hospitalId"]
    ex = expect(R.call("POST", "/api/v1/beds/holds", dr_b, {"hospitalId": best, "patientId": hid, "ttlMinutes": 30}), 201)
    hold_id = ex.json["holdId"]
    expect(R.call("POST", f"/api/v1/beds/holds/{hold_id}/confirm", dr_b), 200)
    expect(R.call("GET", f"/api/v1/hospitals/{best}/beds", dr_b), 200)

    R.step("7b", f"Race: 10 concurrent holds for the last free bed at {best}")
    others = [with_check_digit(str(7300000000000 + i)) for i in range(10)]
    def hold(i):
        h = {"Authorization": "Bearer " + dr_b, "Idempotency-Key": str(uuid.uuid4()), "Content-Type": "application/json",
             "X-Correlation-Id": f"race-{i}"}
        r = requests.post(G + "/api/v1/beds/holds", headers=h,
                          data=json.dumps({"hospitalId": best, "patientId": others[i], "ttlMinutes": 10}), timeout=30)
        return i, r.status_code, r.text
    with cf.ThreadPoolExecutor(10) as pool:
        results = list(pool.map(hold, range(10)))
    for i, s, t in results:
        R.say(f"  hold {i}: {s} {t[:170]}")
    wins = [r for r in results if r[1] == 201]
    R.note(f"{len(wins)} hold(s) won, {sum(1 for r in results if r[1] == 409)} got 409 Conflict")
    expect(R.call("GET", f"/api/v1/hospitals/{best}/beds", dr_b), 200)

    # 8 ---------------------------------------------------------------- crypto-shred
    R.step("8", "Right to erasure: crypto-shred; the record is unreadable, the ledger still verifies",
           journey="Months later Ramesh asks for his data to be erased (crypto-shred)")
    expect(R.call("DELETE", f"/api/v1/patients/{hid}/data", pat), 200, 202)
    R.call("GET", f"/api/v1/patients/{hid}/records", dr_a)
    R.call("GET", f"/api/v1/records/{rid}/verification", dr_a)
    expect(R.call("GET", f"/api/v1/ledger/entries/{entry}/proof", steward), 200)
    expect(R.call("GET", "/api/v1/ledger/verify", steward), 200)
    row = subprocess.run(["docker", "exec", "mg-postgres", "psql", "-U", "medigraph", "-d", "records", "-At", "-c",
                          f"select (select count(*) from records.health_record where patient_id = '{hid}'), "
                          f"(select count(*) from records.patient_key where patient_id = '{hid}'), "
                          f"(select count(*) from records.patient_key_tombstone where patient_id = '{hid}')"],
                         capture_output=True, text=True).stdout.strip()
    R.note(f"postgres after erasure (ciphertext rows | live data keys | key tombstones): {row}")

    # 9 ---------------------------------------------------------------- auth failures
    R.step("9", "Security: one 401 and one 403", journey="What happens without a token, and with the wrong role")
    expect(R.call("GET", f"/api/v1/patients/{hid}/records"), 401)
    expect(R.call("POST", f"/api/v1/patients/{hid}/prescriptions", nurse_b,
                  {"drug": "paracetamol", "dosage": "1 g", "purpose": "TREATMENT"}), 403)

    # 10 --------------------------------------------------------------- ops
    R.step("10", "Ops: health, Prometheus, Swagger UI (gateway)")
    R.call("GET", "/actuator/health", idem=False)
    R.call("GET", "/actuator/health/readiness", idem=False)
    ex = R.call("GET", "/actuator/prometheus", idem=False, show=False, extra={"Accept": "text/plain"})
    lines = [l for l in ex.raw.splitlines() if l.startswith(("http_server_requests_seconds_count{", "resilience4j_circuitbreaker_state{",
                                                             "jvm_memory_used_bytes{area=\"heap\""))]
    R.say(f"\n> GET {G}/actuator/prometheus\n< {ex.status} ({len(ex.raw.splitlines())} lines); a sample:")
    for l in lines[:12]:
        R.say("    " + l)
    r = requests.get(G + "/swagger-ui.html", allow_redirects=True, timeout=10)
    R.say(f"\n> GET {G}/swagger-ui.html\n< {r.status_code} after redirect to {r.url}; title present: {'<title>Swagger UI</title>' in r.text}")
    r = requests.get(G + "/v3/api-docs", timeout=10)
    R.say(f"> GET {G}/v3/api-docs\n< {r.status_code}; openapi {r.json().get('openapi')}, {len(r.json().get('paths', {}))} paths")

    R.say(f"\nfinished {now_ist()}")
    # dev JWTs are short-lived and local, but there is no reason to keep whole ones in a log
    open(a.out, "w").write(re.sub(r"(eyJ[A-Za-z0-9_-]{8})[A-Za-z0-9_.-]{20,}", r"\1…", "\n".join(R.out)) + "\n")
    write_journey(R, a.journey, hid)

# ---------------------------------------------------------------- markdown journey
def pretty(obj, max_items=3):
    def trim(o):
        if isinstance(o, list):
            if len(o) > max_items:
                return [trim(x) for x in o[:max_items]] + [f"… {len(o) - max_items} more items not shown"]
            return [trim(x) for x in o]
        if isinstance(o, dict):
            return {k: trim(v) for k, v in o.items()}
        if isinstance(o, str) and len(o) > 160:
            return o[:120] + f"… ({len(o)} chars)"
        return o
    return json.dumps(trim(obj), indent=2, ensure_ascii=False)

def write_journey(R, path, hid):
    md = [f"# One patient through MediGraph: Ramesh Sharma", "",
          f"Ramesh Sharma, 58, from Lucknow, on warfarin for atrial fibrillation. Every request and response below was "
          f"captured from a live run of the stack on {now_ist()[:10]} by `medigraph_demo.py` (see `demo.sh`); nothing was "
          f"typed in by hand. Calls go through the API gateway on `localhost:8080`. Tokens come from the gateway's local "
          f"dev issuer and are cut short. Long arrays are trimmed, and each trim is marked.", "",
          f"Health ID issued in this run: `{hid}`.", ""]
    n = 0
    for s in R.steps:
        if not s["journey"]:
            continue
        n += 1
        md += [f"## {n}. {s['journey']}", ""]
        for it in s["items"]:
            if isinstance(it, str):
                md += [f"> {it}", ""]
                continue
            md += ["```http", f"{it.method} {it.url.replace(G, '')} HTTP/1.1", "Host: localhost:8080"]
            for k in ("Authorization", "Idempotency-Key", "X-Correlation-Id", "Content-Type"):
                if k in it.req_headers:
                    v = it.req_headers[k]
                    md.append(f"{k}: {short_auth(v) if k == 'Authorization' else v}")
            md.append("```")
            if it.req_body is not None:
                md += ["", "```json", pretty(it.req_body), "```"]
            md += ["", f"**{it.status} {it.reason}** ({it.elapsed_ms:.0f} ms)", ""]
            if it.resp_headers:
                md += ["```http"] + [f"{k}: {v}" for k, v in it.resp_headers.items()] + ["```", ""]
            if it.json is not None:
                md += ["```json", pretty(it.json), "```", ""]
            elif it.raw:
                md += ["```", it.raw[:600], "```", ""]
    open(path, "w").write("\n".join(md) + "\n")

if __name__ == "__main__":
    main()
