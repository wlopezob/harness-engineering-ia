package com.gentleman.inventory.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Entidad JPA del movimiento de stock. Vive en infraestructura: el modelo de dominio
 * (StockMovement) no puede llevar anotaciones jakarta (lo prohíbe ArchitectureTest).
 */
@Entity
@Table(name = "stock_movement")
public class StockMovementEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "product_id", nullable = false)
  public Long productId;

  @Column(nullable = false)
  public int delta;

  @Column(name = "previous_quantity", nullable = false)
  public int previousQuantity;

  @Column(name = "resulting_quantity", nullable = false)
  public int resultingQuantity;

  @Column(name = "occurred_at", nullable = false)
  public Instant occurredAt;

  protected StockMovementEntity() {
    // requerido por JPA
  }

  public StockMovementEntity(
      Long productId, int delta, int previousQuantity, int resultingQuantity, Instant occurredAt) {
    this.productId = productId;
    this.delta = delta;
    this.previousQuantity = previousQuantity;
    this.resultingQuantity = resultingQuantity;
    this.occurredAt = occurredAt;
  }
}
