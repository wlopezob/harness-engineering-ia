package com.gentleman.inventory.domain.model;

/** Una salida de stock dejaría la cantidad del producto en negativo. */
public class InsufficientStockException extends RuntimeException {

  public InsufficientStockException(int available, int delta) {
    super("Stock insuficiente: disponible " + available + ", ajuste solicitado " + delta);
  }
}
