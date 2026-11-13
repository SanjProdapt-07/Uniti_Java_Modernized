/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.checkout;

import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * RestClients for the HTTP services (shipping, email). They are built from the auto-configured
 * {@link RestClient.Builder} so the OpenTelemetry Spring Boot starter instruments them: client
 * spans and traceparent/baggage propagation, like the otelhttp transport in the Go service.
 * Plain HTTP/1.1 is used, as Go's net/http does for cleartext URLs.
 */
@Configuration(proxyBeanMethods = false)
class HttpClientConfiguration {

  @Bean
  ShippingClient shippingClient(
      RestClient.Builder builder, @Value("${checkout.shipping-addr}") String shippingAddr) {
    HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    return new ShippingClient(
        builder.clone().requestFactory(new JdkClientHttpRequestFactory(httpClient)).build(),
        shippingAddr);
  }

  @Bean
  EmailClient emailClient(
      RestClient.Builder builder, @Value("${checkout.email-addr}") String emailAddr) {
    HttpClient httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(EmailClient.REQUEST_TIMEOUT)
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(EmailClient.REQUEST_TIMEOUT);
    return new EmailClient(builder.clone().requestFactory(requestFactory).build(), emailAddr);
  }
}
