package com.gentleman.inventory.application.usecase;

import com.gentleman.inventory.domain.model.PageRequest;
import com.gentleman.inventory.domain.model.Product;
import com.gentleman.inventory.domain.model.ProductPage;
import com.gentleman.inventory.domain.port.ProductRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * Caso de uso: listar los productos del inventario, paginados. Orquesta el dominio a través del
 * puerto. Sin HTTP ni SQL.
 */
@ApplicationScoped
public class ListProductsUseCase {

  private final ProductRepository repository;

  public ListProductsUseCase(ProductRepository repository) {
    this.repository = repository;
  }

  public ProductPage handle(int page, int size) {
    PageRequest pageRequest = new PageRequest(page, size);
    List<Product> items = repository.findPage(pageRequest);
    long totalElements = repository.countActive();
    return new ProductPage(items, pageRequest.page(), pageRequest.size(), totalElements);
  }
}
