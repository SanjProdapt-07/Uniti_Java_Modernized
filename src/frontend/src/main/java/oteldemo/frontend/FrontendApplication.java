/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frontend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.grpc.client.ImportGrpcClients;
import oteldemo.AdServiceGrpc.AdServiceBlockingStub;
import oteldemo.CartServiceGrpc.CartServiceBlockingStub;
import oteldemo.CheckoutServiceGrpc.CheckoutServiceBlockingStub;
import oteldemo.CurrencyServiceGrpc.CurrencyServiceBlockingStub;
import oteldemo.ProductCatalogServiceGrpc.ProductCatalogServiceBlockingStub;
import oteldemo.RecommendationServiceGrpc.RecommendationServiceBlockingStub;

/**
 * Java / Spring Boot port of the Next.js frontend: server-rendered pages (Thymeleaf), a small
 * static JavaScript layer for the interactive parts and the {@code /api/*} surface that bridges the
 * browser to the gRPC / HTTP backend services.
 */
@SpringBootApplication
@ImportGrpcClients(target = "ad", types = AdServiceBlockingStub.class)
@ImportGrpcClients(target = "cart", types = CartServiceBlockingStub.class)
@ImportGrpcClients(target = "checkout", types = CheckoutServiceBlockingStub.class)
@ImportGrpcClients(target = "currency", types = CurrencyServiceBlockingStub.class)
@ImportGrpcClients(target = "product-catalog", types = ProductCatalogServiceBlockingStub.class)
@ImportGrpcClients(target = "recommendation", types = RecommendationServiceBlockingStub.class)
public class FrontendApplication {

  public static void main(String[] args) {
    SpringApplication.run(FrontendApplication.class, args);
  }
}
