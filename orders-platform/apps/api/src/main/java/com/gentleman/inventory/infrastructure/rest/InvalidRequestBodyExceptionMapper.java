package com.gentleman.inventory.infrastructure.rest;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Cuerpo de petición con un campo no admitido (p. ej. "quantity" en el PUT) o con un valor de tipo
 * incompatible → 400 Bad Request con ApiError, como el resto de los errores de esta API. Sustituye
 * al mapper built-in de Quarkus para MismatchedInputException, que responde 400 sin cuerpo.
 */
@Provider
public class InvalidRequestBodyExceptionMapper
    implements ExceptionMapper<MismatchedInputException> {

  @Override
  public Response toResponse(MismatchedInputException exception) {
    return Response.status(Response.Status.BAD_REQUEST)
        .entity(new ApiError(message(exception)))
        .build();
  }

  private static String message(MismatchedInputException exception) {
    if (exception instanceof UnrecognizedPropertyException unrecognized) {
      return "Campo no admitido en el cuerpo: '" + unrecognized.getPropertyName() + "'";
    }
    return "El cuerpo de la petición no es válido";
  }
}
