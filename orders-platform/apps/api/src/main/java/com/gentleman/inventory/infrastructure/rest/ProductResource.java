package com.gentleman.inventory.infrastructure.rest;

import com.gentleman.inventory.application.usecase.CreateProductUseCase;
import com.gentleman.inventory.application.usecase.DeleteProductUseCase;
import com.gentleman.inventory.application.usecase.GetProductUseCase;
import com.gentleman.inventory.application.usecase.ListProductsUseCase;
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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

import java.net.URI;
import java.util.List;

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

    public ProductResource(CreateProductUseCase createProduct,
                           ListProductsUseCase listProducts,
                           GetProductUseCase getProduct,
                           UpdateProductUseCase updateProduct,
                           DeleteProductUseCase deleteProduct) {
        this.createProduct = createProduct;
        this.listProducts = listProducts;
        this.getProduct = getProduct;
        this.updateProduct = updateProduct;
        this.deleteProduct = deleteProduct;
    }

    @POST
    @APIResponses({
            @APIResponse(responseCode = "201", description = "Producto creado",
                    headers = @Header(name = "Location",
                            description = "URI del producto creado",
                            schema = @Schema(type = SchemaType.STRING)),
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ProductResponse.class))),
            @APIResponse(responseCode = "400",
                    description = "Datos inválidos (nombre o sku en blanco, cantidad negativa)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ApiError.class))),
            @APIResponse(responseCode = "409",
                    description = "El SKU ya existe en el inventario",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ApiError.class)))
    })
    public Response create(CreateProductRequest request, @Context UriInfo uriInfo) {
        Product product =
                createProduct.handle(request.name(), request.sku(), request.quantity());
        URI location = uriInfo.getAbsolutePathBuilder()
                .path(String.valueOf(product.id()))
                .build();
        return Response.created(location)
                .entity(ProductResponse.from(product))
                .build();
    }

    @GET
    @APIResponse(responseCode = "200", description = "Listado de productos",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(type = SchemaType.ARRAY, implementation = ProductResponse.class)))
    public List<ProductResponse> list() {
        return listProducts.handle()
                .stream()
                .map(ProductResponse::from)
                .toList();
    }

    @GET
    @Path("/{id}")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Producto encontrado",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ProductResponse.class))),
            @APIResponse(responseCode = "404",
                    description = "No existe un producto con ese id",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ApiError.class)))
    })
    public ProductResponse getById(@PathParam("id") Long id) {
        return ProductResponse.from(getProduct.handle(id));
    }

    @PUT
    @Path("/{id}")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Producto actualizado",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ProductResponse.class))),
            @APIResponse(responseCode = "400",
                    description = "Datos inválidos (nombre en blanco, cantidad negativa)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ApiError.class))),
            @APIResponse(responseCode = "404",
                    description = "No existe un producto con ese id",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ApiError.class)))
    })
    public ProductResponse update(@PathParam("id") Long id, UpdateProductRequest request) {
        Product product = updateProduct.handle(id, request.name(), request.quantity());
        return ProductResponse.from(product);
    }

    @DELETE
    @Path("/{id}")
    @APIResponses({
            @APIResponse(responseCode = "204", description = "Producto eliminado"),
            @APIResponse(responseCode = "404",
                    description = "No existe un producto con ese id",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ApiError.class)))
    })
    public void delete(@PathParam("id") Long id) {
        deleteProduct.handle(id);
    }
}
