package com.gentleman.inventory.domain.model;

/**
 * No existe un producto con el id solicitado.
 */
public class ProductNotFoundException extends RuntimeException {

    private final Long id;

    public ProductNotFoundException(Long id) {
        super("No existe un producto con id: " + id);
        this.id = id;
    }

    public Long id() {
        return id;
    }
}
