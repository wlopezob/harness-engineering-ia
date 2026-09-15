package com.gentleman.inventory.domain.model;

/**
 * Umbral de stock para la consulta de productos que necesitan reabastecerse. Objeto de valor puro:
 * la regla vive en el constructor canónico, no en un factory (mismo patrón que {@link PageRequest}
 * y {@link StockAvailability}: en un record el canónico es público y sería la puerta trasera para
 * construir un umbral inválido). Cero es un umbral válido ("qué se agotó del todo"); solo un valor
 * negativo no representa ninguna cantidad de stock posible.
 */
public record StockThreshold(int value) {

  public static final int DEFAULT = 5;

  public StockThreshold {
    if (value < 0) {
      throw new IllegalArgumentException("El umbral de stock no puede ser negativo");
    }
  }
}
