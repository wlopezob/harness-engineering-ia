package com.gentleman.inventory.infrastructure.rest;

import com.gentleman.inventory.domain.model.Product;

/** Representación HTTP de un producto. Coincide con el contrato openapi. */
public record ProductResponse(Long id, String name, String sku, int quantity) {

  public static ProductResponse from(Product product) {
    return new ProductResponse(product.id(), product.name(), product.sku(), product.quantity());
  }
}
