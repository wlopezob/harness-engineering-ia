package com.gentleman.inventory.infrastructure.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** Ajustes de varios productos en una sola operación (github-32): o se aplican todos, o ninguno. */
@QuarkusTest
class BulkStockAdjustmentResourceTest {

  private int crearProducto(String sku, int cantidad) {
    return given()
        .contentType("application/json")
        .body(
            "{\"name\":\"Producto "
                + sku
                + "\",\"sku\":\""
                + sku
                + "\",\"quantity\":"
                + cantidad
                + "}")
        .when()
        .post("/inventory/products")
        .then()
        .statusCode(201)
        .extract()
        .path("id");
  }

  private int cantidadDe(int id) {
    return given()
        .when()
        .get("/inventory/products/" + id)
        .then()
        .statusCode(200)
        .extract()
        .path("quantity");
  }

  @Test
  void post_lote_valido_ajusta_todos_los_productos_y_devuelve_sus_cantidades() {
    int teclado = crearProducto("BULK-OK-1", 10);
    int mouse = crearProducto("BULK-OK-2", 4);

    given()
        .contentType("application/json")
        .body(
            "{\"adjustments\":[{\"productId\":"
                + teclado
                + ",\"delta\":5},{\"productId\":"
                + mouse
                + ",\"delta\":-3}]}")
        .when()
        .post("/inventory/stock-adjustments")
        .then()
        .statusCode(200)
        .body("", hasSize(2))
        .body("[0].id", equalTo(teclado))
        .body("[0].quantity", equalTo(15))
        .body("[1].id", equalTo(mouse))
        .body("[1].quantity", equalTo(1));

    given()
        .when()
        .get("/inventory/products/" + teclado + "/stock-movements")
        .then()
        .statusCode(200)
        .body("", hasSize(1))
        .body("[0].delta", equalTo(5));
    given()
        .when()
        .get("/inventory/products/" + mouse + "/stock-movements")
        .then()
        .statusCode(200)
        .body("", hasSize(1))
        .body("[0].delta", equalTo(-3));
  }

  @Test
  void post_lote_con_un_producto_inexistente_devuelve_404_y_no_ajusta_ninguno() {
    int teclado = crearProducto("BULK-404", 10);

    given()
        .contentType("application/json")
        .body(
            "{\"adjustments\":[{\"productId\":"
                + teclado
                + ",\"delta\":5},{\"productId\":999999,\"delta\":-3}]}")
        .when()
        .post("/inventory/stock-adjustments")
        .then()
        .statusCode(404)
        .body("message", notNullValue());

    assertEquals(10, cantidadDe(teclado), "el producto válido del lote no debió cambiar");
    given()
        .when()
        .get("/inventory/products/" + teclado + "/stock-movements")
        .then()
        .body("", hasSize(0));
  }

  @Test
  void post_lote_que_dejaria_un_stock_negativo_devuelve_409_y_no_ajusta_ninguno() {
    int teclado = crearProducto("BULK-409-1", 10);
    int mouse = crearProducto("BULK-409-2", 4);

    given()
        .contentType("application/json")
        .body(
            "{\"adjustments\":[{\"productId\":"
                + teclado
                + ",\"delta\":5},{\"productId\":"
                + mouse
                + ",\"delta\":-5}]}")
        .when()
        .post("/inventory/stock-adjustments")
        .then()
        .statusCode(409)
        .body("message", notNullValue());

    assertEquals(10, cantidadDe(teclado));
    assertEquals(4, cantidadDe(mouse));
    given()
        .when()
        .get("/inventory/products/" + teclado + "/stock-movements")
        .then()
        .body("", hasSize(0));
  }

  @Test
  void post_lote_con_el_mismo_producto_dos_veces_devuelve_400_y_no_ajusta_nada() {
    int teclado = crearProducto("BULK-DUP", 10);

    given()
        .contentType("application/json")
        .body(
            "{\"adjustments\":[{\"productId\":"
                + teclado
                + ",\"delta\":5},{\"productId\":"
                + teclado
                + ",\"delta\":-3}]}")
        .when()
        .post("/inventory/stock-adjustments")
        .then()
        .statusCode(400)
        .body("message", notNullValue());

    assertEquals(10, cantidadDe(teclado));
    given()
        .when()
        .get("/inventory/products/" + teclado + "/stock-movements")
        .then()
        .body("", hasSize(0));
  }

  @Test
  void post_lote_vacio_devuelve_400() {
    given()
        .contentType("application/json")
        .body("{\"adjustments\":[]}")
        .when()
        .post("/inventory/stock-adjustments")
        .then()
        .statusCode(400)
        .body("message", notNullValue());
  }

  @Test
  void post_sin_el_campo_adjustments_devuelve_400() {
    given()
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/inventory/stock-adjustments")
        .then()
        .statusCode(400)
        .body("message", notNullValue());
  }

  @Test
  void post_lote_con_delta_cero_devuelve_400_y_no_ajusta_nada() {
    int teclado = crearProducto("BULK-DELTA0-1", 10);
    int mouse = crearProducto("BULK-DELTA0-2", 4);

    given()
        .contentType("application/json")
        .body(
            "{\"adjustments\":[{\"productId\":"
                + teclado
                + ",\"delta\":5},{\"productId\":"
                + mouse
                + ",\"delta\":0}]}")
        .when()
        .post("/inventory/stock-adjustments")
        .then()
        .statusCode(400)
        .body("message", notNullValue());

    assertEquals(10, cantidadDe(teclado));
    assertEquals(4, cantidadDe(mouse));
  }

  @Test
  void post_ajuste_sin_producto_devuelve_400() {
    given()
        .contentType("application/json")
        .body("{\"adjustments\":[{\"delta\":5}]}")
        .when()
        .post("/inventory/stock-adjustments")
        .then()
        .statusCode(400)
        .body("message", notNullValue());
  }

  @Test
  void post_lote_con_un_ajuste_vacio_devuelve_400() {
    given()
        .contentType("application/json")
        .body("{\"adjustments\":[null]}")
        .when()
        .post("/inventory/stock-adjustments")
        .then()
        .statusCode(400)
        .body("message", notNullValue());
  }

  @Test
  void post_lote_con_un_producto_eliminado_devuelve_404_y_no_ajusta_ninguno() {
    int teclado = crearProducto("BULK-DEL-1", 10);
    int mouse = crearProducto("BULK-DEL-2", 4);
    given().when().delete("/inventory/products/" + mouse).then().statusCode(204);

    given()
        .contentType("application/json")
        .body(
            "{\"adjustments\":[{\"productId\":"
                + teclado
                + ",\"delta\":5},{\"productId\":"
                + mouse
                + ",\"delta\":2}]}")
        .when()
        .post("/inventory/stock-adjustments")
        .then()
        .statusCode(404);

    assertEquals(10, cantidadDe(teclado));
  }
}
