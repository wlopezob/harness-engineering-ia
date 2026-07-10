package com.gentleman.inventory.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Entidad JPA. Vive en infraestructura: el modelo de dominio (Product) no puede
 * llevar anotaciones jakarta (lo prohíbe ArchitectureTest).
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

    protected ProductEntity() {
        // requerido por JPA
    }

    public ProductEntity(String name, String sku, int quantity) {
        this.name = name;
        this.sku = sku;
        this.quantity = quantity;
    }
}
