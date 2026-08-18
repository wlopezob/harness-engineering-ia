package com.gentleman.inventory.infrastructure.rest;

import com.gentleman.inventory.domain.model.StockAdjustment;
import java.util.List;

/**
 * Cuerpo del ajuste de varios productos. Coincide con el contrato openapi. El constructor copia la
 * lista (defensa contra la exposición de estado mutable) y normaliza los huecos del cliente sin
 * decidir nada: un cuerpo sin "adjustments" queda como lote vacío y un elemento nulo queda como un
 * ajuste sin producto. Quien los rechaza es el dominio, para que la regla sea una sola.
 */
public record BulkStockAdjustmentRequest(List<BulkStockAdjustmentItem> adjustments) {

  private static final BulkStockAdjustmentItem SIN_AJUSTE = new BulkStockAdjustmentItem(null, 0);

  public BulkStockAdjustmentRequest {
    adjustments =
        adjustments == null
            ? List.of()
            : List.copyOf(
                adjustments.stream().map(item -> item == null ? SIN_AJUSTE : item).toList());
  }

  public List<StockAdjustment> toDomain() {
    return adjustments.stream().map(BulkStockAdjustmentItem::toDomain).toList();
  }
}
