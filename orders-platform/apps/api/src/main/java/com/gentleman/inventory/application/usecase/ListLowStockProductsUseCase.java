package com.gentleman.inventory.application.usecase;

import com.gentleman.inventory.domain.model.Product;
import com.gentleman.inventory.domain.model.StockThreshold;
import com.gentleman.inventory.domain.port.ProductRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * Caso de uso: listar los productos activos cuyo stock está en o por debajo de un umbral. Consulta
 * pura: no ajusta stock ni genera movimientos. Orquesta el dominio a través del puerto. Sin HTTP ni
 * SQL.
 */
@ApplicationScoped
public class ListLowStockProductsUseCase {

  private final ProductRepository repository;

  public ListLowStockProductsUseCase(ProductRepository repository) {
    this.repository = repository;
  }

  public List<Product> handle(int threshold) {
    StockThreshold stockThreshold = new StockThreshold(threshold);
    return repository.findBelowOrEqualThreshold(stockThreshold);
  }
}
