package com.gentleman.inventory.application.usecase;

import com.gentleman.inventory.domain.model.Product;
import com.gentleman.inventory.domain.model.ProductNotFoundException;
import com.gentleman.inventory.domain.port.ProductRepository;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Caso de uso: editar el nombre de un producto existente. El SKU no se cambia y la cantidad
 * tampoco: el stock solo se mueve con un ajuste, que deja movimiento en el historial. Orquesta el
 * dominio vía el puerto. Sin HTTP ni SQL.
 */
@ApplicationScoped
public class UpdateProductUseCase {

  private final ProductRepository repository;

  public UpdateProductUseCase(ProductRepository repository) {
    this.repository = repository;
  }

  public Product handle(Long id, String name) {
    Product existing = repository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
    return repository.update(existing.update(name));
  }
}
