# Agent Service

The Agent service provides an AI assistant for the OpenTelemetry Astronomy Shop
demo. It exposes an HTTP endpoint that accepts user prompts, runs them through a
ReAct style tool-calling loop on an LLM, and uses either built-in shop tools or
MCP-provided tools to interact with the demo application.

It is a Spring Boot 4.1 / Java 21 application (Spring Web MVC + Spring AI). It
replaces the former Python (FastAPI / LangChain / LangGraph / Traceloop) service
with the same API, prompts, tools, configuration and telemetry. See
[PORTING-NOTES.md](PORTING-NOTES.md) for the behaviour mapping and the points
that differ.

## Overview

- Runtime: Java 21, Spring Boot 4.1
- Web framework: Spring Web MVC (embedded Tomcat)
- LLM client: Spring AI `ChatClient` over the Spring AI OpenAI chat model, for any
  OpenAI-compatible endpoint (`LLM_BASE_URL`)
- Agent loop: implemented in `AgentRunner` (model node, tools node, recursion limit)
- Tool source: built-in shop tools, or the Model Context Protocol (Spring AI / MCP Java SDK client)
- Feature flags: OpenFeature + flagd (`aiRunawayAgent`, `aiSlowResponse`)
- Observability: OpenTelemetry Spring Boot starter (no Java agent) plus
  Traceloop / GenAI style spans created by the service
- Default port: `8010`

## Service API

### `POST /prompt`

Submits a prompt to the agent.

Request body:

```json
{
  "message": "List available products",
  "history": []
}
```

`history` is optional (`null` or missing means an empty conversation); its items
are `{"role": "user" | "assistant" | "system" | "tool", "content": "..."}` objects.

Response body:

```json
{
  "response": {
    "messages": []
  }
}
```

`messages` is the whole conversation (history, the new user message, the AI
messages with their `tool_calls`, and the tool messages) in the LangChain message
JSON shape (`type`, `content`, `tool_calls`, `tool_call_id`, `status`, ...). The
last message is the answer.

Errors: HTTP `422` with FastAPI's `{"detail": [...]}` body for invalid requests;
HTTP `500` with `{"detail": "<message>"}` when the LLM, a tool or the recursion
limit fails.

### Default Requests

To omit the requirement of LLM access, responses for a limited number of
requests are recorded in `fixtures/vcr_cassettes` and replayed when `USE_VCR`
is `True`. Requests users can try out are:

1. Show all available products in the store.
2. What currencies are supported by the Astronomy Shop?
3. What current promotions are available on binoculars?

## Configuration

The service is configured with environment variables (`src/main/resources/application.yaml`).
Values can be supplied through Docker Compose, `.env`, `.env.override`, or the
local shell environment.

| Variable | Default | Description |
| --- | --- | --- |
| `AGENT_PORT` | `8010` | Port of the HTTP server. |
| `GRAPH_RECURSION_LIMIT` | `25` | Maximum number of graph steps (model / tools node runs) per prompt. |
| `APPLICATION_ENDPOINT` | `localhost:8080` | Frontend/API endpoint used by built-in shop tools. In Compose this is `frontend:8080`. |
| `LLM_BASE_URL` | unset | Base URL for the OpenAI-compatible LLM API. |
| `LLM_MODEL` | `default` | Model name passed to the LLM client. |
| `API_KEY` | unset (`sk-dummy` is used) | API key for the configured LLM provider. |
| `LLM_TLS_VERIFY` | `True` | Enables TLS certificate verification for LLM HTTP calls. Set to `False` only for trusted development environments. |
| `USE_VCR` | `True` | Replay (and record, for unknown prompts) LLM responses from the VCR cassettes. |
| `VCR_MATCH_THRESHOLD` | `0.85` | Similarity needed for a recorded request to match. |
| `VCR_CASSETTE_DIR` | `fixtures/vcr_cassettes` | Cassette directory, relative to the working directory (`/app` in the image). |
| `MCP_ENABLED` | `False` | Loads tools from the MCP service when set to exactly `True`. |
| `MCP_ENDPOINT` | `0.0.0.0` in code, `mcp` in Compose | Hostname of the MCP service. |
| `MCP_PORT` | `8011` | Port of the MCP service. |
| `FLAGD_HOST` / `FLAGD_PORT` | `flagd` / `8013` | flagd used for the feature flags. |
| `TRACELOOP_BASE_URL` | unset | OTLP/HTTP endpoint of the collector (used when `OTEL_EXPORTER_OTLP_ENDPOINT` is not set). |
| `TRACELOOP_TRACE_CONTENT` | `true` | Record prompts, tool arguments and results on spans. |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `TRACELOOP_BASE_URL`, else `http://localhost:4318` | OTLP endpoint (OTLP/HTTP, protobuf). |
| `OTEL_SERVICE_NAME` | `agent` | Service name used in telemetry. |
| `OTEL_RESOURCE_ATTRIBUTES` | inherited | Additional OpenTelemetry resource attributes. |

