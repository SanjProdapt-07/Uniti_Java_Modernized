# MCP service: porting notes (Python FastMCP -> Java 21 / Spring Boot 4.1)

Original: `opentelemetry-demo/src/mcp` (`run.py`, `mcp_server/astronomy_shop_mcp_server.py`) plus the
shared tools `opentelemetry-demo/src/shared/tools.py`. Target: Spring Boot 4.1.2, Spring AI 2.0.2
(`spring-ai-starter-mcp-server-webmvc`, MCP Java SDK 2.1.0).

Spring AI release line: Spring AI 2.0.x is the line built against Spring Boot 4.1 (the 2.0.2 starters
depend on `spring-boot-starter` 4.1.2; verified on Maven Central). 2.1.x (still RC) moves to Boot 4.2 and was not used.

## Behaviour mapping

| Original (Python) | Java |
| --- | --- |
| `FastMCP("astronomy-shop-mcp")`, streamable HTTP on `0.0.0.0:${MCP_PORT:-8011}` at `/mcp` | Spring AI MCP server, `spring.ai.mcp.server.name=astronomy-shop-mcp`, protocol `STREAMABLE`, endpoint `/mcp`, `server.port=${MCP_PORT:8011}`, `server.address=0.0.0.0` |
| Ten tools registered in `_register_tools` order | `ShopTools` registers the same ten, same order, via `SyncToolSpecification` beans |
| Tool descriptions = docstrings (cleaned), input schemas derived from the Python signatures | Reproduced by hand; test `toolListMatchesFastMcpOutput` compares against the real FastMCP 3.4.10 `tools/list` output (captured in `src/test/resources/fastmcp-tools.json`: names, descriptions, property schemas, `required`, `additionalProperties:false`) |
| Tool results: text content = compact JSON of the frontend body (pydantic `to_json`), `structuredContent` for dict results only, plain strings returned as is | Same (text = compact Jackson JSON; `structuredContent` only for JSON objects / error-string results have none) |
| HTTP calls to `http://${APPLICATION_ENDPOINT:-localhost:8080}/api/...` with `httpx` (10 s timeouts, no redirects), `raise_for_status`, errors returned as `"Error ...: <str(e)>"` strings | Spring `RestClient` over the JDK HttpClient (10 s connect/read timeouts, no redirects); error strings use the same prefixes; HTTP status errors reproduce httpx's `Client error '404 Not Found' for url '...'\nFor more information check: ...` text; connection failures map to `All connection attempts failed` |
| `add_to_cart` payload, `empty_cart` 204 handling, `checkout` failure explanation, `get_shipping_quote` item normalisation (`json.dumps` for `itemList` / `address`) | Ported 1:1 (`ShopTools`), Python `json.dumps` formatting reproduced by `PyJson` |
| pydantic argument validation (`user_id: str`, `quantity: int = 1`, untyped params accept anything; failures are tool errors) | `ToolArguments` (SDK-side JSON-schema validation is switched off so coercion such as `"3"` -> `3` and the pydantic style error text are preserved); invalid arguments give `isError=true` |
| Uncaught exception in a tool -> FastMCP `Error calling tool '<name>': <msg>` (`isError`) | Same text, `isError=true` |
| `demo.tool.calls` counter (`{call}`, attribute `gen_ai.tool.name`), incremented when the tool function runs (after validation) | Same name/unit/description/attribute, same point of increment |
| Traceloop + FastMCP instrumentation: `mcp.server` span (kind attr `server`, `traceloop.workflow.name=astronomy-shop-mcp.mcp`, `mcp.response.value`) containing `<tool>.tool` span (`traceloop.span.kind=tool`, `traceloop.entity.name`, `traceloop.entity.input` = `{"tool_name":..,"arguments":..}`, `traceloop.entity.output` = `[{"type": "text", "content": ...}]`) | `TracedToolRunner` creates the same spans / attributes (gated by `TRACELOOP_TRACE_CONTENT`, default true) |
| `HTTPXClientInstrumentor` (client spans for the frontend calls) | OpenTelemetry Spring Boot starter instruments the `RestClient` (client spans below the tool span) |
| Traceloop OTLP export to `TRACELOOP_BASE_URL` | `otel.exporter.otlp.endpoint = ${OTEL_EXPORTER_OTLP_ENDPOINT:${TRACELOOP_BASE_URL:http://localhost:4318}}` (OTLP/HTTP, which is what the collector's port 4318 speaks) |
| `OTEL_SERVICE_NAME` (compose: `mcp`), `OTEL_RESOURCE_ATTRIBUTES`, `OTEL_EXPORTER_OTLP_INSECURE` | Handled by the OpenTelemetry starter (`OTEL_*` names unchanged) |
| Logging (`logging.basicConfig(INFO)`, "Starting FastMCP Server on 0.0.0.0:8011") | Spring Boot logging (Tomcat start line instead of the FastMCP line) |

## Deviations (not byte-identical)

- Tool results of FastMCP also carry `_meta.fastmcp.tags` on the tool *list*; the Java tools do not.
- Error texts that embed Python exception strings (`httpx`/`anyio`/`json`) are approximations: the common cases
  (HTTP status errors, connection refused/DNS failure, empty/invalid JSON body) are reproduced, other transport
  errors have Java wording.
- `shop` calls are encoded with `%20` (not `+`) for spaces in query values; the frontend decodes both.
- The `shared/tools.py` module was Docker-copied into both the agent and the MCP image; here each Gradle project has
  its own copy of the tools (`ShopTools`, `ToolArguments`, `PyJson` are identical in `src/agent` and `src/mcp`).
- No `/mcp` GET stream keep-alive tuning; transport behaviour is the Spring AI / MCP SDK default for streamable HTTP
  (stateful sessions with `Mcp-Session-Id`, like FastMCP's default).
- Span names/attributes follow the Python instrumentation as read from `opentelemetry-instrumentation-mcp` 0.63.0;
  the server-side JSON-RPC (`tools/list`, `initialize`) wrapper spans of that package were not recreated.

## Tests

`./gradlew test` (see the helper script): `McpServerIntegrationTests` (8 tests, real MCP client over streamable HTTP
against the running server and a fake frontend: server name, tool list vs FastMCP, text / structured results,
HTTP error text, invalid arguments, spans, metric), `ShopToolsTests` (17), `PyJsonTests` (5).

## Not verified

- Docker image build (no Docker here); the Dockerfile follows the recommendation service pattern.
- Behaviour against the real frontend / full compose stack, and against the real `agent` service in MCP mode
  (the agent's MCP client was tested against a stub server and the Java MCP server was tested with the Java
  MCP client; the two were not run against each other, and the Python `mcp` client was not exercised).
- Exact span output was compared with the instrumentation source code, not with a recorded trace of the Python service.
