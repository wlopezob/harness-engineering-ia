package com.gentleman.inventory.infrastructure.rest;

import com.gentleman.inventory.domain.model.StockMovement;
import java.time.Instant;

/** Representación HTTP de un movimiento de stock. Coincide con el contrato openapi. */
public record StockMovementResponse(
    Long id,
    Long productId,
    int delta,
    int previousQuantity,
    int resultingQuantity,
    Instant occurredAt) {

  public static StockMovementResponse from(StockMovement movement) {
    return new StockMovementResponse(
        movement.id(),
        movement.productId(),
        movement.delta(),
        movement.previousQuantity(),
        movement.resultingQuantity(),
        movement.occurredAt());
  }
}
