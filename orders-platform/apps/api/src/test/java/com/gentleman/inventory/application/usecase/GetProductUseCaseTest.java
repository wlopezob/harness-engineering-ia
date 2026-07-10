package com.gentleman.inventory.application.usecase;

import com.gentleman.inventory.domain.model.Product;
import com.gentleman.inventory.domain.model.ProductNotFoundException;
import com.gentleman.inventory.domain.port.ProductRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Doble del puerto con Mockito (HARNESS D), mocks locales (no campos) para no
 * chocar con el_nucleo_es_inmutable (HARNESS C).
 */
class GetProductUseCaseTest {

    @Test
    void handle_devuelve_el_producto_cuando_existe() {
        ProductRepository repository = mock(ProductRepository.class);
        GetProductUseCase useCase = new GetProductUseCase(repository);
        when(repository.findById(1L))
                .thenReturn(Optional.of(Product.restore(1L, "Teclado", "KEY-001", 10)));

        Product result = useCase.handle(1L);

        assertEquals(1L, result.id());
        assertEquals("Teclado", result.name());
        assertEquals("KEY-001", result.sku());
        assertEquals(10, result.quantity());
    }

    @Test
    void handle_lanza_excepcion_cuando_no_existe() {
        ProductRepository repository = mock(ProductRepository.class);
        GetProductUseCase useCase = new GetProductUseCase(repository);
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ProductNotFoundException.class, () -> useCase.handle(99L));
    }
}
