#!/usr/bin/env python3
"""
seed_demo_ward.py — populate a realistic demo ward through the REAL APIs.

Registers and triages six patients across the zones (RED in Resus on a
monitored bed, two ORANGE in Acute on beds, YELLOW + GREEN in General,
one pediatric fever), with plausible vitals and today's timestamps —
exactly what a clinician sees walking into a live ED. Because everything
goes through the real registration + triage + bed-placement endpoints,
every downstream safety feature (SATS engine, zone routing, recheck
clocks, alerts) arms itself naturally.

The one non-API step: triage REQUIRES a rostered TRIAGE_NURSE (admins are
correctly denied), and rostering via API is charge-nurse-gated — so the
throwaway nurse's shift row is inserted via psql and removed afterwards.
The nurse user itself is created and deactivated via the admin API.

Usage:
  python3 scripts/seed_demo_ward.py \
      [--backend http://localhost:8080] \
      [--hospital <hospital-uuid>] \
      [--admin-email admin@smarttriage.com] [--admin-password ...] \
      [--db smarttriage_dev]

Idempotence: every run registers NEW patients (names get no suffix — run
it once per demo reset; re-running adds six more people to the queues).
"""
import argparse
import json
import subprocess
import sys
import time
import urllib.request

DEFAULT_HOSPITAL = "fe18e704-c38a-44ac-9e56-8f9247e98966"  # King Faisal (demo)

# ── The ward roster ──────────────────────────────────────────────────
# (registration fields, triage fields, target zone/bed wishes)
PATIENTS = [
    {
        "label": "RED — resus, severe respiratory distress",
        "register": {
            "firstName": "Theoneste", "lastName": "Habimana",
            "dateOfBirth": "1958-03-14", "gender": "MALE",
            "phoneNumber": "+250788100001",
            "arrivalMode": "AMBULANCE",
            "chiefComplaint": "Severe difficulty breathing, worsening since last night",
        },
        "triage": {
            "hasSevereRespiratoryDistress": True,
            "respiratoryRate": 32, "heartRate": 118, "systolicBP": 98,
            "diastolicBp": 62, "temperature": 38.9, "spo2": 87, "painScore": 4,
            "mobility": "STRETCHER", "avpu": "VERBAL", "traumaStatus": "NO_TRAUMA",
        },
        "bed_zone": "RESUS",
    },
    {
        "label": "ORANGE — acute, chest pain",
        "register": {
            "firstName": "Jean-Claude", "lastName": "Mutabazi",
            "dateOfBirth": "1974-07-02", "gender": "MALE",
            "phoneNumber": "+250788100002",
            "arrivalMode": "WALK_IN",
            "chiefComplaint": "Central chest pain radiating to left arm, 2 hours",
        },
        "triage": {
            "vuChestPain": True,
            "respiratoryRate": 20, "heartRate": 96, "systolicBP": 148,
            "diastolicBp": 92, "temperature": 36.8, "spo2": 95, "painScore": 7,
            "mobility": "WALKING", "avpu": "ALERT", "traumaStatus": "NO_TRAUMA",
        },
        "bed_zone": "ACUTE",
    },
    {
        "label": "ORANGE — acute, dyspnea",
        "register": {
            "firstName": "Immaculee", "lastName": "Mukagatare",
            "dateOfBirth": "1980-11-23", "gender": "FEMALE",
            "phoneNumber": "+250788100003",
            "arrivalMode": "WALK_IN",
            "chiefComplaint": "Shortness of breath on exertion, known asthmatic",
        },
        "triage": {
            "vuShortnessOfBreath": True,
            "respiratoryRate": 26, "heartRate": 104, "systolicBP": 132,
            "diastolicBp": 84, "temperature": 37.2, "spo2": 92, "painScore": 3,
            "mobility": "WALKING", "avpu": "ALERT", "traumaStatus": "NO_TRAUMA",
        },
        "bed_zone": "ACUTE",
    },
    {
        "label": "YELLOW — general, abdominal pain",
        "register": {
            "firstName": "Patrick", "lastName": "Nsengiyumva",
            "dateOfBirth": "1992-01-19", "gender": "MALE",
            "phoneNumber": "+250788100004",
            "arrivalMode": "WALK_IN",
            "chiefComplaint": "Right lower abdominal pain since this morning",
        },
        "triage": {
            "urgAbdominalPain": True,
            "respiratoryRate": 18, "heartRate": 88, "systolicBP": 124,
            "diastolicBp": 78, "temperature": 37.4, "spo2": 98, "painScore": 5,
            "mobility": "WALKING", "avpu": "ALERT", "traumaStatus": "NO_TRAUMA",
        },
        "bed_zone": None,
    },
    {
        "label": "GREEN — general, minor laceration",
        "register": {
            "firstName": "Chantal", "lastName": "Uwamahoro",
            "dateOfBirth": "1998-05-30", "gender": "FEMALE",
            "phoneNumber": "+250788100005",
            "arrivalMode": "WALK_IN",
            "chiefComplaint": "Small cut on left hand from kitchen knife",
        },
        "triage": {
            "respiratoryRate": 16, "heartRate": 76, "systolicBP": 118,
            "diastolicBp": 74, "temperature": 36.6, "spo2": 99, "painScore": 2,
            "mobility": "WALKING", "avpu": "ALERT", "traumaStatus": "TRAUMA",
        },
        "bed_zone": None,
    },
    {
        "label": "GREEN — general, sore throat",
        "register": {
            "firstName": "Eric", "lastName": "Habyarimana",
            "dateOfBirth": "1995-09-08", "gender": "MALE",
            "phoneNumber": "+250788100007",
            "arrivalMode": "WALK_IN",
            "chiefComplaint": "Sore throat and mild cough for three days",
        },
        "triage": {
            "respiratoryRate": 15, "heartRate": 72, "systolicBP": 121,
            "diastolicBp": 76, "temperature": 36.9, "spo2": 99, "painScore": 1,
            "mobility": "WALKING", "avpu": "ALERT", "traumaStatus": "NO_TRAUMA",
        },
        "bed_zone": None,
    },
    {
        "label": "PEDS — fever, 4-year-old",
        "register": {
            "firstName": "Kevine", "lastName": "Ishimwe",
            "dateOfBirth": "2022-04-11", "gender": "FEMALE",
            "phoneNumber": "+250788100006",
            "guardianName": "Josiane Ishimwe", "guardianPhone": "+250788100006",
            "guardianRelationship": "Mother",
            "arrivalMode": "WALK_IN",
            "chiefComplaint": "Fever and reduced feeding for two days",
        },
        "triage": {
            "respiratoryRate": 28, "heartRate": 128, "systolicBP": 96,
            "temperature": 38.8, "spo2": 97, "painScore": 2,
            "childWeightKg": 16.0,
            "mobility": "WITH_HELP", "avpu": "ALERT", "traumaStatus": "NO_TRAUMA",
        },
        "bed_zone": None,
    },
]


