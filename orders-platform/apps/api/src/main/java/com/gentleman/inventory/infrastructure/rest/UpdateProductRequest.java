package com.gentleman.inventory.infrastructure.rest;

/**
 * Cuerpo de la edición: solo nombre y cantidad. El SKU no se edita (es el
 * identificador). Coincide con el contrato openapi.
 */
public record UpdateProductRequest(String name, int quantity) {
}
