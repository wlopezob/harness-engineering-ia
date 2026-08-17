package com.gentleman.inventory.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gentleman.inventory.domain.model.Product;
import com.gentleman.inventory.domain.model.ProductNotFoundException;
import com.gentleman.inventory.domain.model.ProductStatus;
import com.gentleman.inventory.domain.model.StockMovement;
import com.gentleman.inventory.domain.port.ProductRepository;
import com.gentleman.inventory.domain.port.StockMovementRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Doble del puerto con Mockito (HARNESS D), mocks locales (HARNESS C). */
class ListStockMovementsUseCaseTest {

  private static final Instant AT = Instant.parse("2026-08-17T15:04:05Z");

  @Test
  void handle_devuelve_el_historial_del_producto_existente() {
    ProductRepository products = mock(ProductRepository.class);
    StockMovementRepository movements = mock(StockMovementRepository.class);
    ListStockMovementsUseCase useCase = new ListStockMovementsUseCase(products, movements);
    when(products.findById(1L))
        .thenReturn(
            Optional.of(Product.restore(1L, "Teclado", "KEY-001", 12, ProductStatus.ACTIVE)));
    List<StockMovement> history =
        List.of(
            new StockMovement(2L, 1L, -3, 15, 12, AT.plusSeconds(60)),
            new StockMovement(1L, 1L, 5, 10, 15, AT));
    when(movements.findByProductId(1L)).thenReturn(history);

    List<StockMovement> result = useCase.handle(1L);

    assertEquals(history, result);
  }

  @Test
  void handle_lanza_excepcion_cuando_el_producto_no_existe() {
    ProductRepository products = mock(ProductRepository.class);
    StockMovementRepository movements = mock(StockMovementRepository.class);
    ListStockMovementsUseCase useCase = new ListStockMovementsUseCase(products, movements);
    when(products.findById(99L)).thenReturn(Optional.empty());

    assertThrows(ProductNotFoundException.class, () -> useCase.handle(99L));
    verify(movements, never()).findByProductId(anyLong());
  }
}
