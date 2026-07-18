package com.gentleman.inventory.application.usecase;

import com.gentleman.inventory.domain.model.Product;
import com.gentleman.inventory.domain.model.ProductNotFoundException;
import com.gentleman.inventory.domain.port.ProductRepository;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Caso de uso: editar el nombre y la cantidad de un producto existente. El SKU no se cambia.
 * Orquesta el dominio vía el puerto. Sin HTTP ni SQL.
 */
@ApplicationScoped
public class UpdateProductUseCase {

  private final ProductRepository repository;

  public UpdateProductUseCase(ProductRepository repository) {
    this.repository = repository;
  }

  public Product handle(Long id, String name, int quantity) {
    Product existing = repository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
    return repository.update(existing.update(name, quantity));
  }
}
