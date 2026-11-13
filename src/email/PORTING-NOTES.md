# Email service: porting notes (Ruby/Sinatra to Java 21 / Spring Boot 4.1)

Original: `opentelemetry-demo/src/email` (`email_server.rb`, `views/confirmation.erb`, gems Sinatra 4.2.1, Pony, OpenFeature
flagd provider). The original had no tests and **no health endpoint** (compose uses a TCP socket check), so none was added.

## Behavior mapping

| Original (Ruby) | Java |
|---|---|
| `post "/send_order_confirmation"` | `EmailController` `@PostMapping("/send_order_confirmation")`, body read as raw bytes and parsed with Jackson (no content-type requirement, like Sinatra) |
| `set :port, ENV["EMAIL_PORT"]` | `server.port: ${EMAIL_PORT:6060}` (the original had no default; 6060 is the Dockerfile/.env value) |
| Response of the route (`nil` from `puts`) | `200`, empty body, `Content-Type: text/html;charset=utf-8` |
| `JSON.parse(..., object_class: OpenStruct)`, then `data.order.order_id` | JSON tree; missing/non-object `order` or invalid/empty JSON or trailing garbage gives `500` before anything is counted |
| Auto-instrumented Sinatra server span + `demo.order.id` | OTel Spring Boot starter's Spring MVC server span (`POST /send_order_confirmation`) + `demo.order.id` set from `order.order_id` |
| Counter `demo.notification.confirmations` (unit `1`, description kept), `add(1)` before sending | same (meter `email`); counted even if the send then fails, as in the original |
| `send_email` manual span (tracer `email`) with `demo.order.id` | same; exceptions recorded and status ERROR like Ruby's `in_span` |
| Flag `emailMemoryLeak` (number, default 0) | `getDoubleValue("emailMemoryLeak", 0.0)`; body padded with `max(0, len*(m-1))` spaces |
| ERB `erb(:confirmation)` | the same `confirmation.erb` file, rendered by `ConfirmationTemplate` (subset interpreter: `<%= a.b %>` and the `items.each` loop), line endings normalized to LF; output byte for byte (see `ConfirmationTemplateTests`) |
| `Pony.mail(via: :test)`: to=`data.email`, from `noreply@example.com`, subject `Your confirmation email` | `MailOutbox` (stand-in for `Mail::TestMailer.deliveries`); same values |
| `deliveries.clear` when multiplier `< 1` | `MailOutbox.clear()` under the same condition; with the flag on, emails accumulate (the intentional leak) |
| OTel logger `on_emit` `email.confirmation_sent`, INFO, body `Order confirmation email sent`, attr `demo.order.id` | OTel Logs API record with the same event name, severity, body, attribute |
| `puts "Order confirmation email sent for order #{id}"` | slf4j INFO with the same text |
| `error do ... record_exception end` | `@ExceptionHandler(Exception)` records the exception on the current (server) span; `500` |
| Unknown route / wrong verb | `404`, body `<h1>Not Found</h1>`, text/html |
| Metrics/logs/trace OTLP export | OTel Spring Boot starter; `OTEL_*` variables as before |
| flagd (`FLAGD_HOST`, `FLAGD_PORT`, `FLAGD_TLS`) | flagd Java provider reads the same variables itself (same wiring as recommendation, plus the OTel `TracesHook`) |

## Things worth knowing about the original that were kept

* The template reads `shipping_address.street_address_1` and `street_address_2`, but `Address` in `demo.proto` has only
  `street_address`, so the street part always rendered empty (`, , City, Country Zip`). Kept; the test asserts it.
* Money prints as `<units>.<nanos>` with the raw nanos integer (e.g. `5.990000000 USD`; 0.05 USD would print as `0.50000000`).
  Fields Go omits when zero (`omitempty`) print as empty text, as `nil` did in ERB. A missing *object* (e.g. `shipping_cost`
  or `items`) raised `NoMethodError` in Ruby; here it raises `TemplateRenderException`; both give a `500` and a failed
  `send_email` span.
* HTML is not escaped (Sinatra's `erb` does not escape).
* A request without `email` failed in Ruby (Mail's test mailer rejects a blank To). Reproduced as `500` via `MailOutbox`.

## Deviations / uncertain points

* **Body of the 500 response:** I reasoned that Sinatra answered an empty body (its `error` block returned the span, which is
  not a body) and implemented that; the real Sinatra/Ruby behavior was not run. Status `500` is certain; checkout only checks
  for `200`.
* **Error message texts** (`NoMethodError`/`ArgumentError` wording, span status `Unhandled exception of type: <Class>`) are
  Java class names/approximations, not Ruby's.
* Ruby `Float#to_s` formatting for non-integer JSON numbers is approximated (checkout sends only integers).
* `Mail::TestMailer` address validation is only approximated by a blank check on To/From (malformed addresses that Ruby's
  `mail` gem would reject are accepted here).
* Heap: `JAVA_TOOL_OPTIONS=-Xmx300m -XX:+ExitOnOutOfMemoryError` in the image. With `emailMemoryLeak` on, the JVM exits on
  OOM and compose restarts it (`restart: unless-stopped`), analogous to the Ruby container being OOM-killed, but the timing
  and numbers differ.
* The Ruby `puts` went to stdout only; the Java line is a normal Spring log line (with the default Spring console pattern).

## Not verified

* The Docker image was **not built** (no Docker here). The `Dockerfile` follows the recommendation pattern with
  `eclipse-temurin:21-jdk`/`21-jre`, no digest pins (could not be looked up offline).
* Not run against real checkout, flagd, or the collector. The jar was started locally once (exporters disabled) and exercised
  with `curl` (200 / 404 / 500); the flagd provider wiring is not exercised against a real flagd (tests use the OpenFeature
  in-memory provider and mocks).
* The compose healthcheck for email runs `ruby`, which is not in the Java image.
* The ERB output was derived by reasoning about Tilt's ERB trim mode (`<>`, which only trims lines that begin with a tag at
  column 0; none do), not by running Ruby. If Ruby actually trimmed the indented loop lines, the Java output would differ
  by whitespace only.
