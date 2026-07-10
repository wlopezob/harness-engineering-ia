package com.gentleman.inventory.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertThrows(IllegalArgumentException.class,
                () -> Product.create("   ", "KEY-001", 10));
        assertThrows(IllegalArgumentException.class,
                () -> Product.create(null, "KEY-001", 10));
    }

    @Test
    void create_rechaza_sku_en_blanco() {
        assertThrows(IllegalArgumentException.class,
                () -> Product.create("Teclado", "  ", 10));
        assertThrows(IllegalArgumentException.class,
                () -> Product.create("Teclado", null, 10));
    }

    @Test
    void create_rechaza_cantidad_negativa() {
        assertThrows(IllegalArgumentException.class,
                () -> Product.create("Teclado", "KEY-001", -1));
    }

    @Test
    void update_cambia_nombre_y_cantidad_conservando_id_y_sku() {
        Product original = Product.restore(7L, "Teclado", "KEY-001", 10);

        Product updated = original.update("Teclado retroiluminado", 25);

        assertEquals(7L, updated.id());
        assertEquals("KEY-001", updated.sku(), "el SKU no se cambia");
        assertEquals("Teclado retroiluminado", updated.name());
        assertEquals(25, updated.quantity());
    }

    @Test
    void update_rechaza_nombre_en_blanco() {
        Product original = Product.restore(7L, "Teclado", "KEY-001", 10);

        assertThrows(IllegalArgumentException.class, () -> original.update("  ", 5));
        assertThrows(IllegalArgumentException.class, () -> original.update(null, 5));
    }

    @Test
    void update_rechaza_cantidad_negativa() {
        Product original = Product.restore(7L, "Teclado", "KEY-001", 10);

        assertThrows(IllegalArgumentException.class, () -> original.update("Teclado", -1));
    }
}
