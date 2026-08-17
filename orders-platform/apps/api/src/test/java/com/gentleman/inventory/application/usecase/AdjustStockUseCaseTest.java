package com.gentleman.inventory.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gentleman.inventory.domain.model.InsufficientStockException;
import com.gentleman.inventory.domain.model.Product;
import com.gentleman.inventory.domain.model.ProductNotFoundException;
import com.gentleman.inventory.domain.model.ProductStatus;
import com.gentleman.inventory.domain.model.StockMovement;
import com.gentleman.inventory.domain.port.ProductRepository;
import com.gentleman.inventory.domain.port.StockMovementRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Doble del puerto con Mockito (HARNESS D), mocks locales (HARNESS C). */
class AdjustStockUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-08-17T15:04:05Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void handle_ajusta_el_stock_y_persiste_el_resultado() {
    ProductRepository repository = mock(ProductRepository.class);
    StockMovementRepository movements = mock(StockMovementRepository.class);
    AdjustStockUseCase useCase = new AdjustStockUseCase(repository, movements, CLOCK);
    when(repository.findById(1L))
        .thenReturn(
            Optional.of(Product.restore(1L, "Teclado", "KEY-001", 10, ProductStatus.ACTIVE)));
    when(repository.update(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

    Product result = useCase.handle(1L, 5);

    assertEquals(15, result.quantity());
    assertEquals("KEY-001", result.sku(), "el SKU no cambia");
    verify(repository).update(any(Product.class));
  }

  @Test
  void handle_registra_un_movimiento_con_las_cantidades_y_el_instante_del_reloj() {
    ProductRepository repository = mock(ProductRepository.class);
    StockMovementRepository movements = mock(StockMovementRepository.class);
    AdjustStockUseCase useCase = new AdjustStockUseCase(repository, movements, CLOCK);
    when(repository.findById(1L))
        .thenReturn(
            Optional.of(Product.restore(1L, "Teclado", "KEY-001", 10, ProductStatus.ACTIVE)));
    when(repository.update(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

    useCase.handle(1L, -3);

    ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);
    verify(movements).save(captor.capture());
    StockMovement movement = captor.getValue();
    assertNull(movement.id());
    assertEquals(1L, movement.productId());
    assertEquals(-3, movement.delta());
    assertEquals(10, movement.previousQuantity());
    assertEquals(7, movement.resultingQuantity());
    assertEquals(NOW, movement.occurredAt());
  }

  @Test
  void handle_lanza_excepcion_cuando_el_producto_no_existe() {
    ProductRepository repository = mock(ProductRepository.class);
    StockMovementRepository movements = mock(StockMovementRepository.class);
    AdjustStockUseCase useCase = new AdjustStockUseCase(repository, movements, CLOCK);
    when(repository.findById(99L)).thenReturn(Optional.empty());

    assertThrows(ProductNotFoundException.class, () -> useCase.handle(99L, 5));
    verify(repository, never()).update(any(Product.class));
    verify(movements, never()).save(any(StockMovement.class));
  }

  @Test
  void handle_no_persiste_ni_registra_movimiento_cuando_el_dominio_rechaza_el_ajuste() {
    ProductRepository repository = mock(ProductRepository.class);
    StockMovementRepository movements = mock(StockMovementRepository.class);
    AdjustStockUseCase useCase = new AdjustStockUseCase(repository, movements, CLOCK);
    when(repository.findById(1L))
        .thenReturn(
            Optional.of(Product.restore(1L, "Teclado", "KEY-001", 10, ProductStatus.ACTIVE)));

    assertThrows(InsufficientStockException.class, () -> useCase.handle(1L, -11));

    verify(repository, never()).update(any(Product.class));
    verify(movements, never()).save(any(StockMovement.class));
  }
}