class ApiError(Exception):
    def __init__(self, code, detail):
        self.code, self.detail = code, detail
        super().__init__(f"{code}: {detail}")


def api(backend, method, path, token=None, body=None):
    req = urllib.request.Request(backend + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=15) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        raise ApiError(e.code, e.read().decode()[:300])


def psql(db, sql):
    """Run SQL via local psql; the datasource password is read the same way
    the dev recipes do (grep from application-dev.properties)."""
    cmd = (
        "export PGPASSWORD=$(grep -E '^spring.datasource.password' "
        "SmartTriage-server/src/main/resources/application-dev.properties "
        "| cut -d= -f2 | tr -d '[:space:]'); "
        f"psql -h localhost -U postgres -d {db} -t -A -c \"{sql}\""
    )
    out = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if out.returncode != 0:
        raise SystemExit(f"psql failed: {out.stderr.strip()}")
    return out.stdout.strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", default="http://localhost:8080")
    ap.add_argument("--hospital", default=DEFAULT_HOSPITAL)
    ap.add_argument("--admin-email", default="admin@smarttriage.com")
    ap.add_argument("--admin-password", default="SmartTriage@2026")
    ap.add_argument("--db", default="smarttriage_dev")
    args = ap.parse_args()
    base = args.backend + "/api/v1"

    print("1) admin login…")
    admin = api(args.backend, "POST", "/api/v1/auth/login",
                body={"email": args.admin_email, "password": args.admin_password})
    admin_tok = admin["data"]["accessToken"]

    # ── Throwaway triage nurse (triage denies admins — correctly) ──
    suffix = time.strftime("%m%d%H%M")
    nurse_email = f"zdemo.tnurse{suffix}@kfh.test"
    print(f"2) throwaway triage nurse {nurse_email}…")
    nurse = api(args.backend, "POST", "/api/v1/users", token=admin_tok, body={
        "email": nurse_email, "password": "ZDemo@2026x",
        "firstName": "Demo", "lastName": "TriageNurse",
        "role": "NURSE", "designation": "STAFF_NURSE", "hospitalId": args.hospital,
    })
    nurse_id = nurse["data"]["id"]
    # Shift row via SQL (rostering API is charge-nurse-gated). NIGHT spans
    # midnight: before 07:00 the ACTIVE night shift carries YESTERDAY's date.
    psql(args.db, f"""
INSERT INTO shift_assignments (id, user_id, hospital_id, shift_date, shift_period, zone, shift_function, is_active, created_at, is_shift_lead)
SELECT gen_random_uuid(), '{nurse_id}', '{args.hospital}',
  CASE WHEN EXTRACT(HOUR FROM now()) BETWEEN 7 AND 18 THEN CURRENT_DATE
       WHEN EXTRACT(HOUR FROM now()) >= 19 THEN CURRENT_DATE
       ELSE CURRENT_DATE - 1 END,
  CASE WHEN EXTRACT(HOUR FROM now()) BETWEEN 7 AND 18 THEN 'DAY' ELSE 'NIGHT' END,
  'TRIAGE', 'TRIAGE_NURSE', true, now(), false""")
    nurse_tok = api(args.backend, "POST", "/api/v1/auth/login",
                    body={"email": nurse_email, "password": "ZDemo@2026x"})["data"]["accessToken"]

    # Retire any orphaned demo nurses from earlier interrupted runs.
    psql(args.db, f"DELETE FROM shift_assignments WHERE user_id IN "
                  f"(SELECT id FROM users WHERE email LIKE 'zdemo.tnurse%@kfh.test' AND email <> '{nurse_email}')")
    psql(args.db, f"UPDATE users SET is_active=false WHERE email LIKE 'zdemo.tnurse%@kfh.test' AND email <> '{nurse_email}'")

    # ── Register + triage + place ──
    rows = []
    for p in PATIENTS:
        # Idempotence: a patient with this phone already in an active,
        # non-terminal visit means an earlier (possibly interrupted) run
        # covered them — skip instead of duplicating.
        phone = p["register"]["phoneNumber"]
        already = psql(args.db,
            "SELECT count(*) FROM patients pa JOIN visits v ON v.patient_id=pa.id "
            f"WHERE pa.phone_number='{phone}' AND v.is_active=true AND v.status NOT IN "
            "('DISCHARGED','DEPARTED','TRANSFERRED','ADMITTED','DECEASED','LWBS','REFERRED')")
        if already and int(already) > 0:
            print(f"   {p['register']['firstName']} {p['register']['lastName']:<18} already in an active visit — skipped")
            continue
        reg_body = dict(p["register"], hospitalId=args.hospital)
        reg = api(args.backend, "POST", "/api/v1/patients/register",
                  token=admin_tok, body=reg_body)
        data = reg["data"]
        visit_id = data.get("visitId") or data.get("visit", {}).get("id")
        visit_no = data.get("visitNumber") or data.get("visit", {}).get("visitNumber")

        tri = api(args.backend, "POST", "/api/v1/triage", token=nurse_tok,
                  body=dict(p["triage"], visitId=visit_id))
        cat = tri["data"].get("triageCategory")
        tews = tri["data"].get("tewsScore")
        zone = tri["data"].get("assignedZone") or ""

        bed_code = ""
        if p["bed_zone"]:
            free = api(args.backend, "GET",
                       f"/api/v1/beds/hospital/{args.hospital}/zone/{p['bed_zone']}/available",
                       token=admin_tok)["data"]
            if free:
                bed = free[0]
                try:
                    api(args.backend, "POST", f"/api/v1/beds/{bed['id']}/place",
                        token=admin_tok, body={"visitId": visit_id})
                    bed_code = bed.get("code", "?")
                except ApiError as e:
                    if "already placed" in e.detail:
                        # RED triage auto-places into Resus — that IS success.
                        bed_code = e.detail.split("bed ")[-1].split(".")[0] if "bed " in e.detail else "auto"
                    else:
                        raise
        name = f'{p["register"]["firstName"]} {p["register"]["lastName"]}'
        rows.append((name, visit_no, cat, tews, zone, bed_code, p["label"]))
        print(f"   {name:<24} {visit_no}  {cat} (TEWS {tews})  {zone} {bed_code}")

    # ── Cleanup: retire the throwaway nurse ──
    print("3) retiring throwaway nurse…")
    psql(args.db, f"DELETE FROM shift_assignments WHERE user_id='{nurse_id}'")
    psql(args.db, f"UPDATE users SET is_active=false WHERE id='{nurse_id}'")

    print("\nDemo ward seeded:")
    for r in rows:
        print(f"  {r[2]:<7} {r[0]:<24} {r[4]:<12} bed={r[5] or '—':<8} {r[1]}")


if __name__ == "__main__":
    main()
