#!/usr/bin/env python3
"""End-to-end test for the Metabase Cloudflare D1 driver.

Drives a running Metabase instance (with the d1 plugin installed) through its HTTP API against a D1 database
(real Cloudflare, or the emulator in d1_mock_server.py) that has been seeded with test/seed.sql:

  1. completes initial setup
  2. adds the D1 database (validates driver/can-connect?)
  3. waits for sync, asserts tables / column types / PK / FK metadata
  4. runs an MBQL row query (temporal values must come back as timestamps)
  5. runs a date-bucketed aggregation (exercises the SQLite strftime dialect)
  6. runs an implicit-FK-join breakout with a date filter
  7. runs a native query with a template-tag parameter
  8. asserts a bad API token is rejected and a SQL error surfaces cleanly

Config via env: MB_URL (default http://localhost:3000), D1_ACCOUNT_ID, D1_DATABASE_ID, D1_API_TOKEN,
D1_API_BASE_URL (optional, for the mock server).
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

MB_URL = os.environ.get("MB_URL", "http://localhost:3000").rstrip("/")
DETAILS = {
    "account-id": os.environ["D1_ACCOUNT_ID"],
    "database-id": os.environ["D1_DATABASE_ID"],
    "api-token": os.environ["D1_API_TOKEN"],
}
if os.environ.get("D1_API_BASE_URL"):
    DETAILS["api-base-url"] = os.environ["D1_API_BASE_URL"]

SESSION = None
FAILURES = []


def api(method, path, body=None, expect_error=False):
    req = urllib.request.Request(f"{MB_URL}{path}", method=method)
    req.add_header("Content-Type", "application/json")
    if SESSION:
        req.add_header("X-Metabase-Session", SESSION)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=120) as resp:
            return json.loads(resp.read() or "null")
    except urllib.error.HTTPError as e:
        payload = e.read()
        if expect_error:
            try:
                return json.loads(payload)
            except Exception:
                return {"_status": e.code, "_body": payload.decode(errors="replace")}
        raise AssertionError(f"{method} {path} -> HTTP {e.code}: {payload[:800]}") from None


def check(name, ok, detail=""):
    print(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  ({detail})" if detail and not ok else ""))
    if not ok:
        FAILURES.append(f"{name}: {detail}")


def wait_for_metabase(timeout=300):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(f"{MB_URL}/api/health", timeout=5) as resp:
                if resp.status == 200:
                    return
        except Exception:
            pass
        time.sleep(3)
    raise AssertionError(f"Metabase did not become healthy at {MB_URL} within {timeout}s")


def setup_admin():
    global SESSION
    props = api("GET", "/api/session/properties")
    token = props["setup-token"]
    if token is None:
        # instance already set up (e.g. rerun against a warm instance): log in instead
        resp = api("POST", "/api/session", {"username": "admin@example.com", "password": "Sup3rSecret!123"})
    else:
        resp = api("POST", "/api/setup", {
            "token": token,
            "user": {"first_name": "CI", "last_name": "Bot", "email": "admin@example.com",
                     "password": "Sup3rSecret!123"},
            "prefs": {"site_name": "d1-e2e", "allow_tracking": False},
        })
    SESSION = resp["id"]


def add_database():
    db = api("POST", "/api/database", {"engine": "d1", "name": "D1 E2E", "details": DETAILS})
    check("add database (can-connect?)", isinstance(db.get("id"), int), json.dumps(db)[:300])
    return db["id"]


def wait_for_sync(db_id, timeout=180):
    deadline = time.time() + timeout
    while time.time() < deadline:
        db = api("GET", f"/api/database/{db_id}")
        if db["initial_sync_status"] == "complete":
            return
        time.sleep(3)
    raise AssertionError("sync did not complete in time")


def get_metadata(db_id):
    meta = api("GET", f"/api/database/{db_id}/metadata")
    tables = {t["name"]: t for t in meta["tables"]}
    fields = {(t["name"], f["name"]): f for t in meta["tables"] for f in t["fields"]}
    return tables, fields


def assert_schema(tables, fields):
    check("sync found customers + orders", set(tables) == {"customers", "orders"}, str(set(tables)))
    expectations = {
        ("customers", "id"): "type/Integer",
        ("customers", "name"): "type/Text",
        ("customers", "is_active"): "type/Boolean",
        ("customers", "created_at"): "type/DateTime",
        ("orders", "total"): "type/Float",
        ("orders", "ordered_at"): "type/DateTime",
    }
    for key, base_type in expectations.items():
        actual = fields.get(key, {}).get("base_type")
        check(f"base type {key[0]}.{key[1]} = {base_type}", actual == base_type, f"got {actual}")
    check("customers.id is PK", fields[("customers", "id")]["semantic_type"] == "type/PK")
    fk = fields[("orders", "customer_id")]
    check("orders.customer_id FK -> customers.id",
          fk["semantic_type"] == "type/FK" and fk["fk_target_field_id"] == fields[("customers", "id")]["id"],
          json.dumps({k: fk.get(k) for k in ("semantic_type", "fk_target_field_id")}))


def run_dataset(query):
    # failed queries can come back with a non-2xx HTTP status but still carry the standard
    # {"status": "failed", "error": ...} payload, so parse error bodies too
    return api("POST", "/api/dataset", query, expect_error=True)


def test_queries(db_id, tables, fields):
    orders = tables["orders"]["id"]
    ordered_at = fields[("orders", "ordered_at")]["id"]
    total = fields[("orders", "total")]["id"]
    cust_fk = fields[("orders", "customer_id")]["id"]
    cust_name = fields[("customers", "name")]["id"]

    # MBQL row query: temporal strings must be parsed into timestamps
    r = run_dataset({"database": db_id, "type": "query", "query": {"source-table": orders, "limit": 3}})
    check("MBQL row query completes", r.get("status") == "completed", json.dumps(r)[:300])
    rows = r["data"]["rows"]
    check("MBQL rows returned", len(rows) == 3, str(len(rows)))
    check("temporal column parsed to timestamp", "T" in str(rows[0][4]), str(rows[0][4]))

    # date-bucketed aggregation (strftime dialect)
    r = run_dataset({"database": db_id, "type": "query", "query": {
        "source-table": orders,
        "aggregation": [["sum", ["field", total, None]]],
        "breakout": [["field", ordered_at, {"temporal-unit": "month"}]]}})
    check("month bucketing completes", r.get("status") == "completed", json.dumps(r)[:300])
    by_month = {row[0][:7]: row[1] for row in r["data"]["rows"]}
    check("feb sum = 135.5", abs(by_month.get("2026-02", 0) - 135.5) < 0.001, str(by_month))
    check("mar sum = 255.75", abs(by_month.get("2026-03", 0) - 255.75) < 0.001, str(by_month))
    check("bucketing uses SQLite date()", "start of month" in r["data"]["native_form"]["query"])

    # implicit FK join + date filter
    r = run_dataset({"database": db_id, "type": "query", "query": {
        "source-table": orders,
        "aggregation": [["count"]],
        "breakout": [["field", cust_name, {"source-field": cust_fk}]],
        "filter": [">", ["field", ordered_at, None], "2026-02-01"]}})
    check("FK join + date filter completes", r.get("status") == "completed", json.dumps(r)[:300])
    counts = dict(r["data"]["rows"])
    check("Ada has 2 orders after Feb 1", counts.get("Ada Lovelace") == 2, str(counts))

    # native query with template-tag parameter
    r = run_dataset({
        "database": db_id, "type": "native",
        "native": {"query": "SELECT name FROM customers WHERE is_active = {{active}} ORDER BY id",
                   "template-tags": {"active": {"id": "t1", "name": "active", "display-name": "Active",
                                                "type": "number"}}},
        "parameters": [{"type": "number", "target": ["variable", ["template-tag", "active"]], "value": 1}]})
    check("native param query completes", r.get("status") == "completed", json.dumps(r)[:300])
    names = [row[0] for row in r["data"].get("rows", [])]
    check("native param filters rows", names == ["Ada Lovelace", "Alan Turing", "Edsger Dijkstra"], str(names))

    # SQL error surfaces as a clean failure
    r = run_dataset({"database": db_id, "type": "native", "native": {"query": "SELECT nope FROM nothing"}})
    check("SQL error surfaces", r.get("status") == "failed" and "syntax error" in str(r.get("error", "")),
          str(r.get("error"))[:200])


def test_bad_token():
    bad = dict(DETAILS, **{"api-token": "not-a-real-token"})
    r = api("POST", "/api/database", {"engine": "d1", "name": "D1 Bad", "details": bad}, expect_error=True)
    check("bad token rejected", "id" not in r, json.dumps(r)[:200])


def main():
    print(f"waiting for Metabase at {MB_URL} ...")
    wait_for_metabase()
    setup_admin()
    db_id = add_database()
    wait_for_sync(db_id)
    tables, fields = get_metadata(db_id)
    assert_schema(tables, fields)
    test_queries(db_id, tables, fields)
    test_bad_token()
    print()
    if FAILURES:
        print(f"{len(FAILURES)} FAILURE(S):")
        for f in FAILURES:
            print(f"  - {f}")
        sys.exit(1)
    print("ALL CHECKS PASSED")


if __name__ == "__main__":
    main()
