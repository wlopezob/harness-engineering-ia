package com.gentleman.inventory.domain.model;

/**
 * Un ajuste solicitado sobre un producto: delta firmado (positivo entrada, negativo salida). El
 * delta se valida al aplicarlo (Product.adjustStock), donde vive la regla junto a la cantidad.
 */
public record StockAdjustment(Long productId, int delta) {

  public StockAdjustment {
    if (productId == null) {
      throw new IllegalArgumentException("El producto del ajuste es obligatorio");
    }
  }
}
