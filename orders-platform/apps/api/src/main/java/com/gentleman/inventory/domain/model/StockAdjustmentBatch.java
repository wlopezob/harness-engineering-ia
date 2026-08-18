package com.gentleman.inventory.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Conjunto de ajustes que se aplican como UNA sola operación. Las reglas viven en el constructor
 * compacto, no en la factory: así ninguna vía de construcción puede crear un lote inválido, y la
 * copia defensiva protege al lote de la lista que le pasaron (SpotBugs EI_EXPOSE_REP). Sin ajustes
 * no hay operación, y un producto repetido haría que dos ajustes del mismo lote se pisaran.
 */
public record StockAdjustmentBatch(List<StockAdjustment> adjustments) {

  public StockAdjustmentBatch {
    if (adjustments == null || adjustments.isEmpty()) {
      throw new IllegalArgumentException("El lote de ajustes no puede estar vacío");
    }

    if (adjustments.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("El lote de ajustes no admite elementos vacíos");
    }

    List<Long> productIds = adjustments.stream().map(StockAdjustment::productId).toList();
    Optional<Long> repeated =
        productIds.stream()
            .filter(id -> productIds.indexOf(id) != productIds.lastIndexOf(id))
            .findFirst();

    if (repeated.isPresent()) {
      throw new IllegalArgumentException(
          "El producto " + repeated.get() + " aparece más de una vez en el lote de ajustes");
    }

    adjustments = List.copyOf(adjustments);
  }

  public static StockAdjustmentBatch of(List<StockAdjustment> adjustments) {
    return new StockAdjustmentBatch(adjustments);
  }
}
