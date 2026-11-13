# MCP Service

The MCP service exposes the OpenTelemetry Astronomy Shop demo's tools over the
 [Model Context Protocol](https://modelcontextprotocol.io/). It runs an MCP server
 that lets the `agent` service (or any other MCP-compatible client) discover and
 invoke shop operations such as listing products, managing carts, checking out.... etc

It is a Spring Boot 4.1 / Java 21 application using the Spring AI MCP server
starter (`spring-ai-starter-mcp-server-webmvc`). It replaces the former Python
(FastMCP / Traceloop) service with the same tools, schemas and results. See
[PORTING-NOTES.md](PORTING-NOTES.md) for the behaviour mapping and the points
that differ.

## Overview

- Runtime: Java 21, Spring Boot 4.1
- MCP framework: Spring AI MCP server (MCP Java SDK), synchronous server
- Transport: streamable HTTP at `/mcp`
- Observability: OpenTelemetry Spring Boot starter (no Java agent), Traceloop style spans for
  tool calls, outbound HTTP calls to the frontend are traced automatically
- Default port: `8011`

## Exposed MCP Tools

The MCP server registers the Astronomy Shop tools. Each tool calls the frontend HTTP
API through `APPLICATION_ENDPOINT`. Names, descriptions and input schemas are the
ones FastMCP derived from the Python functions.

| Tool | Description |
| --- | --- |
| `add_to_cart(user_id, product_id, quantity=1)` | Adds an item to a user's cart. |
| `checkout(checkout_person)` | Performs checkout for a user's cart. |
| `empty_cart(user_id)` | Empties a user's cart. |
| `get_ads(category)` | Fetches promotional ads for a category. |
| `get_cart(user_id)` | Retrieves a user's cart. |
| `get_product(product_id)` | Gets details for a specific product. |
| `get_recommendations(product_id)` | Returns product recommendations. |
| `get_shipping_quote(items, currency_code, address)` | Returns a shipping quote. |
| `get_supported_currencies()` | Lists supported currencies. |
| `list_products()` | Lists available products. |

Tool results are returned as one text content block (the JSON of the frontend's answer, or an
error text such as `Error fetching ads: ...`); object results also carry `structuredContent`.

MCP clients connect to the streamable HTTP endpoint:

```text
http://${MCP_ENDPOINT}:${MCP_PORT}/mcp
```

## Configuration

The service is configured with environment variables (`src/main/resources/application.yaml`).
Values can be supplied through Docker Compose, `.env`, `.env.override`, or the local shell environment.

| Variable | Default | Description |
| --- | --- | --- |
| `MCP_PORT` | `8011` | Port used by the MCP HTTP server. |
| `MCP_ENDPOINT` | `mcp` in Compose | Service hostname used by other demo services (e.g. `agent`). |
| `APPLICATION_ENDPOINT` | `localhost:8080`, `frontend:8080` in Compose | Frontend/API endpoint used by the shop tools. |
| `TRACELOOP_BASE_URL` | unset | OTLP/HTTP endpoint of the collector (used when `OTEL_EXPORTER_OTLP_ENDPOINT` is not set). |
| `TRACELOOP_TRACE_CONTENT` | `true` | Record tool arguments and results on spans. |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `TRACELOOP_BASE_URL`, else `http://localhost:4318` | OTLP endpoint (OTLP/HTTP, protobuf). |
| `OTEL_RESOURCE_ATTRIBUTES` | inherited | Additional OpenTelemetry resource attributes (Compose adds `service.criticality=low`). |
| `OTEL_SERVICE_NAME` | `mcp` | Service name used in telemetry. |

## Docker Compose Configuration

In `compose.agent.yaml`, the service is named `mcp` and is built from `src/mcp/Dockerfile`.
The `agent` service enables MCP usage via:

```text
MCP_ENABLED=True
MCP_ENDPOINT=mcp
MCP_PORT=8011
```

When `MCP_ENABLED=True` on the agent side, the agent loads its tools from this MCP service
instead of using its own built-in tools.

## Observability

Every `tools/call` produces a `mcp.server` span (`traceloop.span.kind=server`) with a nested
`<tool>.tool` span (`traceloop.span.kind=tool`, `traceloop.entity.name`, `traceloop.entity.input`
and `traceloop.entity.output` with the arguments / result). The `demo.tool.calls` counter
(`gen_ai.tool.name` attribute) counts the calls. Outbound calls to the frontend are client spans.

## Local Development

Requires JDK 21 or newer:

```sh
cd src/mcp
./gradlew bootJar test
MCP_PORT=8011 APPLICATION_ENDPOINT=localhost:8080 java -jar build/libs/mcp.jar
```

The MCP endpoint will be available at `http://localhost:8011/mcp`.

## Docker Build and Run

From the repository root:

```sh
docker compose build mcp
docker compose up mcp
```

## Testing the Endpoint

You can verify the MCP server is reachable with any MCP client. A quick smoke test using
`curl` against the streamable HTTP transport:

```sh
curl -i http://localhost:8011/mcp
```

A 4xx response indicates the server is up; full interaction requires a proper MCP client
(such as the `agent` service with `MCP_ENABLED=True`).

## File Layout

```text
src/mcp/
|-- Dockerfile, build.gradle, settings.gradle, gradlew*, gradle/
`-- src/main/java/oteldemo/mcp/
    |-- McpApplication.java
    |-- McpServerConfiguration.java   # registers the tools with the Spring AI MCP server
    |-- TracedToolRunner.java         # tools/call execution + Traceloop style spans
    `-- ShopTools.java, ToolArguments.java, PyJson.java   # the ten shop tools
```
