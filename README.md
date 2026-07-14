# Metabase Cloudflare D1 Driver

A community [Metabase](https://www.metabase.com) driver plugin that connects to [Cloudflare D1](https://developers.cloudflare.com/d1/) databases over Cloudflare's HTTP REST API.

D1 is SQLite under the hood but has no JDBC driver, so this plugin pairs Metabase's SQLite SQL dialect with an HTTP execution and sync layer built on D1's [`/raw` query endpoint](https://developers.cloudflare.com/api/resources/d1/subresources/database/methods/raw/). No Workers, proxies, or extra infrastructure required — just an API token.

## Installation

1. Download `d1.metabase-driver.jar` (or build it yourself, below).
2. Copy it into the `plugins/` directory of your Metabase instance.
3. Restart Metabase. **Cloudflare D1** now appears in *Admin → Databases → Add database*.

## Connection settings

| Setting | Where to find it |
|---|---|
| Cloudflare Account ID | Cloudflare dashboard sidebar, or `wrangler whoami` |
| D1 Database ID | *Storage & Databases → D1* in the dashboard, or `wrangler d1 info <name>` |
| API Token | Create one at *dashboard → My Profile → API Tokens → Create Token* with the **Account → D1 → Read** permission |

## Building from source

The plugin is built with Metabase's own driver build tooling. You need the [Clojure CLI](https://clojure.org/guides/install_clojure) and a JDK.

```sh
# 1. Clone Metabase at the version you're targeting
git clone --depth 1 --branch v0.62.4 https://github.com/metabase/metabase.git

# 2. Link this driver into the checkout and register it
ln -s /path/to/metabase_d1_driver metabase/modules/drivers/d1
# then add `metabase/d1 {:local/root "d1"}` to the :deps map in metabase/modules/drivers/deps.edn

# 3. Build
cd metabase
./bin/build-driver.sh d1

# The JAR lands in resources/modules/d1.metabase-driver.jar
```

## How it works

- **Driver registration:** `:d1` registers with parent `:sql` (the non-JDBC path used by drivers like BigQuery), inheriting Metabase's MBQL→SQL compiler.
- **SQL dialect:** the SQLite dialect (`strftime` date bucketing, unix-epoch handling, boolean-as-integer, etc.) is ported from Metabase's SQLite driver in `metabase.driver.d1.query-processor`.
- **Sync:** table discovery via `sqlite_master`, columns via `PRAGMA table_info`, foreign keys via `PRAGMA foreign_key_list` — all executed over the REST API. D1's internal `_cf_*` tables are excluded.
- **Execution:** compiled SQL and parameters are POSTed to the `/raw` endpoint, which returns column names and row arrays separately (correct ordering, no column-name collisions, column metadata even for empty results).

## Limitations

- **Analytics/read focus.** SELECT queries and sync introspection are the supported path; Metabase actions/uploads are disabled.
- **One HTTP round-trip per query.** Latency is a Cloudflare API call, and results are subject to D1's per-request response-size limits — very large unaggregated result sets may fail. Cloudflare API rate limits apply.
- **SQLite temporal semantics.** D1/SQLite has no native date type; dates are stored as ISO strings or epoch numbers. The driver parses temporal columns back into proper date/time values based on column metadata.

## License

MIT
