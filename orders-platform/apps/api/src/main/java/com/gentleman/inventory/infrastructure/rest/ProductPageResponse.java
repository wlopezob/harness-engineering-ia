package com.gentleman.inventory.infrastructure.rest;

import com.gentleman.inventory.domain.model.ProductPage;
import java.util.List;

/**
 * Representación HTTP de una página de productos. Coincide con el contrato openapi. totalPages y
 * hasNext se derivan en el dominio: aquí solo se copian.
 */
public record ProductPageResponse(
    List<ProductResponse> items,
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean hasNext) {

  public ProductPageResponse {
    items = List.copyOf(items);
  }

  public static ProductPageResponse from(ProductPage page) {
    return new ProductPageResponse(
        page.items().stream().map(ProductResponse::from).toList(),
        page.page(),
        page.size(),
        page.totalElements(),
        page.totalPages(),
        page.hasNext());
  }
}
