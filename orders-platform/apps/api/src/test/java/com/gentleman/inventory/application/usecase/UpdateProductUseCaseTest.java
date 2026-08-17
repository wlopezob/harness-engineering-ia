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
import com.gentleman.inventory.domain.model.ProductStatus;
import com.gentleman.inventory.domain.port.ProductRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Doble del puerto con Mockito (HARNESS D), mocks locales (HARNESS C). */
class UpdateProductUseCaseTest {

  @Test
  void handle_actualiza_el_nombre_conservando_la_cantidad() {
    ProductRepository repository = mock(ProductRepository.class);
    UpdateProductUseCase useCase = new UpdateProductUseCase(repository);
    when(repository.findById(1L))
        .thenReturn(
            Optional.of(Product.restore(1L, "Teclado", "KEY-001", 10, ProductStatus.ACTIVE)));
    when(repository.update(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

    Product result = useCase.handle(1L, "Teclado nuevo");

    assertEquals(1L, result.id());
    assertEquals("KEY-001", result.sku(), "el SKU no cambia");
    assertEquals("Teclado nuevo", result.name());
    assertEquals(10, result.quantity(), "el stock solo se mueve con un ajuste");
    verify(repository).update(any(Product.class));
  }

  @Test
  void handle_lanza_excepcion_cuando_no_existe() {
    ProductRepository repository = mock(ProductRepository.class);
    UpdateProductUseCase useCase = new UpdateProductUseCase(repository);
    when(repository.findById(99L)).thenReturn(Optional.empty());

    assertThrows(ProductNotFoundException.class, () -> useCase.handle(99L, "x"));
    verify(repository, never()).update(any(Product.class));
  }
}
