/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.agent;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * The ReAct agent behind {@code POST /prompt}: a Java port of {@code Agent.run_agent} (agents.py)
 * and of the LangChain {@code create_agent} graph it invoked ({@code model} node -> {@code tools}
 * node -> {@code model} ... until the model answers without tool calls), including LangGraph's
 * recursion limit, and the Traceloop / LangChain instrumentation spans the Python service emitted.
 */
class AgentRunner {

  static final String SYSTEM_PROMPT = "You are a helpful assistant. Be concise and accurate.";
  static final String WORKFLOW_NAME = "astronomy_shop_agent_workflow";

  /** Failure that the controller maps to HTTP 500 with {@code {"detail": message}}. */
  static final class AgentFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    AgentFailure(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private static final AttributeKey<String> SPAN_KIND = AttributeKey.stringKey("traceloop.span.kind");
  private static final AttributeKey<String> ENTITY_NAME =
      AttributeKey.stringKey("traceloop.entity.name");
  private static final AttributeKey<String> WORKFLOW = AttributeKey.stringKey("traceloop.workflow.name");
  private static final AttributeKey<String> ENTITY_INPUT =
      AttributeKey.stringKey("traceloop.entity.input");
  private static final AttributeKey<String> ENTITY_OUTPUT =
      AttributeKey.stringKey("traceloop.entity.output");
  private static final AttributeKey<String> PROVIDER = AttributeKey.stringKey("gen_ai.provider.name");
  private static final AttributeKey<String> OPERATION = AttributeKey.stringKey("gen_ai.operation.name");
  private static final AttributeKey<String> AGENT_NAME = AttributeKey.stringKey("gen_ai.agent.name");
  private static final AttributeKey<String> AGENT_ID = AttributeKey.stringKey("gen_ai.agent.id");
  private static final AttributeKey<String> TASK_NAME = AttributeKey.stringKey("gen_ai.task.name");
  private static final AttributeKey<String> TASK_ID = AttributeKey.stringKey("gen_ai.task.id");
  private static final AttributeKey<String> TASK_PARENT_ID =
      AttributeKey.stringKey("gen_ai.task.parent.id");
  private static final AttributeKey<String> TASK_STATUS = AttributeKey.stringKey("gen_ai.task.status");
  private static final AttributeKey<String> TASK_INPUT = AttributeKey.stringKey("gen_ai.task.input");
  private static final AttributeKey<String> TASK_OUTPUT = AttributeKey.stringKey("gen_ai.task.output");
  private static final AttributeKey<String> TOOL_NAME = AttributeKey.stringKey("gen_ai.tool.name");
  private static final AttributeKey<String> TOOL_TYPE = AttributeKey.stringKey("gen_ai.tool.type");
  private static final AttributeKey<String> TOOL_DESCRIPTION =
      AttributeKey.stringKey("gen_ai.tool.description");
  private static final AttributeKey<String> TOOL_ARGUMENTS =
      AttributeKey.stringKey("gen_ai.tool.call.arguments");
  private static final AttributeKey<String> TOOL_RESULT =
      AttributeKey.stringKey("gen_ai.tool.call.result");
  private static final AttributeKey<String> TOOL_DEFINITIONS =
      AttributeKey.stringKey("gen_ai.tool.definitions");
  private static final AttributeKey<String> SYSTEM_INSTRUCTIONS =
      AttributeKey.stringKey("gen_ai.system_instructions");
  private static final AttributeKey<String> INPUT_MESSAGES =
      AttributeKey.stringKey("gen_ai.input.messages");
  private static final AttributeKey<String> OUTPUT_MESSAGES =
      AttributeKey.stringKey("gen_ai.output.messages");
  private static final AttributeKey<String> REQUEST_MODEL =
      AttributeKey.stringKey("gen_ai.request.model");
  private static final AttributeKey<String> RESPONSE_MODEL =
      AttributeKey.stringKey("gen_ai.response.model");
  private static final AttributeKey<String> RESPONSE_ID = AttributeKey.stringKey("gen_ai.response.id");
  private static final AttributeKey<List<String>> FINISH_REASONS =
      AttributeKey.stringArrayKey("gen_ai.response.finish_reasons");
  private static final AttributeKey<Long> INPUT_TOKENS =
      AttributeKey.longKey("gen_ai.usage.input_tokens");
  private static final AttributeKey<Long> OUTPUT_TOKENS =
      AttributeKey.longKey("gen_ai.usage.output_tokens");
  private static final AttributeKey<Long> TOTAL_TOKENS =
      AttributeKey.longKey("gen_ai.usage.total_tokens");
  private static final AttributeKey<List<String>> WORKFLOW_NODES =
      AttributeKey.stringArrayKey("gen_ai.workflow.nodes");
  private static final AttributeKey<List<String>> WORKFLOW_EDGES =
      AttributeKey.stringArrayKey("gen_ai.workflow.edges");
  private static final AttributeKey<String> TOKEN_TYPE = AttributeKey.stringKey("gen_ai.token.type");
  private static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");

  private final Tracer tracer;
  private final ChatLlm llm;
  private final AgentTool.Source toolSource;
  private final FlagService flags;
  private final int recursionLimit;
  private final boolean traceContent;
  private final LongHistogram tokenHistogram;
  private final DoubleHistogram durationHistogram;
  private final ExecutorService toolExecutor;

  AgentRunner(
      OpenTelemetry openTelemetry,
      ChatLlm llm,
      AgentTool.Source toolSource,
      FlagService flags,
      int recursionLimit,
      boolean traceContent) {
    this.tracer = openTelemetry.getTracer("oteldemo.agent");
    this.llm = llm;
    this.toolSource = toolSource;
    this.flags = flags;
    this.recursionLimit = recursionLimit;
    this.traceContent = traceContent;
    var meter = openTelemetry.getMeter("oteldemo.agent");
    this.tokenHistogram =
        meter
            .histogramBuilder("gen_ai.client.token.usage")
            .ofLongs()
            .setUnit("token")
            .setDescription("Measures number of input and output tokens used")
            .build();
    this.durationHistogram =
        meter
            .histogramBuilder("gen_ai.client.operation.duration")
            .setUnit("s")
            .setDescription("GenAI operation duration")
            .build();
    this.toolExecutor = Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor());
  }

