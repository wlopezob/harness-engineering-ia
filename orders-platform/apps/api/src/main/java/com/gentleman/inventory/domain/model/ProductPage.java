package com.gentleman.inventory.domain.model;

import java.util.List;

/**
 * Resultado de listar productos paginados. Objeto de valor puro: describe una consulta, no cambia
 * nada. {@code totalPages} y {@code hasNext} se derivan de {@code totalElements}/{@code page}/
 * {@code size}, no se guardan como estado independiente (un {@code hasNext=true} en la última
 * página sería un estado incoherente e irrepresentable). La copia defensiva de {@code items}
 * protege a la página de la lista que le pasaron (SpotBugs EI_EXPOSE_REP), igual que {@link
 * StockAdjustmentBatch}.
 */
public record ProductPage(List<Product> items, int page, int size, long totalElements) {

  public ProductPage {
    items = List.copyOf(items);
  }

  public boolean hasNext() {
    return ((long) page + 1) * size < totalElements;
  }

  public int totalPages() {
    return (int) Math.ceil(totalElements / (double) size);
  }
}
