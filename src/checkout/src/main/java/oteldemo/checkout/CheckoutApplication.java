/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.checkout;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.grpc.client.ImportGrpcClients;
import oteldemo.CartServiceGrpc.CartServiceBlockingStub;
import oteldemo.CurrencyServiceGrpc.CurrencyServiceBlockingStub;
import oteldemo.PaymentServiceGrpc.PaymentServiceBlockingStub;
import oteldemo.ProductCatalogServiceGrpc.ProductCatalogServiceBlockingStub;

@SpringBootApplication
@ImportGrpcClients(target = "cart", types = CartServiceBlockingStub.class)
@ImportGrpcClients(target = "currency", types = CurrencyServiceBlockingStub.class)
@ImportGrpcClients(target = "payment", types = PaymentServiceBlockingStub.class)
@ImportGrpcClients(target = "product-catalog", types = ProductCatalogServiceBlockingStub.class)
public class CheckoutApplication {

  public static void main(String[] args) {
    SpringApplication.run(CheckoutApplication.class, args);
  }
}
