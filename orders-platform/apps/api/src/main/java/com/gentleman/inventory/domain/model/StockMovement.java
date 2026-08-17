package com.gentleman.inventory.domain.model;

import java.time.Instant;

/**
 * Movimiento de stock: registro inmutable de un ajuste aplicado a un producto. Cero framework (lo
 * vigila ArchitectureTest). El id lo asigna la persistencia.
 */
public record StockMovement(
    Long id,
    Long productId,
    int delta,
    int previousQuantity,
    int resultingQuantity,
    Instant occurredAt) {

  /**
   * Registra el movimiento entre el estado anterior y el posterior de un producto. El delta se
   * deriva de las cantidades para que no pueda grabarse un movimiento inconsistente con ellas.
   */
  public static StockMovement record(Product before, Product after, Instant occurredAt) {
    int previous = before.quantity();
    int resulting = after.quantity();
    return new StockMovement(
        null, before.id(), resulting - previous, previous, resulting, occurredAt);
  }
}
