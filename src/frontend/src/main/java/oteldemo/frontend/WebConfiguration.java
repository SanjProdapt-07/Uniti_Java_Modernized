/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frontend;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import oteldemo.frontend.api.ApiInstrumentationInterceptor;

@Configuration(proxyBeanMethods = false)
class WebConfiguration implements WebMvcConfigurer {

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new ApiInstrumentationInterceptor()).addPathPatterns("/api/**");
  }

  /**
   * Next.js answers {@code /path/} with a 308 redirect to {@code /path}; the load generator for
   * example calls {@code /api/data/}.
   */
  @Bean
  FilterRegistrationBean<TrailingSlashRedirectFilter> trailingSlashRedirectFilter() {
    FilterRegistrationBean<TrailingSlashRedirectFilter> registration =
        new FilterRegistrationBean<>(new TrailingSlashRedirectFilter());
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }
}
