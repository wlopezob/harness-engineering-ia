package com.gentleman.inventory.infrastructure.persistence;

import com.gentleman.inventory.domain.model.ProductStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Entidad JPA. Vive en infraestructura: el modelo de dominio (Product) no puede llevar anotaciones
 * jakarta (lo prohíbe ArchitectureTest).
 */
@Entity
@Table(name = "product")
public class ProductEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(nullable = false)
  public String name;

  @Column(nullable = false, unique = true)
  public String sku;

  @Column(nullable = false)
  public int quantity;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  public ProductStatus status;

  protected ProductEntity() {
    // requerido por JPA
  }

  public ProductEntity(String name, String sku, int quantity, ProductStatus status) {
    this.name = name;
    this.sku = sku;
    this.quantity = quantity;
    this.status = status;
  }
}
