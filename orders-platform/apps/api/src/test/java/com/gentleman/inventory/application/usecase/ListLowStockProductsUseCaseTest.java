package com.gentleman.inventory.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gentleman.inventory.domain.model.Product;
import com.gentleman.inventory.domain.model.ProductStatus;
import com.gentleman.inventory.domain.model.StockThreshold;
import com.gentleman.inventory.domain.port.ProductRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Doble del puerto con Mockito (HARNESS D). Los mocks son variables locales, no campos, para no
 * chocar con la regla el_nucleo_es_inmutable (HARNESS C).
 */
class ListLowStockProductsUseCaseTest {

  @Test
  void handle_delega_en_el_repositorio_con_el_umbral_construido() {
    ProductRepository repository = mock(ProductRepository.class);
    ListLowStockProductsUseCase useCase = new ListLowStockProductsUseCase(repository);
    List<Product> stored =
        List.of(Product.restore(1L, "Teclado", "KEY-001", 2, ProductStatus.ACTIVE));
    when(repository.findBelowOrEqualThreshold(new StockThreshold(5))).thenReturn(stored);

    List<Product> result = useCase.handle(5);

    assertEquals(1, result.size());
    assertEquals("KEY-001", result.get(0).sku());
  }

  @Test
  void handle_sin_coincidencias_devuelve_lista_vacia() {
    ProductRepository repository = mock(ProductRepository.class);
    ListLowStockProductsUseCase useCase = new ListLowStockProductsUseCase(repository);
    when(repository.findBelowOrEqualThreshold(any())).thenReturn(List.of());

    List<Product> result = useCase.handle(5);

    assertTrue(result.isEmpty());
  }

  @Test
  void handle_con_umbral_negativo_propaga_la_excepcion_sin_consultar_el_repositorio() {
    ProductRepository repository = mock(ProductRepository.class);
    ListLowStockProductsUseCase useCase = new ListLowStockProductsUseCase(repository);

    assertThrows(IllegalArgumentException.class, () -> useCase.handle(-1));

    verify(repository, never()).findBelowOrEqualThreshold(any());
  }
}
