package com.gentleman.inventory.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class StockMovementTest {

  private static final Instant AT = Instant.parse("2026-08-17T15:04:05Z");

  @Test
  void record_toma_cantidades_de_antes_y_despues_y_calcula_el_delta() {
    Product before = Product.restore(7L, "Teclado", "KEY-001", 10, ProductStatus.ACTIVE);
    Product after = before.adjustStock(5);

    StockMovement movement = StockMovement.record(before, after, AT);

    assertNull(movement.id(), "el id lo asigna la persistencia");
    assertEquals(7L, movement.productId());
    assertEquals(10, movement.previousQuantity());
    assertEquals(15, movement.resultingQuantity());
    assertEquals(5, movement.delta());
    assertEquals(AT, movement.occurredAt());
  }

  @Test
  void record_de_una_salida_tiene_delta_negativo() {
    Product before = Product.restore(7L, "Teclado", "KEY-001", 10, ProductStatus.ACTIVE);
    Product after = before.adjustStock(-3);

    StockMovement movement = StockMovement.record(before, after, AT);

    assertEquals(-3, movement.delta());
    assertEquals(10, movement.previousQuantity());
    assertEquals(7, movement.resultingQuantity());
  }
}
