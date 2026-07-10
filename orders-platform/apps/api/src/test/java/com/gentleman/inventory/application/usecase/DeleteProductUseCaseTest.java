package com.gentleman.inventory.application.usecase;

import com.gentleman.inventory.domain.model.ProductNotFoundException;
import com.gentleman.inventory.domain.port.ProductRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Doble del puerto con Mockito (HARNESS D), mocks locales (HARNESS C). */
class DeleteProductUseCaseTest {

    @Test
    void handle_elimina_cuando_existe() {
        ProductRepository repository = mock(ProductRepository.class);
        DeleteProductUseCase useCase = new DeleteProductUseCase(repository);
        when(repository.deleteById(1L)).thenReturn(true);

        useCase.handle(1L);

        verify(repository).deleteById(1L);
    }

    @Test
    void handle_lanza_excepcion_cuando_no_existe() {
        ProductRepository repository = mock(ProductRepository.class);
        DeleteProductUseCase useCase = new DeleteProductUseCase(repository);
        when(repository.deleteById(99L)).thenReturn(false);

        assertThrows(ProductNotFoundException.class, () -> useCase.handle(99L));
    }
}
