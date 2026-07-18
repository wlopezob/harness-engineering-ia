package com.gentleman.inventory.infrastructure.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
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
  void put_actualiza_nombre_y_cantidad_conservando_sku() {
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
        .body("{\"name\":\"Teclado v2\",\"quantity\":99}")
        .when()
        .put("/inventory/products/" + id)
        .then()
        .statusCode(200)
        .body("id", equalTo(id))
        .body("name", equalTo("Teclado v2"))
        .body("sku", equalTo("PUT-A"))
        .body("quantity", equalTo(99));
  }

  @Test
  void put_a_id_inexistente_devuelve_404() {
    given()
        .contentType("application/json")
        .body("{\"name\":\"x\",\"quantity\":1}")
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
        .body("{\"name\":\"   \",\"quantity\":1}")
        .when()
        .put("/inventory/products/" + id)
        .then()
        .statusCode(400)
        .body("message", notNullValue());

    given()
        .contentType("application/json")
        .body("{\"name\":\"Valido\",\"quantity\":-5}")
        .when()
        .put("/inventory/products/" + id)
        .then()
        .statusCode(400);
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
}
