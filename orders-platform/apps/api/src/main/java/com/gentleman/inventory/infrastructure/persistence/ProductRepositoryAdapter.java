package com.gentleman.inventory.infrastructure.persistence;

import com.gentleman.inventory.domain.model.PageRequest;
import com.gentleman.inventory.domain.model.Product;
import com.gentleman.inventory.domain.model.ProductStatus;
import com.gentleman.inventory.domain.model.StockThreshold;
import com.gentleman.inventory.domain.port.ProductRepository;
import io.quarkus.panache.common.Page;
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
    ProductEntity entity =
        new ProductEntity(product.name(), product.sku(), product.quantity(), product.status());
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
  public List<Product> findPage(PageRequest pageRequest) {
    return products
        .find("status", Sort.by("id"), ProductStatus.ACTIVE)
        .page(Page.of(pageRequest.page(), pageRequest.size()))
        .list()
        .stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  @Transactional
  public long countActive() {
    return products.count("status", ProductStatus.ACTIVE);
  }

  @Override
  @Transactional
  public List<Product> findBelowOrEqualThreshold(StockThreshold threshold) {
    return products
        .find(
            "status = ?1 and quantity <= ?2",
            Sort.by("id"),
            ProductStatus.ACTIVE,
            threshold.value())
        .list()
        .stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  @Transactional
  public Optional<Product> findById(Long id) {
    return products
        .find("id = ?1 and status = ?2", id, ProductStatus.ACTIVE)
        .firstResultOptional()
        .map(this::toDomain);
  }

  @Override
  @Transactional
  public Product update(Product product) {
    ProductEntity entity = products.findById(product.id());
    entity.name = product.name();
    entity.quantity = product.quantity();
    entity.status = product.status();
    // el SKU no se cambia; dirty checking persiste al cerrar la transacción
    return toDomain(entity);
  }

  private Product toDomain(ProductEntity entity) {
    return Product.restore(entity.id, entity.name, entity.sku, entity.quantity, entity.status);
  }
}
