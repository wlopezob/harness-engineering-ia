package com.gentleman.inventory.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gentleman.inventory.domain.model.Product;
import com.gentleman.inventory.domain.model.ProductNotFoundException;
import com.gentleman.inventory.domain.model.ProductStatus;
import com.gentleman.inventory.domain.model.StockAvailability;
import com.gentleman.inventory.domain.port.ProductRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Doble del puerto con Mockito (HARNESS D), mocks locales (HARNESS C). */
class CheckStockAvailabilityUseCaseTest {

  @Test
  void handle_devuelve_la_disponibilidad_del_producto_encontrado() {
    ProductRepository repository = mock(ProductRepository.class);
    CheckStockAvailabilityUseCase useCase = new CheckStockAvailabilityUseCase(repository);
    when(repository.findById(1L))
        .thenReturn(
            Optional.of(Product.restore(1L, "Teclado", "KEY-001", 8, ProductStatus.ACTIVE)));

    StockAvailability availability = useCase.handle(1L, 10);

    assertEquals(1L, availability.productId());
    assertEquals(10, availability.requestedQuantity());
    assertEquals(8, availability.availableQuantity());
    assertFalse(availability.available());
    assertEquals(2, availability.missingQuantity());
  }

  @Test
  void handle_lanza_excepcion_cuando_el_producto_no_existe_o_esta_eliminado() {
    ProductRepository repository = mock(ProductRepository.class);
    CheckStockAvailabilityUseCase useCase = new CheckStockAvailabilityUseCase(repository);
    when(repository.findById(99L)).thenReturn(Optional.empty());

    assertThrows(ProductNotFoundException.class, () -> useCase.handle(99L, 1));
  }

  @Test
  void handle_nunca_escribe_el_producto() {
    ProductRepository repository = mock(ProductRepository.class);
    CheckStockAvailabilityUseCase useCase = new CheckStockAvailabilityUseCase(repository);
    Product product = Product.restore(1L, "Teclado", "KEY-001", 8, ProductStatus.ACTIVE);
    when(repository.findById(1L)).thenReturn(Optional.of(product));

    StockAvailability availability = useCase.handle(1L, 4);

    assertTrue(availability.available());
    verify(repository, never()).update(any());
    verify(repository, never()).save(any());
  }
}
