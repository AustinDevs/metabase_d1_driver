#!/usr/bin/env python3
"""Minimal emulator of the Cloudflare D1 REST API's /raw endpoint, backed by a local SQLite file.

Lets the e2e test exercise the Metabase driver without Cloudflare credentials. Implements exactly what the
driver uses: POST /accounts/{account_id}/d1/database/{database_id}/raw with bearer-token auth, returning
{"result": [{"results": {"columns": [...], "rows": [[...]]}, "success": true, "meta": {...}}], "success": true}.

Usage: d1_mock_server.py --port 8787 --db /path/to.sqlite --account-id A --database-id B --token T
"""
import argparse
import json
import sqlite3
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ARGS = None


def d1_error(code, message):
    return {"result": [], "success": False, "errors": [{"code": code, "message": message}], "messages": []}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("d1-mock: %s\n" % (fmt % args))

    def _respond(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        expected_path = f"/accounts/{ARGS.account_id}/d1/database/{ARGS.database_id}/raw"
        # tolerate a base-url prefix like /client/v4
        if not self.path.endswith(expected_path):
            return self._respond(404, d1_error(7404, f"no route for {self.path}"))
        if self.headers.get("Authorization") != f"Bearer {ARGS.token}":
            return self._respond(401, d1_error(10000, "Authentication error"))

        try:
            length = int(self.headers.get("Content-Length", 0))
            req = json.loads(self.rfile.read(length))
            sql, params = req.get("sql", ""), req.get("params") or []
        except Exception as e:
            return self._respond(400, d1_error(7400, f"bad request: {e}"))

        conn = sqlite3.connect(ARGS.db)
        try:
            cur = conn.execute(sql, params)
            columns = [d[0] for d in cur.description] if cur.description else []
            rows = [list(r) for r in cur.fetchall()]
            conn.commit()
        except sqlite3.Error as e:
            # mirror D1's shape for SQL errors: HTTP 200 wrapper is not used; D1 returns a non-2xx with errors
            return self._respond(400, d1_error(7500, f"{e}: SQLITE_ERROR"))
        finally:
            conn.close()

        self._respond(200, {
            "result": [{
                "results": {"columns": columns, "rows": rows},
                "success": True,
                "meta": {"served_by": "d1-mock", "duration": 0, "changes": 0, "last_row_id": 0,
                         "rows_read": len(rows), "rows_written": 0},
            }],
            "success": True,
            "errors": [],
            "messages": [],
        })


def main():
    global ARGS
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--db", required=True)
    parser.add_argument("--account-id", required=True)
    parser.add_argument("--database-id", required=True)
    parser.add_argument("--token", required=True)
    ARGS = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", ARGS.port), Handler)
    sys.stderr.write(f"d1-mock: serving {ARGS.db} on 127.0.0.1:{ARGS.port}\n")
    server.serve_forever()


if __name__ == "__main__":
    main()
