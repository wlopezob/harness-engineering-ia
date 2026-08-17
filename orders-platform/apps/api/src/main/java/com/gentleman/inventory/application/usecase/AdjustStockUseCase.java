package com.gentleman.inventory.application.usecase;

import com.gentleman.inventory.domain.model.Product;
import com.gentleman.inventory.domain.model.ProductNotFoundException;
import com.gentleman.inventory.domain.model.StockMovement;
import com.gentleman.inventory.domain.port.ProductRepository;
import com.gentleman.inventory.domain.port.StockMovementRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Clock;

/**
 * Ajusta el stock de un producto existente y registra el movimiento resultante. La regla del ajuste
 * vive en el dominio; un ajuste rechazado lanza antes de persistir nada. El ajuste y el movimiento
 * son atómicos: handle abre la transacción y los adapters se unen a ella (REQUIRED); cualquier
 * RuntimeException revierte ambos.
 */
@ApplicationScoped
public class AdjustStockUseCase {

  private final ProductRepository repository;
  private final StockMovementRepository movements;
  private final Clock clock;

  public AdjustStockUseCase(
      ProductRepository repository, StockMovementRepository movements, Clock clock) {
    this.repository = repository;
    this.movements = movements;
    this.clock = clock;
  }

  @Transactional
  public Product handle(Long id, int delta) {
    Product existing = repository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
    Product adjusted = existing.adjustStock(delta);
    Product saved = repository.update(adjusted);
    movements.save(StockMovement.record(existing, adjusted, clock.instant()));
    return saved;
  }
}
