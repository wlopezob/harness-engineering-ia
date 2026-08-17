package com.gentleman.inventory.application.usecase;

import com.gentleman.inventory.domain.model.Product;
import com.gentleman.inventory.domain.model.ProductNotFoundException;
import com.gentleman.inventory.domain.port.ProductRepository;
import jakarta.enterprise.context.ApplicationScoped;

/** Ajusta el stock de un producto existente. La regla del ajuste vive en el dominio. */
@ApplicationScoped
public class AdjustStockUseCase {

  private final ProductRepository repository;

  public AdjustStockUseCase(ProductRepository repository) {
    this.repository = repository;
  }

  public Product handle(Long id, int delta) {
    Product existing = repository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
    return repository.update(existing.adjustStock(delta));
  }
}
