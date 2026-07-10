package com.gentleman.inventory.domain.model;

/**
 * Producto del inventario. POJO de dominio puro: cero framework
 * (lo vigila ArchitectureTest). El id lo asigna la persistencia.
 */
public final class Product {

    private final Long id;
    private final String name;
    private final String sku;
    private final int quantity;

    private Product(Long id, String name, String sku, int quantity) {
        this.id = id;
        this.name = name;
        this.sku = sku;
        this.quantity = quantity;
    }

    /** Crea un producto nuevo (aún sin id). Valida las reglas de inventario. */
    public static Product create(String name, String sku, int quantity) {
        String validName = requireName(name);
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("El SKU es obligatorio");
        }
        return new Product(null, validName, sku.trim(), requireQuantity(quantity));
    }

    /** Reconstituye un producto existente (ya persistido, con id). */
    public static Product restore(Long id, String name, String sku, int quantity) {
        return new Product(id, name, sku, quantity);
    }

    /**
     * Devuelve un NUEVO producto con el nombre y la cantidad editados, conservando
     * id y SKU (el SKU es el identificador, no se cambia). Inmutable: no muta this.
     */
    public Product update(String name, int quantity) {
        return new Product(this.id, requireName(name), this.sku, requireQuantity(quantity));
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
}
