package com.gentleman.inventory.domain.model;

/**
 * Parámetros de paginación de una consulta de listado. Objeto de valor puro: la regla vive en el
 * constructor canónico, no en un factory (lección D-029: en un record el canónico es público y
 * sería la puerta trasera para construir una página inválida).
 */
public record PageRequest(int page, int size) {

  public static final int DEFAULT_SIZE = 20;
  public static final int MAX_SIZE = 100;

  public PageRequest {
    if (page < 0) {
      throw new IllegalArgumentException("La página no puede ser negativa");
    }
    if (size <= 0) {
      throw new IllegalArgumentException("El tamaño de página debe ser mayor que cero");
    }
    if (size > MAX_SIZE) {
      throw new IllegalArgumentException("El tamaño de página no puede superar " + MAX_SIZE);
    }
  }
}
