/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.email;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Renders {@code templates/confirmation.erb}, the unchanged ERB template of the Ruby service, so
 * the email body is byte for byte what Sinatra's {@code erb(:confirmation)} produced.
 *
 * <p>Only the ERB subset the template uses is supported: {@code <%= a.b.c %>} output tags and
 * {@code <% x.items.each do |item| %> ... <% end %>} loops. As with Ruby's {@code OpenStruct}, a
 * missing field prints as an empty string, but reading a field of a missing object fails like
 * {@code NoMethodError}. Tilt's default ERB trim mode ({@code <>}) only strips lines that start
 * with a tag at column 0, which none of the template's lines do, so no whitespace is trimmed.
 */
@Component
public class ConfirmationTemplate {

  /** A failure to render, the counterpart of Ruby's NoMethodError on {@code nil}. */
  public static class TemplateRenderException extends RuntimeException {
    TemplateRenderException(String message) {
      super(message);
    }
  }

  private static final Pattern TAG = Pattern.compile("<%(=?)(.*?)%>", Pattern.DOTALL);
  private static final Pattern EACH = Pattern.compile("^(\\S+)\\.each\\s+do\\s*\\|(\\w+)\\|$");

  private sealed interface Node permits Text, Output, Loop {}

  private record Text(String text) implements Node {}

  private record Output(String expression) implements Node {}

  private record Loop(String collection, String variable, List<Node> body) implements Node {}

  private final List<Node> nodes;

  public ConfirmationTemplate() {
    this("templates/confirmation.erb");
  }

  ConfirmationTemplate(String resource) {
    this.nodes = parse(load(resource));
  }

  /** Renders the confirmation for an {@code OrderResult} object ({@code order} in the template). */
  public String render(JsonNode order) {
    StringBuilder out = new StringBuilder();
    renderNodes(nodes, Map.of("order", order), out);
    return out.toString();
  }

  private static String load(String resource) {
    try (InputStream in = new ClassPathResource(resource).getInputStream()) {
      // The repository stores the template with LF line endings; a CRLF checkout must not change
      // the length of the email, which the emailMemoryLeak flag multiplies.
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read template " + resource, e);
    }
  }

  private static List<Node> parse(String source) {
    List<List<Node>> stack = new ArrayList<>();
    List<String[]> open = new ArrayList<>(); // collection and variable of the open loops
    stack.add(new ArrayList<>());
    Matcher tag = TAG.matcher(source);
    int position = 0;
    while (tag.find()) {
      if (tag.start() > position) {
        stack.getLast().add(new Text(source.substring(position, tag.start())));
      }
      position = tag.end();
      String code = tag.group(2).strip();
      if (!tag.group(1).isEmpty()) {
        stack.getLast().add(new Output(code));
      } else if (code.equals("end")) {
        if (open.isEmpty()) {
          throw new IllegalStateException("Unexpected <% end %> in template");
        }
        String[] loop = open.removeLast();
        List<Node> body = stack.removeLast();
        stack.getLast().add(new Loop(loop[0], loop[1], body));
      } else {
        Matcher each = EACH.matcher(code);
        if (!each.matches()) {
          throw new IllegalStateException("Unsupported template code: " + code);
        }
        open.add(new String[] {each.group(1), each.group(2)});
        stack.add(new ArrayList<>());
      }
    }
    if (!open.isEmpty()) {
      throw new IllegalStateException("Unclosed loop in template");
    }
    if (position < source.length()) {
      stack.getLast().add(new Text(source.substring(position)));
    }
    return stack.getFirst();
  }

  private static void renderNodes(List<Node> nodes, Map<String, JsonNode> scope, StringBuilder out) {
    for (Node node : nodes) {
      switch (node) {
        case Text text -> out.append(text.text());
        case Output output -> out.append(print(evaluate(output.expression(), scope)));
        case Loop loop -> {
          JsonNode collection = evaluate(loop.collection(), scope);
          if (collection == null || !collection.isArray()) {
            throw new TemplateRenderException("undefined method 'each' for " + describe(collection));
          }
          for (JsonNode element : collection) {
            Map<String, JsonNode> inner = new java.util.HashMap<>(scope);
            inner.put(loop.variable(), element);
            renderNodes(loop.body(), inner, out);
          }
        }
      }
    }
  }

  /** Follows {@code a.b.c}; a missing last field is {@code null}, a missing middle one fails. */
  private static JsonNode evaluate(String expression, Map<String, JsonNode> scope) {
    String[] path = expression.split("\\.");
    if (!scope.containsKey(path[0])) {
      throw new TemplateRenderException("undefined local variable or method '" + path[0] + "'");
    }
    JsonNode current = scope.get(path[0]);
    for (int i = 1; i < path.length; i++) {
      if (current == null || !current.isObject()) {
        throw new TemplateRenderException(
            "undefined method '" + path[i] + "' for " + describe(current));
      }
      JsonNode next = current.get(path[i]);
      current = next == null || next.isNull() ? null : next;
    }
    return current;
  }

  private static String describe(JsonNode node) {
    return node == null ? "nil" : "an instance of " + node.getNodeType();
  }

  /** What {@code <%= value %>} prints for a value parsed from JSON by Ruby. */
  private static String print(JsonNode value) {
    if (value == null) {
      return "";
    }
    if (value.isString()) {
      return value.asString();
    }
    if (value.isFloatingPointNumber()) {
      double number = value.doubleValue();
      if (number == Math.rint(number) && Math.abs(number) < 1e16) {
        return new BigDecimal(number).toPlainString() + ".0";
      }
      return Double.toString(number);
    }
    return value.toString();
  }
}
