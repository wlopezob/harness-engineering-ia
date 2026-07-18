package com.gentleman.inventory.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gentleman.inventory.domain.model.DuplicateSkuException;
import com.gentleman.inventory.domain.model.Product;
import com.gentleman.inventory.domain.port.ProductRepository;
import org.junit.jupiter.api.Test;

/**
 * Doble del puerto con Mockito (HARNESS D). Los mocks son variables locales, no campos, para no
 * chocar con la regla el_nucleo_es_inmutable (HARNESS C).
 */
class CreateProductUseCaseTest {

  @Test
  void handle_guarda_y_devuelve_producto_con_id() {
    ProductRepository repository = mock(ProductRepository.class);
    CreateProductUseCase useCase = new CreateProductUseCase(repository);
    when(repository.existsBySku("KEY-001")).thenReturn(false);
    when(repository.save(any(Product.class)))
        .thenAnswer(
            inv -> {
              Product p = inv.getArgument(0);
              return Product.restore(1L, p.name(), p.sku(), p.quantity());
            });

    Product result = useCase.handle("Teclado mecánico", "KEY-001", 10);

    assertEquals(1L, result.id());
    assertEquals("Teclado mecánico", result.name());
    assertEquals("KEY-001", result.sku());
    assertEquals(10, result.quantity());
    verify(repository).save(any(Product.class));
  }

  @Test
  void handle_rechaza_sku_duplicado() {
    ProductRepository repository = mock(ProductRepository.class);
    CreateProductUseCase useCase = new CreateProductUseCase(repository);
    when(repository.existsBySku("KEY-001")).thenReturn(true);

    assertThrows(DuplicateSkuException.class, () -> useCase.handle("Teclado", "KEY-001", 10));

    verify(repository, never()).save(any(Product.class));
  }
}
