package com.gentleman.inventory.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProductPageTest {

  @Test
  void sin_elementos_no_hay_pagina_siguiente() {
    ProductPage page = new ProductPage(List.of(), 0, 20, 0);

    assertFalse(page.hasNext());
  }

  @Test
  void en_la_ultima_pagina_exacta_no_hay_pagina_siguiente() {
    // 40 elementos, tamaño 20: página 0 = [0,20), página 1 = [20,40) es la última
    ProductPage page = new ProductPage(List.of(), 1, 20, 40);

    assertFalse(page.hasNext());
  }

  @Test
  void en_una_pagina_intermedia_hay_pagina_siguiente() {
    // 41 elementos, tamaño 20: la página 0 deja 21 elementos por ver
    ProductPage page = new ProductPage(List.of(), 0, 20, 41);

    assertTrue(page.hasNext());
  }

  @Test
  void totalPages_con_division_exacta() {
    ProductPage page = new ProductPage(List.of(), 0, 20, 40);

    assertEquals(2, page.totalPages());
  }

  @Test
  void totalPages_redondea_hacia_arriba_con_resto() {
    ProductPage page = new ProductPage(List.of(), 0, 20, 41);

    assertEquals(3, page.totalPages());
  }

  @Test
  void totalPages_sin_elementos_es_cero() {
    ProductPage page = new ProductPage(List.of(), 0, 20, 0);

    assertEquals(0, page.totalPages());
  }
}
