package com.gentleman.inventory.domain.model;

/**
 * Producto del inventario. POJO de dominio puro: cero framework (lo vigila ArchitectureTest). El id
 * lo asigna la persistencia.
 */
public final class Product {

  private final Long id;
  private final String name;
  private final String sku;
  private final int quantity;
  private final ProductStatus status;

  private Product(Long id, String name, String sku, int quantity, ProductStatus status) {
    this.id = id;
    this.name = name;
    this.sku = sku;
    this.quantity = quantity;
    this.status = status;
  }

  /** Crea un producto nuevo (aún sin id). Valida las reglas de inventario. */
  public static Product create(String name, String sku, int quantity) {
    String validName = requireName(name);
    if (sku == null || sku.isBlank()) {
      throw new IllegalArgumentException("El SKU es obligatorio");
    }
    return new Product(
        null, validName, sku.trim(), requireQuantity(quantity), ProductStatus.ACTIVE);
  }

  /** Reconstituye un producto existente (ya persistido, con id y estado). */
  public static Product restore(
      Long id, String name, String sku, int quantity, ProductStatus status) {
    return new Product(id, name, sku, quantity, status);
  }

  /**
   * Devuelve un NUEVO producto con el nombre editado, conservando id, SKU y cantidad. El SKU es el
   * identificador y no se cambia; la cantidad SOLO se mueve con adjustStock, que deja movimiento en
   * el historial. Inmutable: no muta this.
   */
  public Product update(String name) {
    return new Product(this.id, requireName(name), this.sku, this.quantity, this.status);
  }

  /**
   * Devuelve un NUEVO producto con el stock ajustado por delta (positivo = entrada, negativo =
   * salida), conservando id, nombre y SKU.
   */
  public Product adjustStock(int delta) {
    if (delta == 0) {
      throw new IllegalArgumentException("El ajuste de stock no puede ser cero");
    }
    long adjusted = (long) this.quantity + delta; // en long: int desbordaría a negativo
    if (adjusted > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("El ajuste excede la cantidad máxima de un producto");
    }
    if (adjusted < 0) {
      throw new InsufficientStockException(this.quantity, delta);
    }
    return new Product(this.id, this.name, this.sku, (int) adjusted, this.status);
  }

  /**
   * Consulta si este producto puede cubrir la cantidad solicitada. Es una consulta pura: no cambia
   * el stock ni devuelve un producto nuevo, sino el resultado de la consulta.
   */
  public StockAvailability checkAvailability(int requestedQuantity) {
    return new StockAvailability(this.id, requestedQuantity, this.quantity);
  }

  /**
   * Devuelve un NUEVO producto marcado como eliminado, conservando todos sus datos. El borrado es
   * un cambio de estado, no una desaparición: la fila y su historial permanecen.
   */
  public Product markDeleted() {
    return new Product(this.id, this.name, this.sku, this.quantity, ProductStatus.DELETED);
  }

  private static String requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("El nombre es obligatorio");
    }
    return name.trim();
  }

  private static int requireQuantity(int quantity) {
    if (quantity < 0) {
      throw new IllegalArgumentException("La cantidad no puede ser negativa");
    }
    return quantity;
  }

  public Long id() {
    return id;
  }

  public String name() {
    return name;
  }

  public String sku() {
    return sku;
  }

  public int quantity() {
    return quantity;
  }

  public ProductStatus status() {
    return status;
  }
}
