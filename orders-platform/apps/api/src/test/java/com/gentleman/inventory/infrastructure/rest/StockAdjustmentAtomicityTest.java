package com.gentleman.inventory.infrastructure.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.gentleman.inventory.domain.model.StockMovement;
import com.gentleman.inventory.domain.port.StockMovementRepository;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Atomicidad del ajuste (github-24): el cambio de cantidad y el registro del movimiento se
 * persisten juntos o no se persiste ninguno. Clase separada: el @InjectMock sustituye el puerto
 * solo para estos tests (HARNESS D) y no contamina ProductResourceTest.
 */
@QuarkusTest
class StockAdjustmentAtomicityTest {

  @InjectMock StockMovementRepository movements;

  @Test
  void si_falla_el_registro_del_movimiento_el_stock_no_cambia() {
    int id =
        given()
            .contentType("application/json")
            .body("{\"name\":\"Teclado\",\"sku\":\"ATOMIC-1\",\"quantity\":10}")
            .when()
            .post("/inventory/products")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    when(movements.save(any(StockMovement.class)))
        .thenThrow(new RuntimeException("fallo simulado al registrar el movimiento"));

    given()
        .contentType("application/json")
        .body("{\"delta\":5}")
        .when()
        .post("/inventory/products/" + id + "/stock-adjustments")
        .then()
        .statusCode(500);

    given()
        .when()
        .get("/inventory/products/" + id)
        .then()
        .statusCode(200)
        // el ajuste no se persistió sin su movimiento
        .body("quantity", equalTo(10));
  }

  @Test
  void si_falla_el_movimiento_del_segundo_producto_ninguno_cambia_su_stock() {
    int teclado =
        given()
            .contentType("application/json")
            .body("{\"name\":\"Teclado\",\"sku\":\"ATOMIC-BULK-1\",\"quantity\":10}")
            .when()
            .post("/inventory/products")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    int mouse =
        given()
            .contentType("application/json")
            .body("{\"name\":\"Mouse\",\"sku\":\"ATOMIC-BULK-2\",\"quantity\":4}")
            .when()
            .post("/inventory/products")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    when(movements.save(any(StockMovement.class)))
        .thenReturn(new StockMovement(1L, (long) teclado, 5, 10, 15, Instant.EPOCH))
        .thenThrow(new RuntimeException("fallo simulado al registrar el segundo movimiento"));

    given()
        .contentType("application/json")
        .body(
            "{\"adjustments\":[{\"productId\":"
                + teclado
                + ",\"delta\":5},{\"productId\":"
                + mouse
                + ",\"delta\":-2}]}")
        .when()
        .post("/inventory/stock-adjustments")
        .then()
        .statusCode(500);

    given()
        .when()
        .get("/inventory/products/" + teclado)
        .then()
        .statusCode(200)
        .body("quantity", equalTo(10));
    given()
        .when()
        .get("/inventory/products/" + mouse)
        .then()
        .statusCode(200)
        .body("quantity", equalTo(4));
  }
}