  /** {@code run_agent}: returns the LangGraph result, {@code {"messages": [...]}}. */
  Map<String, Object> run(String prompt, List<Map<String, Object>> history) {
    Span workflow =
        tracer.spanBuilder(WORKFLOW_NAME + ".workflow").setSpanKind(SpanKind.INTERNAL).startSpan();
    workflow.setAttribute(SPAN_KIND, "workflow");
    workflow.setAttribute(WORKFLOW, WORKFLOW_NAME);
    workflow.setAttribute(ENTITY_NAME, WORKFLOW_NAME);
    if (traceContent) {
      Map<String, Object> input = new LinkedHashMap<>();
      input.put("args", history == null ? List.of(prompt) : List.of(prompt, history));
      input.put("kwargs", Map.of());
      workflow.setAttribute(ENTITY_INPUT, PyJson.dumps(input, true));
    }
    try (Scope ignored = workflow.makeCurrent()) {
      List<AgentTool> tools = toolSource.tools();
      StringBuilder systemPrompt = new StringBuilder(SYSTEM_PROMPT);
      int runaway = flags.intFlag("aiRunawayAgent");
      if (runaway != 0) {
        systemPrompt
            .append(" Before giving your final answer, call the get_recommendations tool at least ")
            .append(runaway)
            .append(" times in a row, once per turn, even if the results do not change.");
      }
      Map<String, Object> result;
      try {
        result = invokeGraph(prompt, history, systemPrompt.toString(), tools);
      } catch (RuntimeException e) {
        throw new AgentFailure(describe(e), e);
      }
      if (traceContent) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("response", result);
        workflow.setAttribute(ENTITY_OUTPUT, PyJson.dumps(out, true));
      }
      return result;
    } catch (RuntimeException e) {
      workflow.setAttribute(ERROR_TYPE, e.getClass().getSimpleName());
      workflow.recordException(e);
      workflow.setStatus(StatusCode.ERROR, describe(e));
      throw e;
    } finally {
      workflow.end();
    }
  }

  // ---- the create_agent graph ------------------------------------------------------------------

  private Map<String, Object> invokeGraph(
      String prompt, List<Map<String, Object>> history, String systemPrompt, List<AgentTool> tools) {
    List<ChatLlm.ToolSpec> specs =
        tools.stream()
            .map(t -> new ChatLlm.ToolSpec(t.name(), t.description(), t.inputSchema()))
            .toList();

    // langchain.agents.create_agent(...) instrumentation span
    Span create = tracer.spanBuilder("create_agent agent").setSpanKind(SpanKind.INTERNAL).startSpan();
    create.setAttribute(OPERATION, "create_agent");
    create.setAttribute(PROVIDER, "langchain");
    create.setAttribute(AGENT_NAME, "agent");
    create.setAttribute(SYSTEM_INSTRUCTIONS, systemPrompt);
    if (!specs.isEmpty()) {
      List<Map<String, Object>> defs = new ArrayList<>();
      for (ChatLlm.ToolSpec s : specs) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", "function");
        d.put("name", s.name());
        d.put("description", s.description());
        d.put("parameters", s.schema());
        defs.add(d);
      }
      create.setAttribute(TOOL_DEFINITIONS, PyJson.dumps(defs, true));
    }
    create.end();

    List<AgentMessage> state = new ArrayList<>(HistoryConverter.convertAll(history));
    state.add(AgentMessage.human(prompt));

    String graphName = "LangGraph";
    Span graph = tracer.spanBuilder("invoke_agent " + graphName).setSpanKind(SpanKind.INTERNAL).startSpan();
    graph.setAttribute(PROVIDER, "langgraph");
    graph.setAttribute(OPERATION, "invoke_agent");
    graph.setAttribute(AGENT_NAME, graphName);
    graph.setAttribute(WORKFLOW_NODES, List.of("model", "tools"));
    graph.setAttribute(WORKFLOW_EDGES, List.of("tools -> model"));
    try (Scope graphScope = graph.makeCurrent()) {
      Span chain = chainSpan(graphName, state);
      try (Scope chainScope = chain.makeCurrent()) {
        String chainId = chain.getSpanContext().getSpanId();
        int steps = 0;
        while (true) {
          checkRecursion(steps);
          steps++;
          AgentMessage ai = modelNode(systemPrompt, state, specs, chainId);
          state.add(ai);
          if (ai.toolCalls().isEmpty()) {
            break;
          }
          checkRecursion(steps);
          steps++;
          state.addAll(toolsNode(ai.toolCalls(), tools, chainId));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> json = new ArrayList<>();
        for (AgentMessage m : state) {
          json.add(m.toJson());
        }
        result.put("messages", json);
        if (traceContent) {
          chain.setAttribute(ENTITY_OUTPUT, PyJson.dumps(Map.of("outputs", result, "kwargs", Map.of()), false));
        }
        chain.setAttribute(TASK_STATUS, "success");
        return result;
      } catch (RuntimeException e) {
        chain.setStatus(StatusCode.ERROR, describe(e));
        chain.recordException(e);
        throw e;
      } finally {
        chain.end();
      }
    } catch (RuntimeException e) {
      graph.setStatus(StatusCode.ERROR, describe(e));
      graph.recordException(e);
      throw e;
    } finally {
      graph.end();
    }
  }

  private void checkRecursion(int steps) {
    if (steps >= recursionLimit) {
      throw new IllegalStateException(
          "Recursion limit of "
              + recursionLimit
              + " reached without hitting a stop condition. You can increase the limit by setting"
              + " the `recursion_limit` config key.\n"
              + "For troubleshooting, visit:"
              + " https://docs.langchain.com/oss/python/langgraph/errors/GRAPH_RECURSION_LIMIT");
    }
  }

  private Span chainSpan(String name, List<AgentMessage> inputs) {
    Span span = tracer.spanBuilder(name + ".workflow").setSpanKind(SpanKind.INTERNAL).startSpan();
    span.setAttribute(WORKFLOW, name);
    span.setAttribute(SPAN_KIND, "workflow");
    span.setAttribute(ENTITY_NAME, name);
    span.setAttribute(PROVIDER, "langgraph");
    span.setAttribute(OPERATION, "invoke_agent");
    span.setAttribute(AGENT_NAME, name);
    span.setAttribute(AGENT_ID, UUID.randomUUID().toString());
    if (traceContent) {
      List<Map<String, Object>> msgs = new ArrayList<>();
      inputs.forEach(m -> msgs.add(m.toJson()));
      Map<String, Object> in = new LinkedHashMap<>();
      in.put("inputs", Map.of("messages", msgs));
      in.put("tags", List.of());
      span.setAttribute(ENTITY_INPUT, PyJson.dumps(in, false));
    }
    return span;
  }

  private Span taskSpan(String node, String parentTaskId) {
    Span span = tracer.spanBuilder("execute_task " + node).setSpanKind(SpanKind.INTERNAL).startSpan();
    span.setAttribute(WORKFLOW, "LangGraph");
    span.setAttribute(SPAN_KIND, "task");
    span.setAttribute(ENTITY_NAME, node);
    span.setAttribute(PROVIDER, "langgraph");
    span.setAttribute(OPERATION, "execute_task");
    span.setAttribute(TASK_NAME, node);
    span.setAttribute(TASK_ID, UUID.randomUUID().toString());
    span.setAttribute(TASK_PARENT_ID, parentTaskId);
    return span;
  }

  // ---- model node ------------------------------------------------------------------------------

  private AgentMessage modelNode(
      String systemPrompt, List<AgentMessage> state, List<ChatLlm.ToolSpec> specs, String chainId) {
    Span task = taskSpan("model", chainId);
    try (Scope ignored = task.makeCurrent()) {
      List<AgentMessage> request = new ArrayList<>();
      request.add(AgentMessage.builder("system").content(systemPrompt).id(null).build());
      request.addAll(state);

      Span llmSpan = tracer.spanBuilder("ChatLLM.chat").setSpanKind(SpanKind.CLIENT).startSpan();
      llmSpan.setAttribute(WORKFLOW, "LangGraph");
      llmSpan.setAttribute(PROVIDER, "langchain");
      llmSpan.setAttribute(OPERATION, "chat");
      llmSpan.setAttribute(REQUEST_MODEL, llm.model());
      llmSpan.setAttribute(RESPONSE_MODEL, llm.model());
      if (traceContent) {
        setRequestContent(llmSpan, request, specs);
      }
      long started = System.nanoTime();
      ChatLlm.Completion completion;
      try (Scope llmScope = llmSpan.makeCurrent()) {
        completion = llm.complete(request, specs);
      } catch (RuntimeException e) {
        llmSpan.setStatus(StatusCode.ERROR, describe(e));
        llmSpan.recordException(e);
        llmSpan.end();
        task.setAttribute(TASK_STATUS, "failure");
        throw e;
      }
      String responseModel = completion.model() == null ? llm.model() : completion.model();
      llmSpan.setAttribute(RESPONSE_MODEL, responseModel);
      if (completion.id() != null && !completion.id().isEmpty()) {
        llmSpan.setAttribute(RESPONSE_ID, completion.id());
      }
      if (completion.promptTokens() != null && completion.completionTokens() != null) {
        llmSpan.setAttribute(INPUT_TOKENS, (long) completion.promptTokens());
        llmSpan.setAttribute(OUTPUT_TOKENS, (long) completion.completionTokens());
        llmSpan.setAttribute(
            TOTAL_TOKENS, (long) (completion.promptTokens() + completion.completionTokens()));
        recordTokens(completion.promptTokens(), "input", responseModel);
        recordTokens(completion.completionTokens(), "output", responseModel);
      }
      if (completion.finishReason() != null) {
        llmSpan.setAttribute(FINISH_REASONS, List.of(mapFinishReason(completion.finishReason())));
      }
      if (traceContent) {
        llmSpan.setAttribute(OUTPUT_MESSAGES, PyJson.dumps(outputMessages(completion), true));
      }
      durationHistogram.record(
          (System.nanoTime() - started) / 1e9,
          io.opentelemetry.api.common.Attributes.of(
              PROVIDER, "langchain", RESPONSE_MODEL, responseModel));
      llmSpan.end();

      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("model_name", responseModel);
      metadata.put("finish_reason", completion.finishReason());
      metadata.put("model_provider", "openai");
      Map<String, Object> usage = null;
      if (completion.promptTokens() != null && completion.completionTokens() != null) {
        usage = new LinkedHashMap<>();
        usage.put("input_tokens", completion.promptTokens());
        usage.put("output_tokens", completion.completionTokens());
        usage.put("total_tokens", completion.promptTokens() + completion.completionTokens());
      }
      AgentMessage ai =
          AgentMessage.builder("ai")
              .content(completion.content())
              .id("lc_run--" + UUID.randomUUID())
              .toolCalls(completion.toolCalls())
              .invalidToolCalls(completion.invalidToolCalls())
              .responseMetadata(metadata)
              .usageMetadata(usage)
              .build();
      task.setAttribute(TASK_STATUS, "success");
      if (traceContent) {
        String out = PyJson.dumps(Map.of("outputs", Map.of("messages", List.of(ai.toJson()))), false);
        task.setAttribute(ENTITY_OUTPUT, out);
        task.setAttribute(TASK_OUTPUT, out);
      }
      return ai;
    } finally {
      task.end();
    }
  }

  private static String mapFinishReason(String reason) {
    return switch (reason) {
      case "tool_calls" -> "tool_call";
      case "length" -> "length";
      case "content_filter" -> "content_filter";
      default -> reason;
    };
  }

  private void recordTokens(int count, String type, String model) {
    if (count > 0) {
      tokenHistogram.record(
          count,
          io.opentelemetry.api.common.Attributes.of(
              PROVIDER, "langchain", TOKEN_TYPE, type, RESPONSE_MODEL, model));
    }
  }

  private static void setRequestContent(
      Span span, List<AgentMessage> request, List<ChatLlm.ToolSpec> specs) {
    if (!specs.isEmpty()) {
      List<Map<String, Object>> defs = new ArrayList<>();
      for (ChatLlm.ToolSpec s : specs) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", s.name());
        if (!s.description().isEmpty()) {
          d.put("description", s.description());
        }
        d.put("parameters", s.schema());
        defs.add(d);
      }
      span.setAttribute(TOOL_DEFINITIONS, PyJson.dumps(defs, true));
    }
    List<Object> system = new ArrayList<>();
    List<Map<String, Object>> input = new ArrayList<>();
    for (AgentMessage m : request) {
      switch (m.type()) {
        case "system" -> {
          if (!m.text().isEmpty()) {
            system.add(Map.of("type", "text", "content", m.text()));
          }
        }
        case "tool" -> {
          Map<String, Object> part = new LinkedHashMap<>();
          part.put("type", "tool_call_response");
          part.put("id", m.toolCallId());
          part.put("response", m.content() instanceof String s ? s : PyJson.dumps(m.content(), true));
          input.add(Map.of("role", "tool", "parts", List.of(part)));
        }
        default -> {
          List<Object> parts = new ArrayList<>();
          if (!m.text().isEmpty()) {
            parts.add(Map.of("type", "text", "content", m.text()));
          }
          for (AgentMessage.ToolCall c : m.toolCalls()) {
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("type", "tool_call");
            part.put("id", c.id());
            part.put("name", c.name());
            part.put("arguments", c.args());
            parts.add(part);
          }
          input.add(Map.of("role", m.type().equals("human") ? "user" : "assistant", "parts", parts));
        }
      }
    }
    if (!system.isEmpty()) {
      span.setAttribute(SYSTEM_INSTRUCTIONS, PyJson.dumps(system, true));
    }
    if (!input.isEmpty()) {
      span.setAttribute(INPUT_MESSAGES, PyJson.dumps(input, true));
    }
  }

  private static List<Map<String, Object>> outputMessages(ChatLlm.Completion c) {
    List<Object> parts = new ArrayList<>();
    if (!c.content().isEmpty()) {
      parts.add(Map.of("type", "text", "content", c.content()));
    }
    for (AgentMessage.ToolCall tc : c.toolCalls()) {
      Map<String, Object> part = new LinkedHashMap<>();
      part.put("type", "tool_call");
      part.put("id", tc.id());
      part.put("name", tc.name());
      part.put("arguments", tc.args());
      parts.add(part);
    }
    Map<String, Object> msg = new LinkedHashMap<>();
    msg.put("role", "assistant");
    msg.put("parts", parts);
    msg.put("finish_reason", c.finishReason() == null ? "" : mapFinishReason(c.finishReason()));
    return List.of(msg);
  }

  // ---- tools node ------------------------------------------------------------------------------

  private List<AgentMessage> toolsNode(
      List<AgentMessage.ToolCall> calls, List<AgentTool> tools, String chainId) {
    Span task = taskSpan("tools", chainId);
    try (Scope ignored = task.makeCurrent()) {
      List<AgentMessage> out = new ArrayList<>();
      if (calls.size() == 1) {
        out.add(runTool(calls.get(0), tools));
      } else {
        // LangGraph's ToolNode runs the calls of one AI message concurrently
        List<CompletableFuture<AgentMessage>> futures = new ArrayList<>();
        for (AgentMessage.ToolCall call : calls) {
          futures.add(CompletableFuture.supplyAsync(() -> runTool(call, tools), toolExecutor));
        }
        for (CompletableFuture<AgentMessage> f : futures) {
          try {
            out.add(f.join());
          } catch (CompletionException e) {
            task.setAttribute(TASK_STATUS, "failure");
            if (e.getCause() instanceof RuntimeException re) {
              throw re;
            }
            throw e;
          }
        }
      }
      task.setAttribute(TASK_STATUS, "success");
      return out;
    } finally {
      task.end();
    }
  }

  private AgentMessage runTool(AgentMessage.ToolCall call, List<AgentTool> tools) {
    AgentTool tool = tools.stream().filter(t -> t.name().equals(call.name())).findFirst().orElse(null);
    if (tool == null) {
      String available = tools.stream().map(AgentTool::name).collect(Collectors.joining(", "));
      return toolMessage(
          call,
          "Error: " + call.name() + " is not a valid tool, try one of [" + available + "].",
          "error");
    }
    Span span =
        tracer.spanBuilder("execute_tool " + call.name()).setSpanKind(SpanKind.INTERNAL).startSpan();
    span.setAttribute(WORKFLOW, "LangGraph");
    span.setAttribute(SPAN_KIND, "tool");
    span.setAttribute(ENTITY_NAME, call.name());
    span.setAttribute(PROVIDER, "langgraph");
    span.setAttribute(OPERATION, "execute_tool");
    span.setAttribute(TOOL_NAME, call.name());
    span.setAttribute(TOOL_TYPE, "function");
    if (!tool.description().isEmpty()) {
      span.setAttribute(TOOL_DESCRIPTION, tool.description());
    }
    if (traceContent) {
      Map<String, Object> input = new LinkedHashMap<>();
      input.put("input_str", PyJson.repr(call.args()));
      input.put("inputs", call.args());
      String json = PyJson.dumps(input, false);
      span.setAttribute(ENTITY_INPUT, json);
      span.setAttribute(TOOL_ARGUMENTS, json);
    }
    try (Scope ignored = span.makeCurrent()) {
      Object content;
      try {
        content = tool.call(call.args());
      } catch (ToolArguments.InvalidArguments e) {
        span.setAttribute(TASK_STATUS, "failure");
        String message =
            "Error invoking tool '"
                + call.name()
                + "' with kwargs "
                + PyJson.repr(call.args())
                + " with error:\n "
                + e.getMessage()
                + "\n Please fix the error and try again.";
        span.end();
        return toolMessage(call, message, "error");
      } catch (RuntimeException e) {
        span.setStatus(StatusCode.ERROR, describe(e));
        span.recordException(e);
        span.end();
        throw e;
      }
      span.setAttribute(TASK_STATUS, "success");
      if (traceContent) {
        String json = PyJson.dumps(Map.of("output", content, "kwargs", Map.of()), false);
        span.setAttribute(ENTITY_OUTPUT, json);
        span.setAttribute(TOOL_RESULT, json);
      }
      span.end();
      return toolMessage(call, content, "success");
    }
  }

  private static AgentMessage toolMessage(AgentMessage.ToolCall call, Object content, String status) {
    return AgentMessage.builder("tool")
        .content(content)
        .name(call.name())
        .toolCallId(call.id())
        .status(status)
        .build();
  }

  /** {@code str(exception)}. */
  static String describe(Throwable e) {
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }
}
