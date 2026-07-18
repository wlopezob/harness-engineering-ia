package com.gentleman.inventory.application.usecase;

import com.gentleman.inventory.domain.model.DuplicateSkuException;
import com.gentleman.inventory.domain.model.Product;
import com.gentleman.inventory.domain.port.ProductRepository;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Caso de uso: registrar un producto en el inventario. Orquesta el dominio a través del puerto. Sin
 * HTTP ni SQL.
 */
@ApplicationScoped
public class CreateProductUseCase {

  private final ProductRepository repository;

  public CreateProductUseCase(ProductRepository repository) {
    this.repository = repository;
  }

  public Product handle(String name, String sku, int quantity) {
    Product product = Product.create(name, sku, quantity); // valida reglas de dominio
    if (repository.existsBySku(product.sku())) {
      throw new DuplicateSkuException(product.sku());
    }
    return repository.save(product);
  }
}
