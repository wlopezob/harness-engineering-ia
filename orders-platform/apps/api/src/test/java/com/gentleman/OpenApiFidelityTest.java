package com.gentleman;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Diente de fidelidad de HARNESS B: el contrato no debe tener bodies sin
 * describir (schema: {}). Un body vacío = el contrato le miente al front.
 */
class OpenApiFidelityTest {

    private static final Path CONTRACT = Path.of("..", "..", "contracts", "openapi.yaml");

    @Test
    void el_contrato_no_tiene_bodies_sin_describir() throws Exception {
        String contract = Files.readString(CONTRACT);

        boolean tieneSchemaVacio = contract.lines()
                .anyMatch(linea -> linea.strip().equals("schema: {}"));

        assertFalse(tieneSchemaVacio,
                "El contrato tiene 'schema: {}' (un body sin describir). Tipa la "
              + "respuesta o anótala con @APIResponse, y regenera el contrato.");
    }
}