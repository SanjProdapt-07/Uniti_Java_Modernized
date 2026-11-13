/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.openfeature.sdk.Client;
import io.grpc.Status;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.stub.StreamObserver;
import io.lettuce.core.resource.ClientResources;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.opamp.client.OpampClientBuilder;
import io.opentelemetry.sdk.resources.Resource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConfigurationTests {

  // --- VALKEY_ADDR

  @Test
  void valkeyAddressWithHostAndPort() {
    assertThat(ValkeyAddress.parse("valkey-cart:6379")).isEqualTo(new ValkeyAddress("valkey-cart", 6379));
    assertThat(ValkeyAddress.parse(" cache.example.com:6380 "))
        .isEqualTo(new ValkeyAddress("cache.example.com", 6380));
  }

  @Test
  void valkeyAddressWithoutPortUsesTheDefaultPort() {
    assertThat(ValkeyAddress.parse("valkey-cart")).isEqualTo(new ValkeyAddress("valkey-cart", 6379));
    assertThat(ValkeyAddress.parse("valkey-cart:")).isEqualTo(new ValkeyAddress("valkey-cart", 6379));
  }

  @Test
  void connectionStringIsTheOneOfTheOriginalAndNeverHasThePassword() {
    ValkeyAddress address = ValkeyAddress.parse("valkey-cart:6379");

    assertThat(address.connectionString(false))
        .isEqualTo("valkey-cart:6379,ssl=false,allowAdmin=true,abortConnect=false");
    assertThat(address.connectionString(true))
        .isEqualTo("valkey-cart:6379,ssl=true,allowAdmin=true,abortConnect=false");
  }

  @Test
  void missingValkeyAddressStopsTheStartup() {
    ValkeyConfiguration configuration = new ValkeyConfiguration();
    ClientResources resources = mock(ClientResources.class);

    for (String blank : new String[] {"", "  "}) {
      assertThatThrownBy(
              () ->
                  configuration.cartStore(
                      resources, mock(Client.class), OpenTelemetry.noop(), blank, "", "false"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("VALKEY_ADDR environment variable is required.");
    }
    verifyNoInteractions(resources);
  }

  @Test
  void theBadStoreTargetsBadhostAndIsNotConnectedAtStartup() {
    ClientResources resources = mock(ClientResources.class);

    ValkeyCartStore bad =
        new ValkeyConfiguration().badCartStore(resources, mock(Client.class), OpenTelemetry.noop());

    assertThat(ValkeyConfiguration.BAD_HOST_ADDRESS).isEqualTo("badhost:1234");
    // not initialized: nothing is connected until it is used
    assertThat(bad.ping()).isFalse();
    verifyNoInteractions(resources);
  }

  // --- codec

  @Test
  void codecKeepsBinaryValuesAndUtf8Keys() {
    byte[] value = {0, 1, 2, (byte) 0xff, (byte) 0x80};

    ByteBuffer encoded = ByteArrayValueCodec.INSTANCE.encodeValue(value);

    assertThat(ByteArrayValueCodec.INSTANCE.decodeValue(encoded)).isEqualTo(value);
    assertThat(ByteArrayValueCodec.INSTANCE.decodeKey(ByteArrayValueCodec.INSTANCE.encodeKey("ключ")))
        .isEqualTo("ключ");
    assertThat(ByteArrayValueCodec.INSTANCE.encodeKey("ключ").remaining())
        .isEqualTo("ключ".getBytes(StandardCharsets.UTF_8).length);
  }

  // --- Lettuce resources

  @Test
  void clientResourcesHaveTracingEnabled() throws Exception {
    ClientResources resources =
        new ValkeyConfiguration().valkeyClientResources(OpenTelemetry.noop());
    try {
      assertThat(resources.tracing().isEnabled()).isTrue();
    } finally {
      resources.shutdown(0, 1, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS);
    }
  }

  // --- readiness / health

  @Test
  void readinessFollowsTheFailedReadinessProbeFlag() {
    Client flags = mock(Client.class);
    when(flags.getBooleanValue("failedReadinessProbe", false)).thenReturn(false, true);
    ReadinessCheck check = new ReadinessCheck(flags);

    assertThat(check.isHealthy()).isTrue();
    assertThat(check.isHealthy()).isFalse();
    assertThat(ReadinessCheck.NAME).isEqualTo("oteldemo.CartService");
  }

  @Test
  @SuppressWarnings("unchecked")
  void healthCheckOfAnUnknownServiceFailsWithUnknown() {
    HealthService health = new HealthService(mock(ReadinessCheck.class));
    StreamObserver<HealthCheckResponse> observer = mock(StreamObserver.class);

    health.check(HealthCheckRequest.newBuilder().setService("other").build(), observer);

    ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
    verify(observer).onError(error.capture());
    assertThat(Status.fromThrowable(error.getValue()).getCode()).isEqualTo(Status.Code.UNKNOWN);
  }

  @Test
  @SuppressWarnings("unchecked")
  void healthCheckMapsReadinessToServingStatus() {
    ReadinessCheck readiness = mock(ReadinessCheck.class);
    HealthService health = new HealthService(readiness);
    StreamObserver<HealthCheckResponse> observer = mock(StreamObserver.class);

    when(readiness.isHealthy()).thenReturn(true);
    health.check(HealthCheckRequest.getDefaultInstance(), observer);
    when(readiness.isHealthy()).thenReturn(false);
    health.check(HealthCheckRequest.newBuilder().setService("oteldemo.CartService").build(), observer);

    ArgumentCaptor<HealthCheckResponse> responses = ArgumentCaptor.forClass(HealthCheckResponse.class);
    verify(observer, org.mockito.Mockito.times(2)).onNext(responses.capture());
    assertThat(responses.getAllValues())
        .extracting(HealthCheckResponse::getStatus)
        .containsExactly(ServingStatus.SERVING, ServingStatus.NOT_SERVING);
  }

  // --- OpAMP

  @Test
  void opAmpClientIsNotStartedWithoutAnEndpoint() {
    assertThat(new OpAmpClientManager("", Resource.empty(), false).isRunning()).isFalse();
    assertThat(new OpAmpClientManager(null, Resource.empty(), false).isRunning()).isFalse();
  }

  @Test
  void opAmpStartFailureIsSwallowed() {
    OpAmpClientManager manager = new OpAmpClientManager("not a url", Resource.empty(), false);

    assertThat(manager.isRunning()).isFalse();
  }

  @Test
  void opAmpAttributesAreSplitIntoIdentifyingAndNonIdentifying() {
    Resource resource =
        Resource.create(
            Attributes.builder()
                .put("service.name", "cart")
                .put("service.instance.id", "abc")
                .put("service.namespace", "opentelemetry-demo")
                .put("host.name", "h1")
                .put("process.pid", 42L)
                .build());
    OpampClientBuilder builder = mock(OpampClientBuilder.class);

    OpAmpClientManager.addAttributes(builder, resource);

    verify(builder).putIdentifyingAttribute("service.name", "cart");
    verify(builder).putIdentifyingAttribute("service.instance.id", "abc");
    verify(builder).putIdentifyingAttribute("service.namespace", "opentelemetry-demo");
    verify(builder).putNonIdentifyingAttribute("host.name", "h1");
    verify(builder).putNonIdentifyingAttribute("process.pid", "42");
  }
}
