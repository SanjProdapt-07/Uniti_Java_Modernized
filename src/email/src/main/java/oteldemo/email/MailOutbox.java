/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.email;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Stand-in for Ruby's {@code Mail::TestMailer.deliveries}, the "mail server" the original service
 * used ({@code Pony.mail(via: :test)}): a delivered email is only appended to an in-memory list.
 * Nothing is ever sent over the network. The emailMemoryLeak feature flag relies on the list
 * growing when it is not cleared.
 */
@Component
public class MailOutbox {

  /** One delivered email. */
  public record Mail(String to, String from, String subject, String body) {}

  private final List<Mail> deliveries = new ArrayList<>();

  /**
   * Delivers the email, with the checks {@code Mail::CheckDeliveryParams} makes for the test
   * mailer.
   */
  public synchronized void deliver(Mail mail) {
    if (mail.from() == null || mail.from().isBlank()) {
      throw new IllegalArgumentException("SMTP From address may not be blank");
    }
    if (mail.to() == null || mail.to().isBlank()) {
      throw new IllegalArgumentException("SMTP To address may not be blank");
    }
    deliveries.add(mail);
  }

  public synchronized void clear() {
    deliveries.clear();
  }

  public synchronized int size() {
    return deliveries.size();
  }

  public synchronized List<Mail> deliveries() {
    return List.copyOf(deliveries);
  }
}
