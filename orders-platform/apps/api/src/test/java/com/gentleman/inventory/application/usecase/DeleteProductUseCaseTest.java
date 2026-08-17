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
import org.mockito.ArgumentCaptor;

/** Doble del puerto con Mockito (HARNESS D), mocks locales (HARNESS C). */
class DeleteProductUseCaseTest {

  @Test
  void handle_marca_el_producto_como_eliminado_y_lo_persiste() {
    ProductRepository repository = mock(ProductRepository.class);
    DeleteProductUseCase useCase = new DeleteProductUseCase(repository);
    when(repository.findById(1L))
        .thenReturn(
            Optional.of(Product.restore(1L, "Teclado", "KEY-001", 10, ProductStatus.ACTIVE)));
    when(repository.update(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

    useCase.handle(1L);

    ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
    verify(repository).update(captor.capture());
    Product persisted = captor.getValue();
    assertEquals(ProductStatus.DELETED, persisted.status());
    assertEquals(1L, persisted.id());
    assertEquals(10, persisted.quantity(), "el borrado lógico no toca el stock");
  }

  @Test
  void handle_lanza_excepcion_cuando_no_existe() {
    ProductRepository repository = mock(ProductRepository.class);
    DeleteProductUseCase useCase = new DeleteProductUseCase(repository);
    when(repository.findById(99L)).thenReturn(Optional.empty());

    assertThrows(ProductNotFoundException.class, () -> useCase.handle(99L));
    verify(repository, never()).update(any(Product.class));
  }
}
