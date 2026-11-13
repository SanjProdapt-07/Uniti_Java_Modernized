# Agent service: porting notes (Python FastAPI/LangChain -> Java 21 / Spring Boot 4.1)

Original: `opentelemetry-demo/src/agent` (`run.py`, `src/agents/{agents,llm,feature_flags,mcp_client,patch_vcr}.py`) plus
`opentelemetry-demo/src/shared/tools.py`. Target: Spring Boot 4.1.2 (Web MVC), Spring AI 2.0.2
(`spring-ai-openai`, `spring-ai-client-chat`, `spring-ai-mcp`, MCP Java SDK 2.1.0), OpenFeature + flagd, OpenTelemetry Spring Boot starter.

Spring AI release line: Spring AI 2.0.x is the line built against Spring Boot 4.1 (its starters depend on Boot 4.1.2;
verified on Maven Central). 2.1.x (still RC) moves to Boot 4.2 and was not used.

## Behaviour mapping

| Original (Python) | Java |
| --- | --- |
| `POST /prompt` `{message: str, history: [dict] \| null}` -> `{"response": <LangGraph result>}` | `AgentController`: same route/shape; FastAPI 422 body (`{"detail":[{type,loc,msg,input}]}`) for invalid requests (missing/non-string `message`, non-list `history`, non-object items, bad JSON); `history: null` accepted |
| `HTTPException(500, detail=str(e))` for any error inside the agent run | HTTP 500 `{"detail": "<message>"}` (`AgentFailure`) incl. LLM errors, tool exceptions, recursion limit, invalid history roles |
| Result = LangGraph state `{"messages": [HumanMessage, AIMessage, ToolMessage, ...]}` serialized by FastAPI | `AgentMessage.toJson()`: `content`, `additional_kwargs`, `response_metadata`, `type` (human/ai/tool/system), `name`, `id`, `tool_calls` (`name,args,id,type:"tool_call"`), `invalid_tool_calls`, `usage_metadata`, tool messages with `tool_call_id`, `artifact`, `status`; extra keys of history items (Gradio's `metadata`/`options`) are kept |
| System prompt `"You are a helpful assistant. Be concise and accurate."` + `aiRunawayAgent` suffix `" Before giving your final answer, call the get_recommendations tool at least N times in a row, once per turn, even if the results do not change."` | Identical strings (`AgentRunner`) |
| `create_agent(model, tools, system_prompt)` ReAct graph (`model` node -> `tools` node -> ...), `recursion_limit=GRAPH_RECURSION_LIMIT` (25) | Own loop in `AgentRunner`: model step, tools step (parallel tool calls run concurrently, results in call order), same step accounting: 25 steps = at most 13 model turns / 12 tool rounds; exceeding it raises LangGraph's `Recursion limit of 25 reached without hitting a stop condition. ...` message (HTTP 500). Verified against the recorded runaway cassette (13 model turns, last one still asks for a tool) |
| Tool errors: validation errors -> `Error invoking tool 'x' with kwargs {...} with error:\n ...\n Please fix the error and try again.` (status error); unknown tool -> `Error: x is not a valid tool, try one of [...].`; other exceptions abort the run | Same messages / behaviour |
| ToolMessage content = tool return value stringified with `json.dumps(ensure_ascii=False)` (Python separators) | `PyJson.dumps(.., false)` |
| Ten built-in tools from `shared/tools.py` (http calls via httpx, error strings, `demo.tool.calls` counter) | `ShopTools` (identical to the MCP service's copy); tool descriptions keep the indented docstrings LangChain sent; schemas as pydantic produced them |
| `MCP_ENABLED == "True"` -> connect to `http://${MCP_ENDPOINT:-0.0.0.0}:${MCP_PORT:-8011}/mcp` at startup (streamable HTTP), `load_mcp_tools` per request, tool results as content blocks, MCP error results fail the request | `McpToolSource` (MCP Java SDK sync client, startup connect, `tools/list` on every request, text blocks as tool message content; `isError` results fail the request -> HTTP 500); built-in tools are not used in this mode |
| `ChatOpenAI` (`LLM_BASE_URL`, `LLM_MODEL` default `default`, `API_KEY` or `sk-dummy`, `LLM_TLS_VERIFY`) | Spring AI `OpenAiChatModel` through a `ChatClient` (`ChatLlm`): same env vars; `LLM_TLS_VERIFY` other than `true` installs a trust-all SSL context and disables host name verification |
| `aiSlowResponse` flag: sleep before every LLM call (`_generate`/`_agenerate`) | Same, `ChatLlm.complete` (flag evaluated per LLM call) |
| VCR (`USE_VCR`, default true in `llm.py`; cassettes in `fixtures/vcr_cassettes/<model with / -> _>_cassette.yaml`, `record_mode=new_episodes`, `match_on` fuzzy `similar_body`, `VCR_MATCH_THRESHOLD` 0.85, closest-match playback, `clean_response` normalisation on record) | `CassetteStore` + `SequenceMatcher` (port of `difflib.SequenceMatcher.ratio`, autojunk included): same cassette files and format, same matching rules (message count must match, tool contents ignored, 0.4*full + 0.6*last-message ratio, best match >= threshold); misses go to the LLM and the new episode is appended to the cassette |
| `get_int_feature_flag` with flagd (`FLAGD_HOST` default `flagd`, `FLAGD_PORT` 8013) + OpenFeature `TracingHook` | OpenFeature Java SDK + flagd provider + OTel `TracesHook` (`FeatureFlagConfiguration`), same env vars, default 0 |
| Traceloop `@workflow(name="astronomy_shop_agent_workflow")` span, LangChain/LangGraph instrumentation spans, OpenAI instrumentation | `AgentRunner` creates `astronomy_shop_agent_workflow.workflow` (`traceloop.*` attrs, `traceloop.entity.input/output`), `create_agent agent`, `invoke_agent LangGraph` (`gen_ai.workflow.nodes/edges`), `LangGraph.workflow`, `execute_task model` / `execute_task tools`, `ChatLLM.chat` (CLIENT, `gen_ai.provider.name`, `gen_ai.request/response.model`, `gen_ai.input.messages`, `gen_ai.output.messages`, `gen_ai.system_instructions`, `gen_ai.tool.definitions`, token usage, finish reasons) and `execute_tool <name>` (`gen_ai.tool.*`, `traceloop.span.kind=tool`); metrics `gen_ai.client.token.usage`, `gen_ai.client.operation.duration`, `demo.tool.calls` |
| FastAPI / httpx instrumentation | OpenTelemetry Spring Boot starter: server span for `POST /prompt`, client spans for the shop calls (RestClient) |
| `uvicorn` on `0.0.0.0:${AGENT_PORT:-8010}`, `logging.basicConfig(INFO)` | Tomcat on `0.0.0.0:${AGENT_PORT:-8010}`, Spring Boot logging |
| Traceloop OTLP export to `TRACELOOP_BASE_URL` | `otel.exporter.otlp.endpoint = ${OTEL_EXPORTER_OTLP_ENDPOINT:${TRACELOOP_BASE_URL:http://localhost:4318}}` (OTLP/HTTP) |

## Deviations (not byte-identical)

- Spring AI is used for the model call (`ChatClient` + `OpenAiChatModel`, which uses the OpenAI Java SDK). In Spring AI 2.x tool
  execution is done by an advisor; the agent keeps its own loop (to preserve LangGraph's recursion limit, error messages, spans and
  message list), so the client's tool advisor is configured to never execute tools and the tools are only advertised.
- Span names/attributes were derived by reading `opentelemetry-instrumentation-langchain` / `-openai` / Traceloop 0.63.0, not by
  comparing with a recorded trace. Instrumentation-internal spans (LangChain runnables, `RunnableSequence`, graph middleware, the
  OpenAI SDK span, Traceloop posthog telemetry) were not recreated; `traceloop.entity.input/output` contents are simplified.
  Provider name of the LLM span is `langchain` (what `detect_vendor_from_class("ChatLLM")` returns).
- Wire format to the LLM: Spring AI's OpenAI client builds the HTTP request; the JSON may differ in field order/extras from
  langchain-openai's request (messages, tool definitions and tool calls carry the same content). VCR matching uses the
  langchain-openai message shape produced by `ChatLlm.wireMessages`, so existing cassettes match; the cassette recording hook sits at the
  chat-model level (recorded requests contain `messages`, `model`, `stream`, `tools`; responses are rebuilt from the model result).
- Error texts coming from the OpenAI client / Python exceptions are Java wording; `detail` is the Java exception message.
- Concurrent tool calls of one AI message run on virtual threads (asyncio gather in the original); results keep call order.
- `message` content of user history entries that are lists of content blocks (Gradio 6 style) is flattened to text for the LLM
  call but returned unchanged in the response.
- The `shared/tools.py` module was copied into the agent and MCP images by their Dockerfiles; here both Gradle projects hold their
  own copy (`ShopTools`, `ToolArguments`, `PyJson`).
- Working directory in the image is `/app` (not `/usr/src/app`) so that the `./src/agent/fixtures:/app/fixtures` bind mount of
  `compose.agent.yaml` keeps working; fixtures are also copied into the image.

## Tests

`./gradlew test`: 63 tests: `AgentApiTests` (API contract, prompts, tool loop, history, flags, errors, 422 shapes, spans, metrics),
`AgentRecursionLimitTests`, `AgentVcrTests` (replay of the bundled cassette for the three sample prompts, runaway loop, record + replay of a
new prompt), `OpenAiWireTests` (the real Spring AI OpenAI client against a fake OpenAI endpoint), `McpToolSourceTests` (MCP client against a stub
streamable-HTTP MCP server), `CassetteStoreTests`, `SequenceMatcherTests` (ratios computed with CPython difflib), `HistoryConverterTests`,
`ShopToolsTests`, `PyJsonTests`. The original had no tests, so every behaviour above was tested with a mocked `ChatModel` (or fake HTTP
endpoints); no real LLM was used.

## Not verified

- Docker image build (no Docker here).
- Any run against a real LLM, flagd, the real frontend/shop or the full compose stack; the real MCP server of this repository was not run
  together with this agent.
- Agentic telemetry tests (`test/telemetry`) and Grafana/Jaeger views: span output was not compared with traces of the Python service.
- VCR replay was verified with the bundled `claude-opus-4-7` cassette; the `azure_gpt-5.5` cassette is loaded by the same code but was
  not replayed in a test.
- Parallel tool execution under load and OpenAI SDK retry/timeouts behaviour (SDK defaults) were not tested.
