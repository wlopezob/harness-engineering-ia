package com.gentleman.inventory.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PageRequestTest {

  @Test
  void rechaza_pagina_negativa() {
    assertThrows(IllegalArgumentException.class, () -> new PageRequest(-1, 20));
  }

  @Test
  void rechaza_tamano_cero() {
    assertThrows(IllegalArgumentException.class, () -> new PageRequest(0, 0));
  }

  @Test
  void rechaza_tamano_negativo() {
    assertThrows(IllegalArgumentException.class, () -> new PageRequest(0, -5));
  }

  @Test
  void rechaza_tamano_mayor_al_maximo() {
    assertThrows(
        IllegalArgumentException.class, () -> new PageRequest(0, PageRequest.MAX_SIZE + 1));
  }

  @Test
  void acepta_pagina_y_tamano_validos() {
    PageRequest pageRequest = new PageRequest(2, PageRequest.MAX_SIZE);

    assertEquals(2, pageRequest.page());
    assertEquals(PageRequest.MAX_SIZE, pageRequest.size());
  }
}
