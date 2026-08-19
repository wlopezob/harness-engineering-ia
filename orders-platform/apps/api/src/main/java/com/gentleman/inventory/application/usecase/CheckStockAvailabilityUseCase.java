package com.gentleman.inventory.application.usecase;

import com.gentleman.inventory.domain.model.ProductNotFoundException;
import com.gentleman.inventory.domain.model.StockAvailability;
import com.gentleman.inventory.domain.port.ProductRepository;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Caso de uso: consultar si un producto puede cubrir una cantidad solicitada. Solo lee: resuelve el
 * producto por el puerto y delega la regla en el dominio. Sin @Transactional porque no escribe por
 * ninguna rama.
 */
@ApplicationScoped
public class CheckStockAvailabilityUseCase {

  private final ProductRepository repository;

  public CheckStockAvailabilityUseCase(ProductRepository repository) {
    this.repository = repository;
  }

  public StockAvailability handle(Long id, int requestedQuantity) {
    return repository
        .findById(id)
        .orElseThrow(() -> new ProductNotFoundException(id))
        .checkAvailability(requestedQuantity);
  }
}
