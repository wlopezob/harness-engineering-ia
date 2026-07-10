package com.gentleman.inventory.infrastructure.persistence;

import io.quarkus.hibernate.orm.panache.PanacheRepository;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Repositorio Panache (repository pattern) sobre ProductEntity: aporta el CRUD.
 * No cruza fuera de infrastructure (HARNESS E).
 */
@ApplicationScoped
public class ProductPanacheRepository implements PanacheRepository<ProductEntity> {
}
