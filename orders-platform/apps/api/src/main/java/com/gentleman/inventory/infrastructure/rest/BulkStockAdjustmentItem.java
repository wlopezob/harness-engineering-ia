package com.gentleman.inventory.infrastructure.rest;

import com.gentleman.inventory.domain.model.StockAdjustment;

/** Un ajuste dentro del lote: producto y delta firmado. Coincide con el contrato openapi. */
public record BulkStockAdjustmentItem(Long productId, int delta) {

  public StockAdjustment toDomain() {
    return new StockAdjustment(productId, delta);
  }
}
