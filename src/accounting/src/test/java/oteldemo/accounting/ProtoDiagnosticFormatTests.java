/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import oteldemo.Demo.OrderResult;

class ProtoDiagnosticFormatTests {

  @Test
  void rendersSingleLineJsonWithDiagnosticSpacing() {
    OrderResult order =
        OrderResult.newBuilder()
            .setOrderId("abc")
            .setShippingCost(TestOrders.money("USD", 8, 990000000))
            .build();

    assertThat(ProtoDiagnosticFormat.toDiagnosticString(order))
        .isEqualTo(
            "{ \"orderId\": \"abc\", \"shippingCost\": { \"currencyCode\": \"USD\", \"units\": \"8\", \"nanos\": 990000000 } }");
  }

  @Test
  void emptyMessageAndEmptyContainers() {
    assertThat(ProtoDiagnosticFormat.toDiagnosticString(OrderResult.getDefaultInstance())).isEqualTo("{ }");
    assertThat(ProtoDiagnosticFormat.spaced("{\"a\":[]}")).isEqualTo("{ \"a\": [ ] }");
  }

  @Test
  void leavesStringContentsUntouched() {
    OrderResult order = OrderResult.newBuilder().setOrderId("a,b:{c}\"d").build();

    assertThat(ProtoDiagnosticFormat.toDiagnosticString(order)).isEqualTo("{ \"orderId\": \"a,b:{c}\\\"d\" }");
  }

  @Test
  void rendersRepeatedMessages() {
    OrderResult order = TestOrders.order("o", "t").build();

    assertThat(ProtoDiagnosticFormat.toDiagnosticString(order))
        .contains("\"items\": [ { \"item\": { \"productId\": \"OLJCESPC7Z\", \"quantity\": 2 }, \"cost\": {")
        .endsWith("} } ] }");
  }
}
