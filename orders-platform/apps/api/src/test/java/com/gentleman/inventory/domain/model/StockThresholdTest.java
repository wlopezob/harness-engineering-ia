package com.gentleman.inventory.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class StockThresholdTest {

  @Test
  void rechaza_valor_negativo() {
    assertThrows(IllegalArgumentException.class, () -> new StockThreshold(-1));
  }

  @Test
  void acepta_cero() {
    StockThreshold threshold = new StockThreshold(0);

    assertEquals(0, threshold.value());
  }

  @Test
  void acepta_valor_positivo() {
    StockThreshold threshold = new StockThreshold(7);

    assertEquals(7, threshold.value());
  }
}
