package com.gentleman.inventory.application.usecase;

import com.gentleman.inventory.domain.model.Product;
import com.gentleman.inventory.domain.model.ProductNotFoundException;
import com.gentleman.inventory.domain.model.StockAdjustment;
import com.gentleman.inventory.domain.model.StockAdjustmentBatch;
import com.gentleman.inventory.domain.model.StockMovement;
import com.gentleman.inventory.domain.port.ProductRepository;
import com.gentleman.inventory.domain.port.StockMovementRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

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

  /** Ajuste de un solo producto: es el lote de un elemento, para que la regla no pueda divergir. */
  @Transactional
  public Product handle(Long id, int delta) {
    return handleAll(List.of(new StockAdjustment(id, delta))).getFirst();
  }

  /**
   * Aplica varios ajustes como UNA sola operación: o se aplican todos, o ninguno. Las dos primeras
   * fases no escriben (findById lee; adjustStock es puro), así que un lote rechazado no deja rastro
   * sin depender del rollback; la transacción cubre el fallo que ocurra ya escribiendo.
   */
  @Transactional
  public List<Product> handleAll(List<StockAdjustment> adjustments) {
    StockAdjustmentBatch batch = StockAdjustmentBatch.of(adjustments);

    // 1) cargar: el primer producto ausente rechaza el lote entero
    List<Product> before =
        batch.adjustments().stream()
            .map(
                adjustment ->
                    repository
                        .findById(adjustment.productId())
                        .orElseThrow(() -> new ProductNotFoundException(adjustment.productId())))
            .toList();

    // 2) calcular: delta cero o stock insuficiente rechazan aquí, todavía sin efectos
    List<Product> after =
        IntStream.range(0, before.size())
            .mapToObj(i -> before.get(i).adjustStock(batch.adjustments().get(i).delta()))
            .toList();

    // 3) escribir: desde aquí solo puede fallar la persistencia, y la transacción revierte ambos
    Instant now = clock.instant();

    return IntStream.range(0, before.size())
        .mapToObj(
            i -> {
              Product saved = repository.update(after.get(i));
              movements.save(StockMovement.record(before.get(i), after.get(i), now));
              return saved;
            })
        .toList();
  }
}
