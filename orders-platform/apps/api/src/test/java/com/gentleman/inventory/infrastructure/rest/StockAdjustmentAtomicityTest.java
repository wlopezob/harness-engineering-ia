package com.gentleman.inventory.infrastructure.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.gentleman.inventory.domain.model.StockMovement;
import com.gentleman.inventory.domain.port.StockMovementRepository;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
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
}
