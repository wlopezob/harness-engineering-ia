package com.gentleman.inventory.infrastructure.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;

/**
 * Expone el reloj del sistema como bean CDI. El núcleo recibe un Clock (puro, sustituible en tests
 * por Clock.fixed) en vez de llamar a Instant.now() (HARNESS C).
 */
@ApplicationScoped
public class ClockProducer {

  @Produces
  @ApplicationScoped
  public Clock clock() {
    return Clock.systemUTC();
  }
}
