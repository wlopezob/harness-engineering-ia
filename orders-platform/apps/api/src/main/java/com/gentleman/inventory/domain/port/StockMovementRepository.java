package com.gentleman.inventory.domain.port;

import com.gentleman.inventory.domain.model.StockMovement;
import java.util.List;

/**
 * Puerto de salida del dominio hacia la persistencia del historial de stock. El dominio define la
 * interface; la infraestructura la implementa.
 */
public interface StockMovementRepository {

  /** Persiste el movimiento y lo devuelve con su id asignado. */
  StockMovement save(StockMovement movement);

  /** Historial de un producto, del más reciente al más antiguo. Vacío si no tiene movimientos. */
  List<StockMovement> findByProductId(Long productId);
}
