package com.gentleman.inventory.infrastructure.rest;

import com.gentleman.inventory.domain.model.StockAvailability;

/**
 * Representación HTTP de una consulta de disponibilidad. Coincide con el contrato openapi.
 * available y missingQuantity se derivan en el dominio: aquí solo se copian.
 */
public record StockAvailabilityResponse(
    Long productId,
    int requestedQuantity,
    int availableQuantity,
    boolean available,
    int missingQuantity) {

  public static StockAvailabilityResponse from(StockAvailability availability) {
    return new StockAvailabilityResponse(
        availability.productId(),
        availability.requestedQuantity(),
        availability.availableQuantity(),
        availability.available(),
        availability.missingQuantity());
  }
}
