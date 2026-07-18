package com.gentleman.inventory.infrastructure.rest;

import com.gentleman.inventory.domain.model.ProductNotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Producto inexistente → 404 Not Found. */
@Provider
public class ProductNotFoundExceptionMapper implements ExceptionMapper<ProductNotFoundException> {

  @Override
  public Response toResponse(ProductNotFoundException exception) {
    return Response.status(Response.Status.NOT_FOUND)
        .entity(new ApiError(exception.getMessage()))
        .build();
  }
}
