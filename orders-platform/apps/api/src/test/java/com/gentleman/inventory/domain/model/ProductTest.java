package com.gentleman.inventory.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProductTest {

  @Test
  void create_con_datos_validos_construye_producto_sin_id() {
    Product product = Product.create("Teclado mecánico", "KEY-001", 10);

    assertNull(product.id(), "un producto recién creado aún no tiene id");
    assertEquals("Teclado mecánico", product.name());
    assertEquals("KEY-001", product.sku());
    assertEquals(10, product.quantity());
  }

  @Test
  void create_rechaza_nombre_en_blanco() {
    assertThrows(IllegalArgumentException.class, () -> Product.create("   ", "KEY-001", 10));
    assertThrows(IllegalArgumentException.class, () -> Product.create(null, "KEY-001", 10));
  }

  @Test
  void create_rechaza_sku_en_blanco() {
    assertThrows(IllegalArgumentException.class, () -> Product.create("Teclado", "  ", 10));
    assertThrows(IllegalArgumentException.class, () -> Product.create("Teclado", null, 10));
  }

  @Test
  void create_rechaza_cantidad_negativa() {
    assertThrows(IllegalArgumentException.class, () -> Product.create("Teclado", "KEY-001", -1));
  }

  @Test
  void update_cambia_nombre_y_cantidad_conservando_id_y_sku() {
    Product original = Product.restore(7L, "Teclado", "KEY-001", 10, ProductStatus.ACTIVE);

    Product updated = original.update("Teclado retroiluminado", 25);

    assertEquals(7L, updated.id());
    assertEquals("KEY-001", updated.sku(), "el SKU no se cambia");
    assertEquals("Teclado retroiluminado", updated.name());
    assertEquals(25, updated.quantity());
  }

  @Test
  void update_rechaza_nombre_en_blanco() {
    Product original = Product.restore(7L, "Teclado", "KEY-001", 10, ProductStatus.ACTIVE);

    assertThrows(IllegalArgumentException.class, () -> original.update("  ", 5));
    assertThrows(IllegalArgumentException.class, () -> original.update(null, 5));
  }

  @Test
  void update_rechaza_cantidad_negativa() {
    Product original = Product.restore(7L, "Teclado", "KEY-001", 10, ProductStatus.ACTIVE);

    assertThrows(IllegalArgumentException.class, () -> original.update("Teclado", -1));
  }

  @Test
  void adjust_stock_con_delta_positivo_aumenta_la_cantidad() {
    Product original = Product.restore(7L, "Teclado", "KEY-001", 10, ProductStatus.ACTIVE);

    Product adjusted = original.adjustStock(5);

    assertEquals(15, adjusted.quantity());
    assertEquals(7L, adjusted.id());
    assertEquals("KEY-001", adjusted.sku(), "un ajuste de stock no toca el SKU");
    assertEquals("Teclado", adjusted.name(), "un ajuste de stock no toca el nombre");
  }

  @Test
  void adjust_stock_con_delta_negativo_disminuye_la_cantidad() {
    Product original = Product.restore(7L, "Teclado", "KEY-001", 10, ProductStatus.ACTIVE);

    Product adjusted = original.adjustStock(-3);

    assertEquals(7, adjusted.quantity());
  }

  @Test
  void adjust_stock_rechaza_delta_cero() {
    Product original = Product.restore(7L, "Teclado", "KEY-001", 10, ProductStatus.ACTIVE);

    assertThrows(IllegalArgumentException.class, () -> original.adjustStock(0));
  }

  @Test
  void adjust_stock_rechaza_una_salida_mayor_al_stock_disponible() {
    Product original = Product.restore(7L, "Teclado", "KEY-001", 10, ProductStatus.ACTIVE);

    assertThrows(InsufficientStockException.class, () -> original.adjustStock(-11));
  }

  @Test
  void adjust_stock_permite_dejar_la_cantidad_exactamente_en_cero() {
    Product original = Product.restore(7L, "Teclado", "KEY-001", 10, ProductStatus.ACTIVE);

    Product adjusted = original.adjustStock(-10);

    assertEquals(0, adjusted.quantity(), "vaciar el stock es válido; negativo es lo prohibido");
  }

  @Test
  void adjust_stock_rechaza_un_delta_que_desborda_el_rango_de_int() {
    Product original = Product.restore(7L, "Teclado", "KEY-001", 10, ProductStatus.ACTIVE);

    assertThrows(
        IllegalArgumentException.class,
        () -> original.adjustStock(Integer.MAX_VALUE),
        "el desborde es un delta inválido (400), no una falta de stock (409)");
  }

  @Test
  void adjust_stock_permite_llegar_exactamente_a_la_cantidad_maxima() {
    Product original =
        Product.restore(7L, "Teclado", "KEY-001", Integer.MAX_VALUE - 5, ProductStatus.ACTIVE);

    Product adjusted = original.adjustStock(5);

    assertEquals(Integer.MAX_VALUE, adjusted.quantity(), "el máximo es alcanzable, no excedido");
  }

  @Test
  void create_deja_el_producto_activo() {
    Product product = Product.create("Teclado", "KEY-001", 10);

    assertEquals(ProductStatus.ACTIVE, product.status());
  }

  @Test
  void mark_deleted_devuelve_un_producto_eliminado_conservando_sus_datos() {
    Product original = Product.restore(7L, "Teclado", "KEY-001", 10, ProductStatus.ACTIVE);

    Product deleted = original.markDeleted();

    assertEquals(ProductStatus.DELETED, deleted.status());
    assertEquals(7L, deleted.id());
    assertEquals("Teclado", deleted.name());
    assertEquals("KEY-001", deleted.sku());
    assertEquals(10, deleted.quantity());
    assertEquals(ProductStatus.ACTIVE, original.status(), "inmutable: el original no cambia");
  }
}
