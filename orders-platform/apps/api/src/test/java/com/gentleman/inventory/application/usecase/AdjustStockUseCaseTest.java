package com.gentleman.inventory.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gentleman.inventory.domain.model.InsufficientStockException;
import com.gentleman.inventory.domain.model.Product;
import com.gentleman.inventory.domain.model.ProductNotFoundException;
import com.gentleman.inventory.domain.model.ProductStatus;
import com.gentleman.inventory.domain.model.StockAdjustment;
import com.gentleman.inventory.domain.model.StockMovement;
import com.gentleman.inventory.domain.port.ProductRepository;
import com.gentleman.inventory.domain.port.StockMovementRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Doble del puerto con Mockito (HARNESS D), mocks locales (HARNESS C). */
class AdjustStockUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-08-17T15:04:05Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  /**
   * Reloj que avanza un segundo en CADA lectura: si el lote leyera la hora una vez por ajuste, los
   * movimientos saldrían con instantes distintos y el test lo delataría.
   */
  private static final class TickingClock extends Clock {

    private Instant current = NOW;

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      Instant reading = current;
      current = current.plusSeconds(1);
      return reading;
    }
  }

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

  @Test
  void handleAll_ajusta_varios_productos_y_devuelve_las_cantidades_en_el_orden_de_la_peticion() {
    ProductRepository repository = mock(ProductRepository.class);
    StockMovementRepository movements = mock(StockMovementRepository.class);
    AdjustStockUseCase useCase = new AdjustStockUseCase(repository, movements, CLOCK);
    when(repository.findById(1L))
        .thenReturn(
            Optional.of(Product.restore(1L, "Teclado", "KEY-001", 10, ProductStatus.ACTIVE)));
    when(repository.findById(2L))
        .thenReturn(Optional.of(Product.restore(2L, "Mouse", "MOU-001", 4, ProductStatus.ACTIVE)));
    when(repository.update(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

    List<Product> result =
        useCase.handleAll(List.of(new StockAdjustment(1L, 5), new StockAdjustment(2L, -3)));

    assertEquals(List.of(15, 1), result.stream().map(Product::quantity).toList());
    assertEquals(List.of("KEY-001", "MOU-001"), result.stream().map(Product::sku).toList());
  }

  @Test
  void handleAll_registra_un_movimiento_por_ajuste_con_el_mismo_instante() {
    ProductRepository repository = mock(ProductRepository.class);
    StockMovementRepository movements = mock(StockMovementRepository.class);
    AdjustStockUseCase useCase = new AdjustStockUseCase(repository, movements, new TickingClock());
    when(repository.findById(1L))
        .thenReturn(
            Optional.of(Product.restore(1L, "Teclado", "KEY-001", 10, ProductStatus.ACTIVE)));
    when(repository.findById(2L))
        .thenReturn(Optional.of(Product.restore(2L, "Mouse", "MOU-001", 4, ProductStatus.ACTIVE)));
    when(repository.update(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

    useCase.handleAll(List.of(new StockAdjustment(1L, 5), new StockAdjustment(2L, -3)));

    ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);
    verify(movements, times(2)).save(captor.capture());
    List<StockMovement> saved = captor.getAllValues();
    assertEquals(List.of(1L, 2L), saved.stream().map(StockMovement::productId).toList());
    assertEquals(List.of(5, -3), saved.stream().map(StockMovement::delta).toList());
    assertEquals(List.of(10, 4), saved.stream().map(StockMovement::previousQuantity).toList());
    assertEquals(List.of(15, 1), saved.stream().map(StockMovement::resultingQuantity).toList());
    assertEquals(List.of(NOW, NOW), saved.stream().map(StockMovement::occurredAt).toList());
  }

  @Test
  void handleAll_no_escribe_nada_cuando_un_producto_del_lote_no_existe() {
    ProductRepository repository = mock(ProductRepository.class);
    StockMovementRepository movements = mock(StockMovementRepository.class);
    AdjustStockUseCase useCase = new AdjustStockUseCase(repository, movements, CLOCK);
    when(repository.findById(1L))
        .thenReturn(
            Optional.of(Product.restore(1L, "Teclado", "KEY-001", 10, ProductStatus.ACTIVE)));
    when(repository.findById(99L)).thenReturn(Optional.empty());
    List<StockAdjustment> lote = List.of(new StockAdjustment(1L, 5), new StockAdjustment(99L, -3));

    assertThrows(ProductNotFoundException.class, () -> useCase.handleAll(lote));

    verify(repository, never()).update(any(Product.class));
    verify(movements, never()).save(any(StockMovement.class));
  }

  @Test
  void handleAll_no_escribe_nada_cuando_un_ajuste_dejaria_el_stock_negativo() {
    ProductRepository repository = mock(ProductRepository.class);
    StockMovementRepository movements = mock(StockMovementRepository.class);
    AdjustStockUseCase useCase = new AdjustStockUseCase(repository, movements, CLOCK);
    when(repository.findById(1L))
        .thenReturn(
            Optional.of(Product.restore(1L, "Teclado", "KEY-001", 10, ProductStatus.ACTIVE)));
    when(repository.findById(2L))
        .thenReturn(Optional.of(Product.restore(2L, "Mouse", "MOU-001", 4, ProductStatus.ACTIVE)));
    List<StockAdjustment> lote = List.of(new StockAdjustment(1L, 5), new StockAdjustment(2L, -5));

    assertThrows(InsufficientStockException.class, () -> useCase.handleAll(lote));

    verify(repository, never()).update(any(Product.class));
    verify(movements, never()).save(any(StockMovement.class));
  }

  @Test
  void handleAll_no_escribe_nada_cuando_un_ajuste_tiene_delta_cero() {
    ProductRepository repository = mock(ProductRepository.class);
    StockMovementRepository movements = mock(StockMovementRepository.class);
    AdjustStockUseCase useCase = new AdjustStockUseCase(repository, movements, CLOCK);
    when(repository.findById(1L))
        .thenReturn(
            Optional.of(Product.restore(1L, "Teclado", "KEY-001", 10, ProductStatus.ACTIVE)));
    when(repository.findById(2L))
        .thenReturn(Optional.of(Product.restore(2L, "Mouse", "MOU-001", 4, ProductStatus.ACTIVE)));
    List<StockAdjustment> lote = List.of(new StockAdjustment(1L, 5), new StockAdjustment(2L, 0));

    assertThrows(IllegalArgumentException.class, () -> useCase.handleAll(lote));

    verify(repository, never()).update(any(Product.class));
    verify(movements, never()).save(any(StockMovement.class));
  }
}
