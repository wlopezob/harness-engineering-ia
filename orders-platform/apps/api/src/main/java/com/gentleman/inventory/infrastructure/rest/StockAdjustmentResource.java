package com.gentleman.inventory.infrastructure.rest;

import com.gentleman.inventory.application.usecase.AdjustStockUseCase;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

/**
 * Adapter HTTP del ajuste de varios productos en una sola operación. Vive fuera de ProductResource
 * porque la operación es sobre el inventario, no sobre un producto concreto.
 */
@Path("/inventory/stock-adjustments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class StockAdjustmentResource {

  private final AdjustStockUseCase adjustStock;

  public StockAdjustmentResource(AdjustStockUseCase adjustStock) {
    this.adjustStock = adjustStock;
  }

  @POST
  @APIResponses({
    @APIResponse(
        responseCode = "200",
        description =
            "Ajustes aplicados; devuelve cada producto con su cantidad resultante, en el orden"
                + " de la petición",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(type = SchemaType.ARRAY, implementation = ProductResponse.class))),
    @APIResponse(
        responseCode = "400",
        description =
            "Lote inválido (vacío, producto repetido, producto ausente en un ajuste, delta cero o"
                + " resultado fuera del rango admitido)",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ApiError.class))),
    @APIResponse(
        responseCode = "404",
        description = "Alguno de los productos no existe; no se aplicó ningún ajuste",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ApiError.class))),
    @APIResponse(
        responseCode = "409",
        description =
            "Alguno de los ajustes dejaría el stock en negativo; no se aplicó ningún ajuste",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ApiError.class)))
  })
  public List<ProductResponse> adjustAll(BulkStockAdjustmentRequest request) {
    List<ProductResponse> adjusted =
        adjustStock.handleAll(request == null ? List.of() : request.toDomain()).stream()
            .map(ProductResponse::from)
            .toList();

    return adjusted;
  }
}
