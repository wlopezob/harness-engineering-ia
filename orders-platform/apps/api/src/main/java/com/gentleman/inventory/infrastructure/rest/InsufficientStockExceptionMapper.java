package com.gentleman.inventory.infrastructure.rest;

import com.gentleman.inventory.domain.model.InsufficientStockException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Salida de stock mayor a la disponible → 409 Conflict. */
@Provider
public class InsufficientStockExceptionMapper
    implements ExceptionMapper<InsufficientStockException> {

  @Override
  public Response toResponse(InsufficientStockException exception) {
    return Response.status(Response.Status.CONFLICT)
        .entity(new ApiError(exception.getMessage()))
        .build();
  }
}
