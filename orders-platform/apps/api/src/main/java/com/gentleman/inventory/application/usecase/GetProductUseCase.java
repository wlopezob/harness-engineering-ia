package com.gentleman.inventory.application.usecase;

import com.gentleman.inventory.domain.model.Product;
import com.gentleman.inventory.domain.model.ProductNotFoundException;
import com.gentleman.inventory.domain.port.ProductRepository;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Caso de uso: consultar un producto por su id.
 * Orquesta el dominio a través del puerto. Sin HTTP ni SQL.
 */
@ApplicationScoped
public class GetProductUseCase {

    private final ProductRepository repository;

    public GetProductUseCase(ProductRepository repository) {
        this.repository = repository;
    }

    public Product handle(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }
}
