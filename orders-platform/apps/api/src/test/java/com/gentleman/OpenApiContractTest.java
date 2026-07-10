package com.gentleman;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class OpenApiContractTest {

    private static final Path CONTRACT = Path.of("..", "..", "contracts", "openapi.yaml");

    @Test
    void el_codigo_no_se_desvia_del_contrato_publicado() throws Exception {
        String live = RestAssured.given()
                .when().get("/q/openapi")
                .then().statusCode(200)
                .extract().asString();

        String committed = Files.readString(CONTRACT);

        assertEquals(committed.strip(), live.strip(),
                "El OpenAPI del código difiere de contracts/openapi.yaml. Regenera y commitea el contrato.");
    }


}