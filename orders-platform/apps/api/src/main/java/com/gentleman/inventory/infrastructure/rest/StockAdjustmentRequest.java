package com.gentleman.inventory.infrastructure.rest;

/**
 * Cuerpo del ajuste de stock: delta firmado (positivo = entrada, negativo = salida). Coincide con
 * el contrato openapi.
 */
public record StockAdjustmentRequest(int delta) {}