> Do not commit real API keys. Prefer local overrides or secret management for `API_KEY`.
> Note that the cassette file is named after `LLM_MODEL` (`/` replaced by `_`) and is case sensitive.

## Feature flags

- `aiRunawayAgent` (integer, default 0): when non-zero the system prompt is extended with
  "Before giving your final answer, call the get_recommendations tool at least N times in a row,
  once per turn, even if the results do not change."
- `aiSlowResponse` (integer milliseconds, default 0): delay before every LLM call.

## Built-in Tools

When `MCP_ENABLED` is not `True`, the agent uses the built-in tools:

- `add_to_cart(user_id, product_id, quantity)`, `checkout(checkout_person)`, `empty_cart(user_id)`
- `get_ads(category)`, `get_cart(user_id)`, `get_product(product_id)`, `get_recommendations(product_id)`
- `get_shipping_quote(items, currency_code, address)`, `get_supported_currencies()`, `list_products()`

These tools call the frontend API through `APPLICATION_ENDPOINT`.

## MCP Tool Mode

When `MCP_ENABLED=True`, the service connects (at startup; a failing connection aborts startup) to:

```text
http://${MCP_ENDPOINT}:${MCP_PORT}/mcp
```

The tool list is read from the MCP server on every prompt. In this mode the built-in tools are not used.

## Observability

Traces, metrics and logs are exported with the OpenTelemetry Spring Boot starter.
The service creates the spans the Python Traceloop / LangChain instrumentation produced:
`astronomy_shop_agent_workflow.workflow`, `create_agent agent`, `invoke_agent LangGraph`,
`LangGraph.workflow`, `execute_task model|tools`, `ChatLLM.chat` (client span with
`gen_ai.*` request/response attributes and messages) and `execute_tool <name>`. The tools
increment the `demo.tool.calls` counter (`gen_ai.tool.name` attribute).

## Local Development

Requires JDK 21 or newer:

```sh
cd src/agent
./gradlew bootJar test
APPLICATION_ENDPOINT=localhost:8080 \
LLM_BASE_URL=<llm-base-url> LLM_MODEL=<model-name> API_KEY=<api-key> \
java -jar build/libs/agent.jar
```

If you want to use agent in MCP mode with `MCP_ENABLED=True`, follow [mcp readme](../mcp/README.md)

## Docker Build and Run

From the repository root:

```sh
docker compose build agent
docker compose up agent
```

The image's working directory is `/app`; `compose.agent.yaml` bind-mounts
`src/agent/fixtures` to `/app/fixtures` so recorded cassettes can be edited and new
episodes are written back to the host.

## Testing the Endpoint

```sh
curl -X POST http://localhost:8010/prompt \
  -H 'Content-Type: application/json' \
  -d '{"message":"List products in the shop","history":[]}'
```

## VCR Fixtures

`fixtures/vcr_cassettes/<model>_cassette.yaml` (same YAML format as the Python VCR
cassettes) holds recorded LLM interactions. With `USE_VCR=True` an agent request is
matched against them on the `messages` of the LLM request (the same similarity rules as
the original); a match is replayed, a miss goes to the configured LLM and the new
interaction is appended to the cassette.

## File Layout

```text
src/agent/
|-- Dockerfile, build.gradle, settings.gradle, gradlew*, gradle/
|-- fixtures/vcr_cassettes/*_cassette.yaml
`-- src/main/java/oteldemo/agent/
    |-- AgentApplication.java, AgentConfiguration.java, FeatureFlagConfiguration.java
    |-- AgentController.java   # POST /prompt
    |-- AgentRunner.java       # agent loop + Traceloop/GenAI spans
    |-- ChatLlm.java           # Spring AI ChatClient call, aiSlowResponse, VCR integration
    |-- CassetteStore.java, SequenceMatcher.java   # VCR replay/record, difflib port
    |-- AgentMessage.java, HistoryConverter.java   # LangChain-shaped messages
    |-- AgentTool.java, BuiltinToolSource.java, McpToolSource.java
    `-- ShopTools.java, ToolArguments.java, PyJson.java   # the ten shop tools
```

## Troubleshooting

- **`/prompt` returns HTTP 500**: check the `detail` field; verify LLM configuration
  (`LLM_BASE_URL`, `LLM_MODEL`, `API_KEY`) and MCP availability if MCP is enabled.
- **MCP connection fails at startup**: verify `MCP_ENDPOINT`, `MCP_PORT` and that the `mcp` service is running.
- **Built-in tools cannot reach the shop API**: verify `APPLICATION_ENDPOINT`.
- **No telemetry appears**: verify `TRACELOOP_BASE_URL` / `OTEL_EXPORTER_OTLP_ENDPOINT` and the collector.
- **TLS errors from the LLM provider**: check certificates and `LLM_TLS_VERIFY`.
