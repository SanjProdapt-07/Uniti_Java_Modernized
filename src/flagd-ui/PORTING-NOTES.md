# Porting notes: flagd-ui (Elixir/Phoenix LiveView to Spring Boot 4.1)

Original: `opentelemetry-demo/src/flagd-ui` (Elixir 1.20, Phoenix 1.8, LiveView, Bandit).
Target: Java 21, Spring Boot 4.1.2, Spring Web MVC, Thymeleaf, plain JavaScript (fetch), Gradle Groovy DSL.
Package: `oteldemo.flagdui`.

## Behavior mapping

| Original | Java |
|---|---|
| `FlagdUi.Storage` GenServer (read file at start, `:read`, `{:replace, json}`, `{:write, flag, variant}`) | `Storage` (synchronized singleton): same init rules (missing/empty/invalid file gives `{}`), `replace` ignores invalid JSON and keeps state and file, `write` uses the 3-element `targeting.if` rule or `defaultVariant` (only if the key exists, like `Map.replace`) and writes `Jason`-style pretty JSON |
| Atomic write (`path.tmp` then `File.rename!`) | `path.tmp` then `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` (non-atomic fallback if the file system cannot) |
| Jason encode/decode (key-sorted maps, 2-space pretty format, compact for API writes) | `FlagJson` (own encoder for byte-compatible pretty/compact output, Jackson 3 for strict parsing) |
| `FlagdUi.Scheduler` GenServer | `Scheduler` (synchronized, one daemon timer thread, epoch guard against stale timers): same validation messages and order, same selection/variant/plan logic, same randomness consumption order, 10-entry history, revert on stop/restart/shutdown, same log messages |
| `/api/read`, `/api/read-file` (GET) | `FeatureController`, body `{"flags": ...}`; 500 `{"errors":{"detail":"Internal Server Error"}}` if the state has no `flags` (Phoenix MatchError) |
| `/api/write`, `/api/write-to-file` (POST `{"data": ...}`) | same; data is re-encoded compactly and replaces the file; returns `{}`; missing `data` or unparsable body gives 400 `{"errors":{"detail":"Bad Request"}}` |
| LiveView `/` (Basic), `/advanced`, `/scheduler` | Thymeleaf pages with the same markup, text and Tailwind/daisyUI classes; routes under `/feature/` via Envoy exactly as before |
| LiveView events (`flag_changed`, `edit`/`save`, scheduler `start`/`stop`/`preset`/`select_all`/`select_none`/`toggle_flag`) | `app.js` + `/ui/*` endpoints (`/ui/flags`, `/ui/advanced/save`, `/ui/scheduler/start|stop|status`, `/ui/flags/version|grid`); presets, All/None, toggle all are done client side |
| Flash toasts ("Saved: x", "Saved!", "Invalid JSON", "Scheduler started/stopped") | same texts, same toast markup, stay until clicked or replaced |
| Envoy `/feature` with `prefix_rewrite "/"` | `DuplicateSlashFilter` collapses the `//api/read` style paths that the rewrite produces (Phoenix ignored empty segments, Spring MVC does not) |
| `url: [path: "/feature"]` link prefix | `flagd-ui.root-path` (`FLAGD_UI_ROOT_PATH`, default `/feature`): links, asset URLs and the `root-path` meta tag |
| `FLAGD_UI_PORT` (default 4000) | `server.port: ${FLAGD_UI_PORT:4000}` |
| Hard-coded `/app/data/demo.flagd.json` (prod) | `FLAGD_UI_STORAGE_FILE_PATH`, default `/app/data/demo.flagd.json` (new variable; compose mount unchanged) |
| OpenTelemetry (`opentelemetry_phoenix`, `opentelemetry_bandit`, OTLP http/protobuf exporter) | `opentelemetry-spring-boot-starter` + `opentelemetry-spring-webmvc-6.0`: SERVER spans named `METHOD /route`; `OTEL_*` variables work; `otel.exporter.otlp.protocol` defaults to `http/protobuf` (compose gives the collector's HTTP port) |
| Compiled Tailwind 4 + daisyUI CSS, favicon, robots.txt | CSS rebuilt from the original sources (see below), favicon/robots copied |

Tests: all original ExUnit tests were ported (`StorageTest`, `SchedulerTest`, `FeatureControllerTest`,
`ErrorPagesTest` for ErrorHTML/ErrorJSON, `PagesTest` for PageController/Navbar, `SchedulerPageTest` for the LiveView
scheduler tests) plus new tests (see the final counts in the report). 95 tests passed with the build helper
(`bootJar test`).

## Deviations

* **No websocket / LiveView push.** The `/live` socket is gone. The Basic page polls `/ui/flags/version` every 2 s
  (and swaps the card grid when the file changed, unless a select has focus); the scheduler page polls an HTML fragment
  every 1 s, which also replaces the 1 s `:tick`. The scheduler page does not refresh its list of flags while open when
  the file changes (LiveView did on `flags_changed`).
* **Seeds are reproducible within this service but not identical to Erlang's `:exsss` sequence** (`java.util.Random`
  is used), so the same seed picks different flags than the Elixir version did. Generated seeds are random positive
  ints. Seeds are limited to the `long` range, durations to what fits a `long` of milliseconds.
