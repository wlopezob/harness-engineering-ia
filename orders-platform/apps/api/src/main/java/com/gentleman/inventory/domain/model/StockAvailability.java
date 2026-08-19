package com.gentleman.inventory.domain.model;

/**
 * Resultado de consultar si un producto puede cubrir una cantidad solicitada. Objeto de valor puro:
 * describe una consulta, no cambia nada.
 */
public record StockAvailability(Long productId, int requestedQuantity, int availableQuantity) {

  /**
   * La regla vive en el constructor canónico, no en un factory: en un record el canónico es público
   * y sería la puerta trasera para construir una consulta inválida (lección de D-029).
   */
  public StockAvailability {
    if (requestedQuantity <= 0) {
      throw new IllegalArgumentException("La cantidad solicitada debe ser mayor que cero");
    }
  }

  /** Indica si el stock disponible cubre la cantidad solicitada. */
  public boolean available() {
    return availableQuantity >= requestedQuantity;
  }

  /** Cantidad que falta para cubrir lo solicitado; cero cuando el stock alcanza. */
  public int missingQuantity() {
    return available() ? 0 : requestedQuantity - availableQuantity;
  }
}
