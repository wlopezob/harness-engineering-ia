package com.gentleman.inventory.infrastructure.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ProductResourceTest {

  @Test
  void post_producto_valido_devuelve_201_con_id_y_location() {
    given()
        .contentType("application/json")
        .body("{\"name\":\"Teclado mecánico\",\"sku\":\"KEY-201\",\"quantity\":10}")
        .when()
        .post("/inventory/products")
        .then()
        .statusCode(201)
        .header("Location", containsString("/inventory/products/"))
        .body("id", notNullValue())
        .body("name", equalTo("Teclado mecánico"))
        .body("sku", equalTo("KEY-201"))
        .body("quantity", equalTo(10));
  }

  @Test
  void post_nombre_en_blanco_devuelve_400() {
    given()
        .contentType("application/json")
        .body("{\"name\":\"   \",\"sku\":\"KEY-400\",\"quantity\":5}")
        .when()
        .post("/inventory/products")
        .then()
        .statusCode(400)
        .body("message", notNullValue());
  }

  @Test
  void post_cantidad_negativa_devuelve_400() {
    given()
        .contentType("application/json")
        .body("{\"name\":\"Mouse\",\"sku\":\"MOU-400\",\"quantity\":-3}")
        .when()
        .post("/inventory/products")
        .then()
        .statusCode(400);
  }

  @Test
  void post_sku_duplicado_devuelve_409() {
    String body = "{\"name\":\"Monitor\",\"sku\":\"MON-DUP\",\"quantity\":2}";

    given()
        .contentType("application/json")
        .body(body)
        .when()
        .post("/inventory/products")
        .then()
        .statusCode(201);

    given()
        .contentType("application/json")
        .body(body)
        .when()
        .post("/inventory/products")
        .then()
        .statusCode(409)
        .body("message", containsString("MON-DUP"));
  }

  @Test
  void get_por_id_devuelve_el_producto_existente() {
    int id =
        given()
            .contentType("application/json")
            .body("{\"name\":\"Webcam\",\"sku\":\"BYID-A\",\"quantity\":9}")
            .when()
            .post("/inventory/products")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    given()
        .when()
        .get("/inventory/products/" + id)
        .then()
        .statusCode(200)
        .body("id", equalTo(id))
        .body("name", equalTo("Webcam"))
        .body("sku", equalTo("BYID-A"))
        .body("quantity", equalTo(9));
  }

  @Test
  void get_por_id_inexistente_devuelve_404() {
    given()
        .when()
        .get("/inventory/products/99999999")
        .then()
        .statusCode(404)
        .body("message", containsString("99999999"));
  }

  @Test
  void put_actualiza_el_nombre_conservando_sku_y_cantidad() {
    int id =
        given()
            .contentType("application/json")
            .body("{\"name\":\"Teclado\",\"sku\":\"PUT-A\",\"quantity\":10}")
            .when()
            .post("/inventory/products")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    given()
        .contentType("application/json")
        .body("{\"name\":\"Teclado v2\"}")
        .when()
        .put("/inventory/products/" + id)
        .then()
        .statusCode(200)
        .body("id", equalTo(id))
        .body("name", equalTo("Teclado v2"))
        .body("sku", equalTo("PUT-A"))
        // el PUT no mueve el stock: sigue en la cantidad de creación
        .body("quantity", equalTo(10));
  }

  @Test
  void put_a_id_inexistente_devuelve_404() {
    given()
        .contentType("application/json")
        .body("{\"name\":\"x\"}")
        .when()
        .put("/inventory/products/99999999")
        .then()
        .statusCode(404)
        .body("message", containsString("99999999"));
  }

  @Test
  void put_con_datos_invalidos_devuelve_400() {
    int id =
        given()
            .contentType("application/json")
            .body("{\"name\":\"Mouse\",\"sku\":\"PUT-INV\",\"quantity\":5}")
            .when()
            .post("/inventory/products")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    given()
        .contentType("application/json")
        .body("{\"name\":\"   \"}")
        .when()
        .put("/inventory/products/" + id)
        .then()
        .statusCode(400)
        .body("message", notNullValue());
  }

  @Test
  void put_con_quantity_en_el_body_devuelve_400_y_no_toca_el_stock() {
    int id = crearProducto("PUT-QTY", 10);

    given()
        .contentType("application/json")
        .body("{\"name\":\"Teclado v2\",\"quantity\":100}")
        .when()
        .put("/inventory/products/" + id)
        .then()
        // el stock no se edita aquí: se mueve en /stock-adjustments
        .statusCode(400)
        .body("message", notNullValue());

    given()
        .when()
        .get("/inventory/products/" + id)
        .then()
        .statusCode(200)
        .body("quantity", equalTo(10))
        .body("name", equalTo("Teclado"));
  }

  @Test
  void delete_elimina_el_producto_y_luego_da_404() {
    int id =
        given()
            .contentType("application/json")
            .body("{\"name\":\"Webcam\",\"sku\":\"DEL-A\",\"quantity\":2}")
            .when()
            .post("/inventory/products")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    given().when().delete("/inventory/products/" + id).then().statusCode(204);

    given().when().get("/inventory/products/" + id).then().statusCode(404);
  }

  @Test
  void delete_a_id_inexistente_devuelve_404() {
    given()
        .when()
        .delete("/inventory/products/99999999")
        .then()
        .statusCode(404)
        .body("message", containsString("99999999"));
  }

  @Test
  void post_ajuste_con_delta_positivo_devuelve_200_con_la_cantidad_aumentada() {
    int id = crearProducto("ADJ-IN", 10);

    given()
        .contentType("application/json")
        .body("{\"delta\":5}")
        .when()
        .post("/inventory/products/" + id + "/stock-adjustments")
        .then()
        .statusCode(200)
        .body("id", equalTo(id))
        .body("sku", equalTo("ADJ-IN"))
        .body("quantity", equalTo(15));

    given()
        .when()
        .get("/inventory/products/" + id)
        .then()
        .statusCode(200)
        // la cantidad resultante quedó persistida, no solo en la respuesta
        .body("quantity", equalTo(15));
  }

  @Test
  void post_ajuste_con_delta_negativo_devuelve_200_con_la_cantidad_disminuida() {
    int id = crearProducto("ADJ-OUT", 10);

    given()
        .contentType("application/json")
        .body("{\"delta\":-3}")
        .when()
        .post("/inventory/products/" + id + "/stock-adjustments")
        .then()
        .statusCode(200)
        .body("quantity", equalTo(7));
  }

  @Test
  void post_ajuste_que_deja_el_stock_negativo_devuelve_409_y_no_persiste() {
    int id = crearProducto("ADJ-409", 10);

    given()
        .contentType("application/json")
        .body("{\"delta\":-11}")
        .when()
        .post("/inventory/products/" + id + "/stock-adjustments")
        .then()
        .statusCode(409)
        .body("message", notNullValue());

    given()
        .when()
        .get("/inventory/products/" + id)
        .then()
        .statusCode(200)
        // el rechazo deja la cantidad intacta
        .body("quantity", equalTo(10));
  }

  @Test
  void post_ajuste_con_delta_cero_devuelve_400() {
    int id = crearProducto("ADJ-400", 10);

    given()
        .contentType("application/json")
        .body("{\"delta\":0}")
        .when()
        .post("/inventory/products/" + id + "/stock-adjustments")
        .then()
        .statusCode(400)
        .body("message", notNullValue());
  }

  @Test
  void post_ajuste_a_id_inexistente_devuelve_404() {
    given()
        .contentType("application/json")
        .body("{\"delta\":5}")
        .when()
        .post("/inventory/products/99999999/stock-adjustments")
        .then()
        .statusCode(404)
        .body("message", containsString("99999999"));
  }

  @Test
  void get_historial_devuelve_los_movimientos_del_mas_reciente_al_mas_antiguo() {
    int id = crearProducto("MOV-HIST", 10);
    ajustarStock(id, 5);
    ajustarStock(id, -3);

    given()
        .when()
        .get("/inventory/products/" + id + "/stock-movements")
        .then()
        .statusCode(200)
        .body("size()", equalTo(2))
        // el más reciente primero: la salida de -3 (15 → 12)
        .body("[0].productId", equalTo(id))
        .body("[0].delta", equalTo(-3))
        .body("[0].previousQuantity", equalTo(15))
        .body("[0].resultingQuantity", equalTo(12))
        .body("[0].occurredAt", notNullValue())
        .body("[0].id", notNullValue())
        // luego la entrada de +5 (10 → 15)
        .body("[1].delta", equalTo(5))
        .body("[1].previousQuantity", equalTo(10))
        .body("[1].resultingQuantity", equalTo(15));
  }

  @Test
  void get_historial_no_incluye_los_ajustes_rechazados() {
    int id = crearProducto("MOV-REJ", 10);
    ajustarStock(id, 5);

    given()
        .contentType("application/json")
        .body("{\"delta\":-100}")
        .when()
        .post("/inventory/products/" + id + "/stock-adjustments")
        .then()
        .statusCode(409);

    given()
        .when()
        .get("/inventory/products/" + id + "/stock-movements")
        .then()
        .statusCode(200)
        // solo el ajuste exitoso dejó movimiento
        .body("size()", equalTo(1))
        .body("[0].delta", equalTo(5));
  }

  @Test
  void get_historial_de_producto_sin_ajustes_devuelve_lista_vacia() {
    int id = crearProducto("MOV-EMPTY", 10);

    given()
        .when()
        .get("/inventory/products/" + id + "/stock-movements")
        .then()
        .statusCode(200)
        .body("size()", equalTo(0));
  }

  @Test
  void get_historial_de_id_inexistente_devuelve_404() {
    given()
        .when()
        .get("/inventory/products/99999999/stock-movements")
        .then()
        .statusCode(404)
        .body("message", containsString("99999999"));
  }

  @Test
  void delete_deja_el_producto_invisible_para_toda_la_api() {
    int id = crearProducto("DEL-SOFT", 10);
    ajustarStock(id, 5);

    given().when().delete("/inventory/products/" + id).then().statusCode(204);

    // para la API un producto eliminado no existe: 404 en todo, ausente en la lista
    given().when().get("/inventory/products/" + id).then().statusCode(404);
    given()
        .contentType("application/json")
        .body("{\"delta\":1}")
        .when()
        .post("/inventory/products/" + id + "/stock-adjustments")
        .then()
        .statusCode(404);
    given().when().get("/inventory/products/" + id + "/stock-movements").then().statusCode(404);
    given()
        .contentType("application/json")
        .body("{\"name\":\"Otro\"}")
        .when()
        .put("/inventory/products/" + id)
        .then()
        .statusCode(404);
    given().when().delete("/inventory/products/" + id).then().statusCode(404);
    given()
        .when()
        .get("/inventory/products")
        .then()
        .statusCode(200)
        .body("sku", not(hasItem("DEL-SOFT")));
  }

  @Test
  void delete_de_producto_con_movimientos_devuelve_204() {
    int id = crearProducto("DEL-HIST", 10);
    ajustarStock(id, 5);
    ajustarStock(id, -2);

    given().when().delete("/inventory/products/" + id).then().statusCode(204);
  }

  @Test
  void post_con_sku_de_un_producto_eliminado_devuelve_409() {
    int id = crearProducto("DEL-SKU", 10);
    given().when().delete("/inventory/products/" + id).then().statusCode(204);

    given()
        .contentType("application/json")
        .body("{\"name\":\"Nuevo\",\"sku\":\"DEL-SKU\",\"quantity\":1}")
        .when()
        .post("/inventory/products")
        .then()
        // el SKU sigue reservado por el producto eliminado
        .statusCode(409);
  }

  @Test
  void el_historial_explica_siempre_la_cantidad_actual() {
    int id = crearProducto("INV-CHAIN", 10);
    ajustarStock(id, 5);

    // intento de mover el stock por la puerta del PUT: se acepte o se rechace, el
    // historial tiene que seguir explicando la cantidad final
    given()
        .contentType("application/json")
        .body("{\"name\":\"Teclado v2\",\"quantity\":100}")
        .when()
        .put("/inventory/products/" + id);

    ajustarStock(id, -3);

    int cantidadActual =
        given()
            .when()
            .get("/inventory/products/" + id)
            .then()
            .statusCode(200)
            .extract()
            .path("quantity");
    List<Map<String, Object>> movimientos =
        given()
            .when()
            .get("/inventory/products/" + id + "/stock-movements")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("$");

    int sumaDeltas = movimientos.stream().mapToInt(m -> ((Number) m.get("delta")).intValue()).sum();
    assertEquals(
        10 + sumaDeltas,
        cantidadActual,
        "cantidad inicial + suma de los deltas del historial debe dar la cantidad actual");
    for (int i = 0; i < movimientos.size() - 1; i++) {
      assertEquals(
          movimientos.get(i + 1).get("resultingQuantity"),
          movimientos.get(i).get("previousQuantity"),
          "salto de cantidad sin un movimiento que lo explique");
    }
  }

  private void ajustarStock(int id, int delta) {
    given()
        .contentType("application/json")
        .body("{\"delta\":" + delta + "}")
        .when()
        .post("/inventory/products/" + id + "/stock-adjustments")
        .then()
        .statusCode(200);
  }

  private int crearProducto(String sku, int quantity) {
    return given()
        .contentType("application/json")
        .body("{\"name\":\"Teclado\",\"sku\":\"" + sku + "\",\"quantity\":" + quantity + "}")
        .when()
        .post("/inventory/products")
        .then()
        .statusCode(201)
        .extract()
        .path("id");
  }

  @Test
  void get_lista_los_productos_registrados() {
    given()
        .contentType("application/json")
        .body("{\"name\":\"Producto A\",\"sku\":\"LIST-A\",\"quantity\":7}")
        .when()
        .post("/inventory/products")
        .then()
        .statusCode(201);
    given()
        .contentType("application/json")
        .body("{\"name\":\"Producto B\",\"sku\":\"LIST-B\",\"quantity\":3}")
        .when()
        .post("/inventory/products")
        .then()
        .statusCode(201);

    given()
        .when()
        .get("/inventory/products")
        .then()
        .statusCode(200)
        .body("sku", hasItems("LIST-A", "LIST-B"))
        .body("find { it.sku == 'LIST-A' }.id", notNullValue())
        .body("find { it.sku == 'LIST-A' }.name", equalTo("Producto A"))
        .body("find { it.sku == 'LIST-A' }.quantity", equalTo(7));
  }

  @Test
  void get_availability_con_stock_suficiente_devuelve_200_disponible() {
    int id =
        given()
            .contentType("application/json")
            .body("{\"name\":\"Teclado\",\"sku\":\"AVAIL-OK\",\"quantity\":10}")
            .when()
            .post("/inventory/products")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    given()
        .when()
        .get("/inventory/products/" + id + "/availability?quantity=4")
        .then()
        .statusCode(200)
        .body("productId", equalTo(id))
        .body("requestedQuantity", equalTo(4))
        .body("availableQuantity", equalTo(10))
        .body("available", equalTo(true))
        .body("missingQuantity", equalTo(0));
  }

  @Test
  void get_availability_con_stock_insuficiente_devuelve_200_con_lo_que_falta() {
    int id = crearProducto("AVAIL-SHORT", 8);

    given()
        .when()
        .get("/inventory/products/" + id + "/availability?quantity=10")
        .then()
        .statusCode(200)
        .body("available", equalTo(false))
        .body("availableQuantity", equalTo(8))
        .body("missingQuantity", equalTo(2));
  }

  @Test
  void get_availability_con_cantidad_solicitada_invalida_devuelve_400() {
    int id = crearProducto("AVAIL-BAD", 5);

    given()
        .when()
        .get("/inventory/products/" + id + "/availability?quantity=0")
        .then()
        .statusCode(400)
        .body("message", notNullValue());

    given()
        .when()
        .get("/inventory/products/" + id + "/availability?quantity=-1")
        .then()
        .statusCode(400);

    given().when().get("/inventory/products/" + id + "/availability").then().statusCode(400);
  }

  @Test
  void get_availability_de_un_producto_inexistente_devuelve_404() {
    given()
        .when()
        .get("/inventory/products/99999999/availability?quantity=1")
        .then()
        .statusCode(404)
        .body("message", containsString("99999999"));
  }

  @Test
  void get_availability_de_un_producto_eliminado_devuelve_404() {
    int id = crearProducto("AVAIL-DEL", 10);

    given().when().delete("/inventory/products/" + id).then().statusCode(204);

    given()
        .when()
        .get("/inventory/products/" + id + "/availability?quantity=1")
        .then()
        .statusCode(404);
  }

  @Test
  void get_availability_no_cambia_la_cantidad_del_producto() {
    int id = crearProducto("AVAIL-KEEP", 7);

    given()
        .when()
        .get("/inventory/products/" + id + "/availability?quantity=99")
        .then()
        .statusCode(200)
        .body("available", equalTo(false));

    given()
        .when()
        .get("/inventory/products/" + id)
        .then()
        .statusCode(200)
        // consultar la disponibilidad no es un movimiento de stock: la cantidad sigue igual
        .body("quantity", equalTo(7));
  }

  @Test
  void get_availability_de_un_producto_inexistente_con_cantidad_invalida_devuelve_404() {
    given()
        .when()
        .get("/inventory/products/99999999/availability?quantity=0")
        .then()
        // el producto se resuelve antes de validar la cantidad: 404 gana sobre 400
        .statusCode(404);
  }

  @Test
  void get_availability_con_cantidad_no_numerica_devuelve_400() {
    int id = crearProducto("AVAIL-NAN", 5);

    given()
        .when()
        .get("/inventory/products/" + id + "/availability?quantity=abc")
        .then()
        .statusCode(400);

    given()
        .when()
        // fuera del rango de un int: tampoco es una cantidad representable
        .get("/inventory/products/" + id + "/availability?quantity=2147483648")
        .then()
        .statusCode(400);
  }
}
