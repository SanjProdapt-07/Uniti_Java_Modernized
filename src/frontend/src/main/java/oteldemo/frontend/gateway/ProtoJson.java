/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frontend.gateway;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON mapping with the same shape the Next.js API produced from the ts-proto generated types:
 * camelCase field names, every scalar present with its default value, 64-bit integers as JSON
 * numbers (not strings as protobuf's canonical JSON does) and unset message fields omitted.
 */
public final class ProtoJson {

  private static final JsonFormat.Parser PARSER = JsonFormat.parser().ignoringUnknownFields();

  private ProtoJson() {}

  public static Map<String, Object> toMap(MessageOrBuilder message) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (FieldDescriptor field : message.getDescriptorForType().getFields()) {
      if (field.isRepeated()) {
        List<Object> values = new ArrayList<>();
        int count = message.getRepeatedFieldCount(field);
        for (int i = 0; i < count; i++) {
          values.add(convert(field, message.getRepeatedField(field, i)));
        }
        out.put(field.getJsonName(), values);
      } else if (field.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
        if (message.hasField(field)) {
          out.put(field.getJsonName(), convert(field, message.getField(field)));
        }
      } else {
        out.put(field.getJsonName(), convert(field, message.getField(field)));
      }
    }
    return out;
  }

  private static Object convert(FieldDescriptor field, Object value) {
    return switch (field.getJavaType()) {
      case MESSAGE -> toMap((MessageOrBuilder) value);
      case ENUM -> value.toString();
      default -> value;
    };
  }

  /** Merges camelCase (or snake_case) JSON into the builder; unknown fields are ignored. */
  public static <B extends Message.Builder> B merge(String json, B builder) {
    try {
      PARSER.merge(json, builder);
      return builder;
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalArgumentException("Invalid request body: " + e.getMessage(), e);
    }
  }
}
