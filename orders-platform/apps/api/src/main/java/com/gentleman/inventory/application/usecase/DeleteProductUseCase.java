package com.gentleman.inventory.application.usecase;

import com.gentleman.inventory.domain.model.Product;
import com.gentleman.inventory.domain.model.ProductNotFoundException;
import com.gentleman.inventory.domain.port.ProductRepository;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Caso de uso: eliminar un producto del inventario. Es un borrado lógico: el producto pasa a
 * DELETED y su fila e historial permanecen. Orquesta el dominio vía el puerto. Sin HTTP ni SQL.
 */
@ApplicationScoped
public class DeleteProductUseCase {

  private final ProductRepository repository;

  public DeleteProductUseCase(ProductRepository repository) {
    this.repository = repository;
  }

  public void handle(Long id) {
    Product existing = repository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
    repository.update(existing.markDeleted());
  }
}
