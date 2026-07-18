package com.gentleman.inventory.infrastructure.rest;

import com.gentleman.inventory.domain.model.DuplicateSkuException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** SKU ya existente → 409 Conflict. */
@Provider
public class DuplicateSkuExceptionMapper implements ExceptionMapper<DuplicateSkuException> {

  @Override
  public Response toResponse(DuplicateSkuException exception) {
    return Response.status(Response.Status.CONFLICT)
        .entity(new ApiError(exception.getMessage()))
        .build();
  }
}
