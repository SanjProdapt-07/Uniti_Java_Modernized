# Chatbot service: porting notes (Python Gradio -> Java 21 / Spring Boot 4.1)

Original: `opentelemetry-demo/src/chatbot` (`run.py`, `src/chat_interface/chat_interface.py`, `src/opamp.py`). Target: Spring Boot 4.1.2
(Web MVC + Thymeleaf + static JS), OpenTelemetry Spring Boot starter. No Spring AI is needed: the chatbot only calls the agent's HTTP API.

## Behaviour mapping

| Original (Gradio) | Java |
| --- | --- |
| `gr.Blocks(title="Astronomy Shop agent")`: `# Astronomy Shop agent` heading, description markdown "Ask me about the astronomy shop application, I will ask for more information if needed.", `gr.Chatbot(height="70vh")`, `gr.Textbox(placeholder="Type a message...", show_label=False, autofocus=True)` | `templates/index.html` + `static/chat.css`: same title, heading, description, 70vh chat window, text box with the same placeholder and autofocus |
| "Sample questions:" label and a row of three small buttons (the three default requests); a click calls `respond_fresh`: new conversation with empty history | Same label/buttons/texts; a click clears the conversation and sends the question with `history: []` |
| `textbox.submit(respond)`: blank (after `strip()`) message is ignored; otherwise history + user + assistant message are appended and the box is cleared | `chat.js`: Enter submits; blank ignored (and `POST /api/chat` with a blank message returns `{"reply": null}` without calling the agent or counting); box is cleared immediately (Gradio cleared it when the answer arrived) and the user message is shown immediately with a "Thinking..." placeholder |
| Conversation history kept per browser session and sent as `[{role, content}]` | Kept in the page (lost on reload, like a Gradio session), sent as `[{role, content}]` with each request |
| Answer rendering: Gradio Chatbot renders Markdown | Small built-in Markdown renderer (escapes HTML first; headings, lists, code, bold/italic, links) |
| `chat_with_agent`: `POST http://AGENT_ENDPOINT:AGENT_PORT/prompt` with `{"message", "history"}` (no `session_id`), timeout `AGENT_CHAT_INTERFACE_TIMEOUT` (300 s), `raise_for_status`, answer = `response.json()["response"]["messages"][-1]["content"]`, defaults: `"Agent returned an empty message body."` when `content` is missing, `"Error: Received an unexpected response format from the agent."` for a missing/empty list; any exception logged (`Error : ...`) and returned as `"Error: <exception text>"` | `ChatService.chatWithAgent`: same request, defaults, messages and log lines; HTTP error text follows `requests` (`500 Server Error: Internal Server Error for url: ...`); connection/timeout texts approximate the `requests` messages |
| Log line `Sending request {payload} to Agent` (Python repr of the payload) | Same line (payload rendered like a Python dict) |
| `demo.chatbot.requests` counter (`{request}`, "Number of chat messages sent from the chatbot UI to the agent"), incremented once per message sent | Same name/unit/description; incremented at the start of `chatWithAgent` (blank messages are not counted) |
| Server: Gradio on `CHATBOT_ENDPOINT` (0.0.0.0) : `CHATBOT_PORT` (7860), `root_path=CHATBOT_ROOT_PATH` | `server.address=${CHATBOT_ENDPOINT:0.0.0.0}`, `server.port=${CHATBOT_PORT:7860}`; envoy rewrites `/chatbot/` to `/`, and the page only uses relative URLs, so `CHATBOT_ROOT_PATH` is not needed (accepted but unused) |
| OpenTelemetry: OTLP/HTTP traces + metrics (`OTEL_EXPORTER_OTLP_ENDPOINT`), `requests`/`httpx` instrumentation (client spans, trace context propagation to the agent) | OpenTelemetry Spring Boot starter (OTLP/HTTP is its default protocol), RestClient instrumentation: client span + `traceparent` towards the agent; also server spans for the page and API requests |
| OpAMP: `start_opamp_agent()` when `OPAMP_SERVER_ENDPOINT` is set (WebSocket transport, `HealthReportingOpAMPClient`: status+health+heartbeat capabilities, full state on connect, heartbeat every 30 s, retry/backoff, `AgentDisconnect` on stop, `OPAMP_SERVER_TLS_INSECURE_SKIP_VERIFY` boolean parsing with the same accepted values/error message) | `opamp` package: `OpampMessages` (hand-written protobuf encoder for `AgentToServer`: bytes verified against the Python protobuf serialization), `OpampWebSocketTransport` (JDK WebSocket, 1 byte zero header, TLS skip option), `OpampAgent` (same queue/retry/heartbeat/disconnect logic, `ReportFullState` handling), `OpampLifecycle` (starts/stops with the application) |

## Deviations (not byte-identical)

- The UI is a custom page, not Gradio: no Gradio-specific features (themes, share/API pages, copy/retry/undo buttons, file upload, the
  `/gradio_api` endpoints). Streaming was not used by the original, so none is implemented.
- Answers are rendered with a small Markdown subset rather than Gradio's full Markdown renderer.
- HTTP error/connection texts shown as `Error: ...` approximate the Python `requests` wording.
- OpAMP resource attributes come from the Java SDK resource (more attributes than the Python SDK default, e.g. `host.*`, `os.*`,
  `process.*`; list valued attributes are stringified); the User-Agent header is `OTel-OpAMP-Java/1.0`.
- Server spans are created for the page and API requests (Gradio was not instrumented).
- `CHATBOT_ROOT_PATH` has no effect (see above).

## Tests

`./gradlew test`: `ChatbotApplicationTests` (page content/relative URLs, static assets, relay of message+history to a fake agent, blank
messages, metric, error and odd-response texts) and `OpampTests` (wire bytes identical to the Python protobuf output for full-state,
heartbeat and disconnect; ServerToAgent parsing; agent connect/heartbeat/disconnect/retry/ReportFullState behaviour with a fake transport;
boolean parsing; URL scheme mapping). The browser script (`chat.js`) has no automated test: it was not executed in a browser.

## Not verified

- Docker image build (no Docker here).
- The chat page in a real browser (layout, Markdown rendering, Enter/button behaviour, behind envoy under `/chatbot/`).
- The OpAMP WebSocket transport against the real `opamp-server` (TLS skip, framing, server responses); only message bytes and the agent
  state machine are tested.
- Telemetry in the collector/Jaeger/Prometheus (`test/telemetry` agentic tests were not run).
