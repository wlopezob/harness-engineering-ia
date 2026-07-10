package com.gentleman.inventory.application.usecase;

import com.gentleman.inventory.domain.model.Product;
import com.gentleman.inventory.domain.model.ProductNotFoundException;
import com.gentleman.inventory.domain.port.ProductRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Doble del puerto con Mockito (HARNESS D), mocks locales (HARNESS C). */
class UpdateProductUseCaseTest {

    @Test
    void handle_actualiza_y_devuelve_el_producto() {
        ProductRepository repository = mock(ProductRepository.class);
        UpdateProductUseCase useCase = new UpdateProductUseCase(repository);
        when(repository.findById(1L))
                .thenReturn(Optional.of(Product.restore(1L, "Teclado", "KEY-001", 10)));
        when(repository.update(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        Product result = useCase.handle(1L, "Teclado nuevo", 25);

        assertEquals(1L, result.id());
        assertEquals("KEY-001", result.sku(), "el SKU no cambia");
        assertEquals("Teclado nuevo", result.name());
        assertEquals(25, result.quantity());
        verify(repository).update(any(Product.class));
    }

    @Test
    void handle_lanza_excepcion_cuando_no_existe() {
        ProductRepository repository = mock(ProductRepository.class);
        UpdateProductUseCase useCase = new UpdateProductUseCase(repository);
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ProductNotFoundException.class, () -> useCase.handle(99L, "x", 1));
        verify(repository, never()).update(any(Product.class));
    }
}
