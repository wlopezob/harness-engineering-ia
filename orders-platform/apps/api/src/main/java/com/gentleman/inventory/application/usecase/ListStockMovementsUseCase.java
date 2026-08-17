package com.gentleman.inventory.application.usecase;

import com.gentleman.inventory.domain.model.ProductNotFoundException;
import com.gentleman.inventory.domain.model.StockMovement;
import com.gentleman.inventory.domain.port.ProductRepository;
import com.gentleman.inventory.domain.port.StockMovementRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * Caso de uso: consultar el historial de movimientos de stock de un producto (del más reciente al
 * más antiguo). Si el producto no existe (o está eliminado), lanza ProductNotFoundException.
 */
@ApplicationScoped
public class ListStockMovementsUseCase {

  private final ProductRepository products;
  private final StockMovementRepository movements;

  public ListStockMovementsUseCase(ProductRepository products, StockMovementRepository movements) {
    this.products = products;
    this.movements = movements;
  }

  public List<StockMovement> handle(Long productId) {
    products.findById(productId).orElseThrow(() -> new ProductNotFoundException(productId));
    return movements.findByProductId(productId);
  }
}
