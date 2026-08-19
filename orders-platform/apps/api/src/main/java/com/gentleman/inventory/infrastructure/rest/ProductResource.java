package com.gentleman.inventory.infrastructure.rest;

import com.gentleman.inventory.application.usecase.AdjustStockUseCase;
import com.gentleman.inventory.application.usecase.CheckStockAvailabilityUseCase;
import com.gentleman.inventory.application.usecase.CreateProductUseCase;
import com.gentleman.inventory.application.usecase.DeleteProductUseCase;
import com.gentleman.inventory.application.usecase.GetProductUseCase;
import com.gentleman.inventory.application.usecase.ListProductsUseCase;
import com.gentleman.inventory.application.usecase.ListStockMovementsUseCase;
import com.gentleman.inventory.application.usecase.UpdateProductUseCase;
import com.gentleman.inventory.domain.model.Product;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

/** Adapter HTTP del inventario. */
@Path("/inventory/products")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProductResource {

  private final CreateProductUseCase createProduct;
  private final ListProductsUseCase listProducts;
  private final GetProductUseCase getProduct;
  private final UpdateProductUseCase updateProduct;
  private final DeleteProductUseCase deleteProduct;
  private final AdjustStockUseCase adjustStock;
  private final ListStockMovementsUseCase listStockMovements;
  private final CheckStockAvailabilityUseCase checkStockAvailability;

  public ProductResource(
      CreateProductUseCase createProduct,
      ListProductsUseCase listProducts,
      GetProductUseCase getProduct,
      UpdateProductUseCase updateProduct,
      DeleteProductUseCase deleteProduct,
      AdjustStockUseCase adjustStock,
      ListStockMovementsUseCase listStockMovements,
      CheckStockAvailabilityUseCase checkStockAvailability) {
    this.createProduct = createProduct;
    this.listProducts = listProducts;
    this.getProduct = getProduct;
    this.updateProduct = updateProduct;
    this.deleteProduct = deleteProduct;
    this.adjustStock = adjustStock;
    this.listStockMovements = listStockMovements;
    this.checkStockAvailability = checkStockAvailability;
  }

  @POST
  @APIResponses({
    @APIResponse(
        responseCode = "201",
        description = "Producto creado",
        headers =
            @Header(
                name = "Location",
                description = "URI del producto creado",
                schema = @Schema(type = SchemaType.STRING)),
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ProductResponse.class))),
    @APIResponse(
        responseCode = "400",
        description = "Datos inválidos (nombre o sku en blanco, cantidad negativa)",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ApiError.class))),
    @APIResponse(
        responseCode = "409",
        description = "El SKU ya existe en el inventario",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ApiError.class)))
  })
  public Response create(CreateProductRequest request, @Context UriInfo uriInfo) {
    Product product = createProduct.handle(request.name(), request.sku(), request.quantity());
    URI location = uriInfo.getAbsolutePathBuilder().path(String.valueOf(product.id())).build();
    return Response.created(location).entity(ProductResponse.from(product)).build();
  }

  @GET
  @APIResponse(
      responseCode = "200",
      description = "Listado de productos",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(type = SchemaType.ARRAY, implementation = ProductResponse.class)))
  public List<ProductResponse> list() {
    return listProducts.handle().stream().map(ProductResponse::from).toList();
  }

  @GET
  @Path("/{id}")
  @APIResponses({
    @APIResponse(
        responseCode = "200",
        description = "Producto encontrado",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ProductResponse.class))),
    @APIResponse(
        responseCode = "404",
        description = "No existe un producto con ese id",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ApiError.class)))
  })
  public ProductResponse getById(@PathParam("id") Long id) {
    return ProductResponse.from(getProduct.handle(id));
  }

  @PUT
  @Path("/{id}")
  @APIResponses({
    @APIResponse(
        responseCode = "200",
        description = "Producto actualizado",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ProductResponse.class))),
    @APIResponse(
        responseCode = "400",
        description = "Datos inválidos (nombre en blanco, o un campo no admitido como quantity)",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ApiError.class))),
    @APIResponse(
        responseCode = "404",
        description = "No existe un producto con ese id",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ApiError.class)))
  })
  public ProductResponse update(@PathParam("id") Long id, UpdateProductRequest request) {
    Product product = updateProduct.handle(id, request.name());
    return ProductResponse.from(product);
  }

  @POST
  @Path("/{id}/stock-adjustments")
  @APIResponses({
    @APIResponse(
        responseCode = "200",
        description = "Stock ajustado; devuelve el producto con la cantidad resultante",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ProductResponse.class))),
    @APIResponse(
        responseCode = "400",
        description = "Ajuste inválido (delta cero o resultado fuera del rango admitido)",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ApiError.class))),
    @APIResponse(
        responseCode = "404",
        description = "No existe un producto con ese id",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ApiError.class))),
    @APIResponse(
        responseCode = "409",
        description = "La salida dejaría el stock en negativo",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ApiError.class)))
  })
  public ProductResponse adjustStock(@PathParam("id") Long id, StockAdjustmentRequest request) {
    return ProductResponse.from(adjustStock.handle(id, request.delta()));
  }

  @GET
  @Path("/{id}/stock-movements")
  @APIResponses({
    @APIResponse(
        responseCode = "200",
        description = "Historial de movimientos de stock, del más reciente al más antiguo",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema =
                    @Schema(
                        type = SchemaType.ARRAY,
                        implementation = StockMovementResponse.class))),
    @APIResponse(
        responseCode = "404",
        description = "No existe un producto con ese id",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ApiError.class)))
  })
  public List<StockMovementResponse> stockMovements(@PathParam("id") Long id) {
    return listStockMovements.handle(id).stream().map(StockMovementResponse::from).toList();
  }

  @GET
  @Path("/{id}/availability")
  @APIResponses({
    @APIResponse(
        responseCode = "200",
        description =
            "Disponibilidad del producto para la cantidad solicitada; alcance o no, la consulta"
                + " se resolvió",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = StockAvailabilityResponse.class))),
    @APIResponse(
        responseCode = "400",
        description = "Cantidad solicitada inválida (ausente, no numérica, cero o negativa)",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ApiError.class))),
    @APIResponse(
        responseCode = "404",
        description = "No existe un producto con ese id",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ApiError.class)))
  })
  public StockAvailabilityResponse availability(
      @PathParam("id") Long id,
      @Parameter(
              name = "quantity",
              in = ParameterIn.QUERY,
              required = true,
              description = "Cantidad solicitada; debe ser mayor que cero",
              schema = @Schema(type = SchemaType.INTEGER, format = "int32"))
          @QueryParam("quantity")
          String quantity) {
    return StockAvailabilityResponse.from(
        checkStockAvailability.handle(id, parseQuantity(quantity)));
  }

  /**
   * La conversión vive aquí, en el borde: con un parámetro int, JAX-RS convierte el fallo en un 404
   * y la API diría "no existe el producto" cuando el producto sí existe. Un texto que no es un
   * entero es una petición malformada (400); la regla de negocio (mayor que cero) sigue viviendo en
   * el dominio. Ausente equivale a cero, así que lo rechaza esa misma regla y no hay un segundo
   * camino de validación.
   */
  private static int parseQuantity(String quantity) {
    if (quantity == null) {
      return 0; // ausente equivale a cero, y cero lo rechaza la regla del dominio
    }
    try {
      return Integer.parseInt(quantity.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "La cantidad solicitada debe ser un número entero: " + quantity, e);
    }
  }

  @DELETE
  @Path("/{id}")
  @APIResponses({
    @APIResponse(responseCode = "204", description = "Producto eliminado"),
    @APIResponse(
        responseCode = "404",
        description = "No existe un producto con ese id",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ApiError.class)))
  })
  public void delete(@PathParam("id") Long id) {
    deleteProduct.handle(id);
  }
}
