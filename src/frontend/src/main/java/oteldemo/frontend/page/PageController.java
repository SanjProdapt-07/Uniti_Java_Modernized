/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frontend.page;

import java.time.Year;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The storefront pages. Each one is a server-rendered shell (header, footer, page structure and the
 * {@code window.ENV} bootstrap); the dynamic content is rendered by {@code /assets/js/app.js}
 * from the {@code /api/*} routes, as the React pages did.
 */
@Controller
class PageController {

  private final WindowEnvironment windowEnvironment;

  PageController(WindowEnvironment windowEnvironment) {
    this.windowEnvironment = windowEnvironment;
  }

  @GetMapping("/")
  String home(Model model) {
    return render(model, "index", "Otel Demo - Home", "home");
  }

  @GetMapping("/product/{productId}")
  String product(Model model) {
    return render(model, "product", "Otel Demo - Product", "product");
  }

  @GetMapping("/cart")
  String cart(Model model) {
    return render(model, "cart", "Otel Demo - Cart", "cart");
  }

  @GetMapping("/cart/checkout/{orderId}")
  String checkout(Model model) {
    return render(model, "checkout", "Otel Demo - Checkout", "checkout");
  }

  private String render(Model model, String view, String title, String page) {
    model.addAttribute("title", title);
    model.addAttribute("page", page);
    java.util.Map<String, String> env = windowEnvironment.current();
    model.addAttribute("env", env);
    model.addAttribute("platform", env.get("NEXT_PUBLIC_PLATFORM"));
    model.addAttribute("year", Year.now().getValue());
    return view;
  }
}
