package com.gentleman.inventory.infrastructure.rest;

/** Cuerpo de la petición de creación. Coincide con el contrato openapi. */
public record CreateProductRequest(String name, String sku, int quantity) {
}
