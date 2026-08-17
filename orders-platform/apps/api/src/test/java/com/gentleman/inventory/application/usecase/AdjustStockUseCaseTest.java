package com.gentleman.inventory.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gentleman.inventory.domain.model.Product;
import com.gentleman.inventory.domain.model.ProductNotFoundException;
import com.gentleman.inventory.domain.port.ProductRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Doble del puerto con Mockito (HARNESS D), mocks locales (HARNESS C). */
class AdjustStockUseCaseTest {

  @Test
  void handle_ajusta_el_stock_y_persiste_el_resultado() {
    ProductRepository repository = mock(ProductRepository.class);
    AdjustStockUseCase useCase = new AdjustStockUseCase(repository);
    when(repository.findById(1L))
        .thenReturn(Optional.of(Product.restore(1L, "Teclado", "KEY-001", 10)));
    when(repository.update(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

    Product result = useCase.handle(1L, 5);

    assertEquals(15, result.quantity());
    assertEquals("KEY-001", result.sku(), "el SKU no cambia");
    verify(repository).update(any(Product.class));
  }

  @Test
  void handle_lanza_excepcion_cuando_el_producto_no_existe() {
    ProductRepository repository = mock(ProductRepository.class);
    AdjustStockUseCase useCase = new AdjustStockUseCase(repository);
    when(repository.findById(99L)).thenReturn(Optional.empty());

    assertThrows(ProductNotFoundException.class, () -> useCase.handle(99L, 5));
    verify(repository, never()).update(any(Product.class));
  }
}
