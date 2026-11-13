# Chatbot Service

The Chatbot service provides a browser-based chat UI for the OpenTelemetry
Astronomy Shop demo. It serves a small Thymeleaf page with a JavaScript chat
window and forwards user messages to the Agent service.

It is a Spring Boot 4.1 / Java 21 application (Spring Web MVC + Thymeleaf +
static JS). It replaces the former Python / Gradio service with the same page
content and behaviour. See [PORTING-NOTES.md](PORTING-NOTES.md) for the
behaviour mapping and the points that differ.

## Overview

- Runtime: Java 21, Spring Boot 4.1
- UI: Thymeleaf page (`templates/index.html`) + `static/chat.js` / `static/chat.css`
- HTTP client: Spring `RestClient` (OpenTelemetry instrumented)
- Observability: OpenTelemetry Spring Boot starter (traces and metrics exported with OTLP/HTTP)
- OpAMP: a status/health reporting OpAMP client (WebSocket) when `OPAMP_SERVER_ENDPOINT` is set
- Default port: `7860` exposed outside docker via `http://localhost:8080/chatbot/`
- If chatbot service is running access chatbot UI from [Chatbot UI](http://localhost:8080/chatbot/)

## How It Works

1. A user submits a message in the chat UI (Enter in the text box, or a sample question button).
2. The page posts the message and the conversation so far to `POST api/chat` of the chatbot.
3. The chatbot sends a request to the Agent service at:

   ```text
   http://${AGENT_ENDPOINT}:${AGENT_PORT}/prompt
   ```

4. The Agent service returns a response object.
5. The chatbot returns the content of the final message of the response, and the page appends the
   user message and the (Markdown rendered) answer to the conversation. Problems are shown as
   `Error: ...` answers.

Request body sent to the Agent service:

```json
{
    "message": "List available products",
    "history": [<Past interactions with agent in the same conversation. Empty list for the first request>]
}
```

All URLs of the page are relative, so the UI works under the frontend proxy's `/chatbot/` prefix
(envoy rewrites `/chatbot/` to `/`).

## Default Requests

To omit the requirement of LLM access, the agent has responses for a limited number of requests.
Requests users can try out are shown as buttons below the text box; clicking one starts a new
conversation:

1. Show all available products in the store.
2. What currencies are supported by the Astronomy Shop?
3. What current promotions are available on binoculars?

## Configuration

The service is configured with environment variables (`src/main/resources/application.yaml`).
Values can be supplied through Docker Compose, `.env`, `.env.override`, or the local shell environment.

| Variable | Default | Description |
| --- | --- | --- |
| `CHATBOT_ENDPOINT` | `0.0.0.0` | Host/interface where the server binds. |
| `CHATBOT_HOST` | `chatbot` in `.env` | Hostname used by other services, such as the frontend proxy. |
| `CHATBOT_PORT` | `7860` | Port used by the server. |
| `CHATBOT_ROOT_PATH` | empty, `/chatbot` in `.env` | Accepted for compatibility; not needed because the page only uses relative URLs. |
| `AGENT_ENDPOINT` | `0.0.0.0` in code, `agent` in `.env` | Hostname of the Agent service. |
| `AGENT_PORT` | `8010` | Port of the Agent service. |
| `AGENT_CHAT_INTERFACE_TIMEOUT` | `300` | Timeout, in seconds, for calls from the chatbot to the Agent service. |
| `OPAMP_SERVER_ENDPOINT` | unset | OpAMP server URL; the OpAMP client only runs when set. |
| `OPAMP_SERVER_TLS_INSECURE_SKIP_VERIFY` | `false` | Skip TLS verification of the OpAMP server. |
| `OTEL_SERVICE_NAME` | `chatbot` | Service name used in telemetry. |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` | OTLP/HTTP endpoint. In Compose this points to the OpenTelemetry Collector. |
| `OTEL_RESOURCE_ATTRIBUTES` | inherited | Additional OpenTelemetry resource attributes. |

## Docker Compose Configuration

In `compose.agent.yaml`, the service is named `chatbot` and is built from `src/chatbot/Dockerfile`.
The frontend proxy receives `CHATBOT_HOST` and `CHATBOT_PORT`, so the chatbot can be exposed through the demo UI,
usually under `/chatbot`.

## Observability

Traces (inbound requests, outbound calls to the agent) and the `demo.chatbot.requests` counter
(`{request}`, one per message forwarded to the agent) are exported through the OpenTelemetry Spring Boot starter.

## Local Development

Requires JDK 21 or newer. Run the Agent service first, then start the chatbot:

```sh
cd src/chatbot
./gradlew bootJar test
CHATBOT_PORT=7860 AGENT_ENDPOINT=localhost AGENT_PORT=8010 java -jar build/libs/chatbot.jar
```

Open the UI at `http://localhost:7860`.

## Docker Build and Run

From the repository root:

```sh
docker compose build chatbot
docker compose up chatbot
```

## File Layout

```text
src/chatbot/
|-- Dockerfile, build.gradle, settings.gradle, gradlew*, gradle/
`-- src/main/
    |-- java/oteldemo/chatbot/
    |   |-- ChatbotApplication.java, ChatbotConfiguration.java
    |   |-- ChatbotController.java   # GET /, POST /api/chat
    |   |-- ChatService.java         # agent client + demo.chatbot.requests
    |   `-- opamp/                   # OpAMP client (protobuf over WebSocket)
    `-- resources/
        |-- application.yaml
        |-- templates/index.html
        `-- static/chat.js, chat.css
```

## Troubleshooting

- **The UI loads but responses fail**: Verify `AGENT_ENDPOINT`, `AGENT_PORT`, and that the Agent service is running.
- **Requests time out**: Increase `AGENT_CHAT_INTERFACE_TIMEOUT` or check the LLM/Agent backend configuration.
- **The UI does not load behind the proxy**: Verify `CHATBOT_HOST` and `CHATBOT_PORT` of the frontend proxy.
- **No telemetry appears**: Verify `OTEL_EXPORTER_OTLP_ENDPOINT`, collector availability, and `OTEL_SERVICE_NAME`.
- **Port conflicts locally**: Change `CHATBOT_PORT` or stop the process already using port `7860`.
