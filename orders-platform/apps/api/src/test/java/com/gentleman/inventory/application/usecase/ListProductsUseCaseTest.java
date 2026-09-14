package com.gentleman.inventory.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gentleman.inventory.domain.model.PageRequest;
import com.gentleman.inventory.domain.model.Product;
import com.gentleman.inventory.domain.model.ProductPage;
import com.gentleman.inventory.domain.model.ProductStatus;
import com.gentleman.inventory.domain.port.ProductRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Doble del puerto con Mockito (HARNESS D). Los mocks son variables locales, no campos, para no
 * chocar con la regla el_nucleo_es_inmutable (HARNESS C).
 */
class ListProductsUseCaseTest {

  @Test
  void handle_sin_productos_devuelve_pagina_vacia() {
    ProductRepository repository = mock(ProductRepository.class);
    ListProductsUseCase useCase = new ListProductsUseCase(repository);
    when(repository.findPage(any())).thenReturn(List.of());
    when(repository.countActive()).thenReturn(0L);

    ProductPage result = useCase.handle(0, 20);

    assertTrue(result.items().isEmpty());
    assertEquals(0, result.page());
    assertEquals(20, result.size());
    assertEquals(0, result.totalElements());
  }

  @Test
  void handle_arma_la_pagina_con_lo_que_devuelve_el_repositorio() {
    ProductRepository repository = mock(ProductRepository.class);
    ListProductsUseCase useCase = new ListProductsUseCase(repository);
    List<Product> stored =
        List.of(
            Product.restore(1L, "Teclado", "KEY-001", 10, ProductStatus.ACTIVE),
            Product.restore(2L, "Mouse", "MOU-002", 5, ProductStatus.ACTIVE));
    when(repository.findPage(new PageRequest(0, 20))).thenReturn(stored);
    when(repository.countActive()).thenReturn(2L);

    ProductPage result = useCase.handle(0, 20);

    assertEquals(2, result.items().size());
    assertEquals("KEY-001", result.items().get(0).sku());
    assertEquals("MOU-002", result.items().get(1).sku());
    assertEquals(0, result.page());
    assertEquals(20, result.size());
    assertEquals(2, result.totalElements());
  }

  @Test
  void handle_con_pagina_invalida_propaga_la_excepcion_sin_consultar_el_repositorio() {
    ProductRepository repository = mock(ProductRepository.class);
    ListProductsUseCase useCase = new ListProductsUseCase(repository);

    assertThrows(IllegalArgumentException.class, () -> useCase.handle(-1, 20));

    verify(repository, never()).findPage(any());
    verify(repository, never()).countActive();
  }
}
