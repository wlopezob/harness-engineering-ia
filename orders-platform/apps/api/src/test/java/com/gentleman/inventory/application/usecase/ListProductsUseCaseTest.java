package com.gentleman.inventory.application.usecase;

import com.gentleman.inventory.domain.model.Product;
import com.gentleman.inventory.domain.port.ProductRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Doble del puerto con Mockito (HARNESS D). Los mocks son variables locales,
 * no campos, para no chocar con la regla el_nucleo_es_inmutable (HARNESS C).
 */
class ListProductsUseCaseTest {

    @Test
    void handle_sin_productos_devuelve_lista_vacia() {
        ProductRepository repository = mock(ProductRepository.class);
        ListProductsUseCase useCase = new ListProductsUseCase(repository);
        when(repository.findAll()).thenReturn(List.of());

        assertTrue(useCase.handle().isEmpty());
    }

    @Test
    void handle_devuelve_todos_los_productos() {
        ProductRepository repository = mock(ProductRepository.class);
        ListProductsUseCase useCase = new ListProductsUseCase(repository);
        List<Product> stored = List.of(
                Product.restore(1L, "Teclado", "KEY-001", 10),
                Product.restore(2L, "Mouse", "MOU-002", 5));
        when(repository.findAll()).thenReturn(stored);

        List<Product> result = useCase.handle();

        assertEquals(2, result.size());
        assertEquals("KEY-001", result.get(0).sku());
        assertEquals("MOU-002", result.get(1).sku());
    }
}
