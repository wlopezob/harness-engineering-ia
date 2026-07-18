package com.gentleman.inventory.domain.model;

/** Se intentó registrar un producto con un SKU que ya existe en el inventario. */
public class DuplicateSkuException extends RuntimeException {

  private final String sku;

  public DuplicateSkuException(String sku) {
    super("Ya existe un producto con el SKU: " + sku);
    this.sku = sku;
  }

  public String sku() {
    return sku;
  }
}