* **Scheduler writes to storage synchronously** (GenServer cast before); a failed write is logged and does not stop the
  scheduler.
* **Form state is client side.** LiveView kept the scheduler form state on the server per session; here the DOM holds
  it. A page reload shows the scheduler's last configuration (as before) rather than unsaved edits.
* **Basic page tolerates a configuration without `flags`** (renders no cards); the Phoenix LiveView crashed on a match
  error. `/api/read` keeps the 500.
* **Numbers**: floats are printed shortest round trip; for very large/small magnitudes the exponent format may differ
  from Erlang's (`1.0e-5` style is mimicked, not exhaustively checked). The demo file has only simple values.
* **Logs**: same messages ("Read new state from file", "Wrote new state to file", "Ignoring replace with invalid
  JSON", the scheduler lines) but Spring Boot's log format instead of the Elixir `$time $metadata[$level]` format.
* **Telemetry**: no LiveView spans or `telemetry_metrics`; HTTP server spans come from the Spring starter. The starter
  also exports metrics (JVM/HTTP) to the same endpoint, which the Elixir service did not.
* **No CSRF token / session cookie.** Phoenix `protect_from_forgery` and the signed session cookie were part of the
  LiveView plumbing; the `/api` endpoints never used them and the new `/ui` endpoints are as unauthenticated as
  `/api/write`.
* **Dev-only routes** (`/dev/dashboard` LiveDashboard, Swoosh mailbox), gettext, the mailer and DNS cluster were
  Phoenix scaffolding (dev routes only compiled in dev) and have no Java counterpart.
* **Compiled CSS**: rebuilt with the Tailwind CLI 4.1.18 from npm (the original image used 4.0.9 through the Hex
  `tailwind` package), after a build-only shim so the CLI could load the vendored daisyUI bundle. The source
  (`assets/css/app.css`, vendored plugins) is unchanged apart from the `@source` paths and dropping the three unused
  `phx-*-loading` variants and the `[data-phx-root-id]` rule. Class names and markup are carried over, but the output
  was not compared pixel by pixel with the old compiled CSS.
* **Removed**: topbar.js (the LiveView page-loading bar), the theme toggle component (`Layouts.app`/`theme_toggle`
  was never rendered by any page). The root layout's theme-persistence script is kept.
* **Container**: runtime image is Temurin 21 JRE instead of Debian + Elixir release; the JVM is capped through
  `JAVA_TOOL_OPTIONS` to try to stay near compose's 200M limit (see unverified items).

## Not verified

* **Docker image build**: no Docker here. The Dockerfile follows the recommendation pattern but was never built; it
  runs `gradlew test bootJar` in the builder stage with `src/flagd/demo.flagd.json` copied to `../flagd`.
* **Memory**: whether the JVM fits compose's 200M limit for `flagd-ui` was not measured (a local run only used
  `-Xmx100m`). A higher limit (e.g. 300M) is requested.
* **Behind Envoy**: the `//path` handling was tested with a real HTTP client against a local port, not through Envoy.
  The browser flow was exercised with jsdom against a locally running jar (dashboard change/polling, advanced save,
  scheduler start/stop/presets/toggles), not in a real browser. Visual appearance was not inspected.
* **Shared file with flagd**: reading/rewriting `demo.flagd.json` was tested on a temp copy only, never with flagd
  watching the mount. A local run showed the pretty output differing from the repo file only by the changed value
  and line endings (the checked-out file here has CRLF; new writes use LF and no trailing newline, like Jason).
* **OTLP export**: spans were seen through the logging exporter only; nothing was sent to a real collector.
* Exact Jason/Erlang output for exotic JSON values (huge floats, unusual unicode) was not compared.
