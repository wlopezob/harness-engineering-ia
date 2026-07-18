package com.gentleman.inventory.infrastructure.persistence;

import com.gentleman.inventory.domain.model.Product;
import com.gentleman.inventory.domain.port.ProductRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Optional;

/**
 * Adapter de persistencia: implementa el puerto del dominio delegando en Panache (repository
 * pattern) y mapeando entre Product (POJO) y ProductEntity (JPA). Los tipos Panache/JPA no salen de
 * aquí.
 */
@ApplicationScoped
public class ProductRepositoryAdapter implements ProductRepository {

  private final ProductPanacheRepository products;

  public ProductRepositoryAdapter(ProductPanacheRepository products) {
    this.products = products;
  }

  @Override
  @Transactional
  public Product save(Product product) {
    ProductEntity entity = new ProductEntity(product.name(), product.sku(), product.quantity());
    products.persist(entity); // IDENTITY → el INSERT asigna el id
    return toDomain(entity);
  }

  @Override
  @Transactional
  public boolean existsBySku(String sku) {
    return products.count("sku", sku) > 0;
  }

  @Override
  @Transactional
  public List<Product> findAll() {
    return products.listAll(Sort.by("id")).stream().map(this::toDomain).toList();
  }

  @Override
  @Transactional
  public Optional<Product> findById(Long id) {
    return products.findByIdOptional(id).map(this::toDomain);
  }

  @Override
  @Transactional
  public Product update(Product product) {
    ProductEntity entity = products.findById(product.id());
    entity.name = product.name();
    entity.quantity = product.quantity();
    // el SKU no se cambia; dirty checking persiste al cerrar la transacción
    return toDomain(entity);
  }

  @Override
  @Transactional
  public boolean deleteById(Long id) {
    return products.deleteById(id);
  }

  private Product toDomain(ProductEntity entity) {
    return Product.restore(entity.id, entity.name, entity.sku, entity.quantity);
  }
}
