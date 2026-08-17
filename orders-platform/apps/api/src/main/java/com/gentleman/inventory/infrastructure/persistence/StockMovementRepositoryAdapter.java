package com.gentleman.inventory.infrastructure.persistence;

import com.gentleman.inventory.domain.model.StockMovement;
import com.gentleman.inventory.domain.port.StockMovementRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;

/**
 * Adapter de persistencia del historial de stock: implementa el puerto delegando en Panache y
 * mapeando StockMovement (record) ↔ StockMovementEntity (JPA). Los tipos JPA no salen de aquí.
 */
@ApplicationScoped
public class StockMovementRepositoryAdapter implements StockMovementRepository {

  private final StockMovementPanacheRepository movements;

  public StockMovementRepositoryAdapter(StockMovementPanacheRepository movements) {
    this.movements = movements;
  }

  @Override
  @Transactional
  public StockMovement save(StockMovement movement) {
    StockMovementEntity entity =
        new StockMovementEntity(
            movement.productId(),
            movement.delta(),
            movement.previousQuantity(),
            movement.resultingQuantity(),
            movement.occurredAt());
    movements.persist(entity); // IDENTITY → el INSERT asigna el id
    return toDomain(entity);
  }

  @Override
  @Transactional
  public List<StockMovement> findByProductId(Long productId) {
    // más reciente primero; id desc desempata movimientos en el mismo instante
    return movements.list("productId", Sort.descending("occurredAt", "id"), productId).stream()
        .map(this::toDomain)
        .toList();
  }

  private StockMovement toDomain(StockMovementEntity entity) {
    return new StockMovement(
        entity.id,
        entity.productId,
        entity.delta,
        entity.previousQuantity,
        entity.resultingQuantity,
        entity.occurredAt);
  }
}
