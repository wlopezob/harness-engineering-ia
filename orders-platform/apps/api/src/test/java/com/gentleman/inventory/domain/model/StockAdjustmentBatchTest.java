package com.gentleman.inventory.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Reglas del lote de ajustes (github-32). Dominio puro: sin mocks ni framework. */
class StockAdjustmentBatchTest {

  @Test
  void of_conserva_los_ajustes_en_el_orden_recibido() {
    List<StockAdjustment> items = List.of(new StockAdjustment(1L, 5), new StockAdjustment(2L, -3));

    StockAdjustmentBatch batch = StockAdjustmentBatch.of(items);

    assertEquals(items, batch.adjustments());
  }

  @Test
  void of_rechaza_un_lote_vacio() {
    List<StockAdjustment> items = List.of();

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> StockAdjustmentBatch.of(items));

    assertEquals("El lote de ajustes no puede estar vacío", error.getMessage());
  }

  @Test
  void of_rechaza_un_lote_nulo() {
    assertThrows(IllegalArgumentException.class, () -> StockAdjustmentBatch.of(null));
  }

  @Test
  void of_rechaza_el_mismo_producto_dos_veces() {
    List<StockAdjustment> items =
        List.of(
            new StockAdjustment(7L, 5), new StockAdjustment(2L, 1), new StockAdjustment(7L, -3));

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> StockAdjustmentBatch.of(items));

    assertTrue(
        error.getMessage().contains("7"),
        "el mensaje debe nombrar el producto repetido, fue: " + error.getMessage());
  }

  @Test
  void of_acepta_el_mismo_delta_en_productos_distintos() {
    List<StockAdjustment> items = List.of(new StockAdjustment(1L, 5), new StockAdjustment(2L, 5));

    StockAdjustmentBatch batch = StockAdjustmentBatch.of(items);

    assertEquals(2, batch.adjustments().size());
  }

  @Test
  void of_rechaza_un_ajuste_nulo_dentro_del_lote() {
    List<StockAdjustment> items = Arrays.asList(new StockAdjustment(1L, 5), null);

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> StockAdjustmentBatch.of(items));

    assertEquals("El lote de ajustes no admite elementos vacíos", error.getMessage());
  }

  @Test
  void un_ajuste_exige_producto() {
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> new StockAdjustment(null, 5));

    assertEquals("El producto del ajuste es obligatorio", error.getMessage());
  }

  @Test
  void of_copia_la_lista_recibida_y_no_la_sigue_observando() {
    List<StockAdjustment> items = new ArrayList<>(List.of(new StockAdjustment(1L, 5)));
    StockAdjustmentBatch batch = StockAdjustmentBatch.of(items);

    items.add(new StockAdjustment(2L, 9));

    assertEquals(1, batch.adjustments().size());
  }

  @Test
  void los_ajustes_del_lote_no_se_pueden_modificar() {
    StockAdjustmentBatch batch = StockAdjustmentBatch.of(List.of(new StockAdjustment(1L, 5)));

    assertThrows(
        UnsupportedOperationException.class,
        () -> batch.adjustments().add(new StockAdjustment(2L, 9)));
  }
}
