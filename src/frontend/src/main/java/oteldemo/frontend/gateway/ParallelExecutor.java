/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frontend.gateway;

import io.opentelemetry.context.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Runs the independent backend calls of a request concurrently, like the {@code Promise.all}
 * fan-outs of the original Node implementation. Tasks run on virtual threads and inherit the
 * caller's OpenTelemetry context, so spans and baggage propagate.
 */
@Component
public class ParallelExecutor {

  private final Executor executor =
      Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor());

  /** Maps every element concurrently; fails with the first failure, like {@code Promise.all}. */
  public <T, R> List<R> map(List<T> items, Function<T, R> fn) {
    List<CompletableFuture<R>> futures = new ArrayList<>(items.size());
    for (T item : items) {
      futures.add(CompletableFuture.supplyAsync(() -> fn.apply(item), executor));
    }
    List<R> results = new ArrayList<>(items.size());
    for (CompletableFuture<R> future : futures) {
      try {
        results.add(future.join());
      } catch (CompletionException e) {
        if (e.getCause() instanceof RuntimeException runtime) {
          throw runtime;
        }
        throw e;
      }
    }
    return results;
  }
}
