/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.imageprovider;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.EncodedResourceResolver;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the directory configured by {@code image-provider.static-root} at the URL root, the way the nginx
 * config did with {@code root /static; gzip_static on; autoindex off;}. No Cache-Control and no CORS headers
 * are added, because the nginx configuration did not add any either. Last-Modified and range support come
 * from the resource handler.
 */
@Configuration(proxyBeanMethods = false)
class StaticContentConfiguration implements WebMvcConfigurer {

  private final String staticRoot;

  StaticContentConfiguration(@Value("${image-provider.static-root}") String staticRoot) {
    this.staticRoot = staticRoot.endsWith("/") ? staticRoot : staticRoot + "/";
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/**")
        .addResourceLocations(staticRoot)
        .setUseLastModified(true)
        .resourceChain(false)
        // gzip_static: serve a pre-compressed "<file>.gz" when the client accepts gzip
        .addResolver(new EncodedResourceResolver())
        .addResolver(new PathResourceResolver());
  }

  @Bean
  StubStatus stubStatus() {
    return new StubStatus();
  }

  @Bean
  FilterRegistrationBean<RequestTelemetryFilter> requestTelemetryFilter(StubStatus stubStatus) {
    FilterRegistrationBean<RequestTelemetryFilter> bean =
        new FilterRegistrationBean<>(new RequestTelemetryFilter(stubStatus));
    // Must run inside the OpenTelemetry servlet instrumentation filter so the server span is current.
    bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 1000);
    return bean;
  }

  @Bean
  FilterRegistrationBean<DirectoryRequestFilter> directoryRequestFilter() throws java.io.IOException {
    Path root = new DefaultResourceLoader().getResource(staticRoot).getFile().toPath();
    FilterRegistrationBean<DirectoryRequestFilter> bean =
        new FilterRegistrationBean<>(new DirectoryRequestFilter(root));
    bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 1100);
    return bean;
  }
}
