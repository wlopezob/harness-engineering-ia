package com.gentleman.inventory.application.usecase;

import com.gentleman.inventory.domain.model.Product;
import com.gentleman.inventory.domain.port.ProductRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * Caso de uso: listar todos los productos del inventario. Orquesta el dominio a través del puerto.
 * Sin HTTP ni SQL.
 */
@ApplicationScoped
public class ListProductsUseCase {

  private final ProductRepository repository;

  public ListProductsUseCase(ProductRepository repository) {
    this.repository = repository;
  }

  public List<Product> handle() {
    return repository.findAll();
  }
}
