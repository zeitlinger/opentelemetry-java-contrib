/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.contrib.dynamic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.incubator.config.DeclarativeConfigProperties;
import io.opentelemetry.contrib.dynamic.policy.registry.PolicyInit;
import io.opentelemetry.contrib.dynamic.policy.tracesampling.TraceSamplingRatePolicy;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import mockwebserver3.junit5.StartStop;
import okio.Buffer;
import opamp.proto.AgentToServer;
import opamp.proto.ServerToAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

class TelemetryPolicySamplerComponentProviderTest {
  @StartStop private final MockWebServer server = new MockWebServer();

  @AfterEach
  void tearDown() throws Exception {
    invokeStaticNoArg(PolicyInit.class, "resetForTest");
    invokeStaticNoArg(TraceSamplingRatePolicy.class, "resetForTest");
  }

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void initializesOpampFromSystemPropertiesAndDeclarativeResource() throws Exception {
    String previousEndpoint =
        System.setProperty("otel.opamp.service.url", server.url("/v1/opamp").toString());
    String previousServiceName = System.getProperty("otel.service.name");
    System.clearProperty("otel.service.name");
    String previousHeaders =
        System.setProperty(
            "otel.experimental.opamp.headers",
            "Authorization=Bearer token,X-Test-Header=test-value");
    try {
      server.enqueue(emptyServerResponse());
      TelemetryPolicySamplerComponentProvider provider =
          new TelemetryPolicySamplerComponentProvider();
      provider.create(telemetryPolicyNodeConfig());

      assertThat(TraceSamplingRatePolicy.getInitializedSampler()).isNotNull();
      RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
      assertThat(request).isNotNull();
      assertThat(request.getHeaders().get("Authorization")).isEqualTo("Bearer token");
      assertThat(request.getHeaders().get("X-Test-Header")).isEqualTo("test-value");
      AgentToServer message =
          AgentToServer.ADAPTER.decode(Objects.requireNonNull(request.getBody()));
      assertThat(Objects.requireNonNull(message.agent_description).identifying_attributes)
          .anySatisfy(
              attribute -> {
                assertThat(attribute.key).isEqualTo("service.name");
                assertThat(Objects.requireNonNull(attribute.value).string_value)
                    .isEqualTo("resource-service");
              });
    } finally {
      restoreSystemProperty("otel.opamp.service.url", previousEndpoint);
      restoreSystemProperty("otel.service.name", previousServiceName);
      restoreSystemProperty("otel.experimental.opamp.headers", previousHeaders);
    }
  }

  @Test
  void doesNothingWhenTelemetryPolicyDeclarativeConfigMissing() {
    TelemetryPolicySamplerComponentProvider provider =
        new TelemetryPolicySamplerComponentProvider();
    provider.create(mock(DeclarativeConfigProperties.class));

    assertThat(TraceSamplingRatePolicy.getInitializedSampler()).isNull();
  }

  private static DeclarativeConfigProperties telemetryPolicyNodeConfig() {
    DeclarativeConfigProperties telemetryPolicy = mock(DeclarativeConfigProperties.class);
    DeclarativeConfigProperties source = mock(DeclarativeConfigProperties.class);
    DeclarativeConfigProperties mapping = mock(DeclarativeConfigProperties.class);
    DeclarativeConfigProperties resourceAttributes = mock(DeclarativeConfigProperties.class);

    when(telemetryPolicy.getStructuredList("sources"))
        .thenReturn(Collections.singletonList(source));
    when(telemetryPolicy.getStructured("otel.resource.attributes")).thenReturn(resourceAttributes);
    when(resourceAttributes.getPropertyKeys()).thenReturn(Collections.singleton("service.name"));
    when(resourceAttributes.getString("service.name")).thenReturn("resource-service");
    when(source.getString("kind")).thenReturn("opamp");
    when(source.getString("format")).thenReturn("jsonkeyvalue");
    when(source.getString("location")).thenReturn("vendor");
    when(source.getStructuredList("mappings")).thenReturn(Collections.singletonList(mapping));
    when(mapping.getString("policyId")).thenReturn("sampling-rate");
    when(mapping.getString("policyType")).thenReturn(TraceSamplingRatePolicy.POLICY_TYPE);

    return telemetryPolicy;
  }

  private static void restoreSystemProperty(String name, String previousValue) {
    if (previousValue == null) {
      System.clearProperty(name);
    } else {
      System.setProperty(name, previousValue);
    }
  }

  private static MockResponse emptyServerResponse() {
    Buffer body = new Buffer();
    body.write(new ServerToAgent.Builder().build().encode());
    return new MockResponse.Builder().code(200).body(body).build();
  }

  private static void invokeStaticNoArg(Class<?> targetClass, String methodName) throws Exception {
    Method method = targetClass.getDeclaredMethod(methodName);
    method.setAccessible(true);
    method.invoke(null);
  }
}
