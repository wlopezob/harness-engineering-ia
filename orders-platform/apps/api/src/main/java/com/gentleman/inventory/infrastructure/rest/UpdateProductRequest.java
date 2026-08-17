package com.gentleman.inventory.infrastructure.rest;

/**
 * Cuerpo de la edición: solo el nombre. El SKU no se edita (es el identificador) y la cantidad
 * tampoco: el stock se mueve en /stock-adjustments, que deja movimiento en el historial. Coincide
 * con el contrato openapi.
 */
public record UpdateProductRequest(String name) {}
