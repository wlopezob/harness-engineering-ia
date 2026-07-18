package com.gentleman.inventory.application.usecase;

import com.gentleman.inventory.domain.model.ProductNotFoundException;
import com.gentleman.inventory.domain.port.ProductRepository;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Caso de uso: eliminar un producto del inventario. Orquesta el dominio vía el puerto. Sin HTTP ni
 * SQL.
 */
@ApplicationScoped
public class DeleteProductUseCase {

  private final ProductRepository repository;

  public DeleteProductUseCase(ProductRepository repository) {
    this.repository = repository;
  }

  public void handle(Long id) {
    if (!repository.deleteById(id)) {
      throw new ProductNotFoundException(id);
    }
  }
}
