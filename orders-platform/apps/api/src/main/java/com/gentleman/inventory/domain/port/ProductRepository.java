package com.gentleman.inventory.domain.port;

import com.gentleman.inventory.domain.model.Product;
import java.util.List;
import java.util.Optional;

/**
 * Puerto de salida del dominio hacia la persistencia. El dominio define la interface; la
 * infraestructura la implementa. Las consultas devuelven solo productos ACTIVE: un producto
 * eliminado (DELETED) no existe para el resto del sistema.
 */
public interface ProductRepository {

  /** Persiste el producto y lo devuelve con su id asignado. */
  Product save(Product product);

  /** Indica si ya existe un producto con ese SKU. */
  boolean existsBySku(String sku);

  /** Devuelve todos los productos activos, ordenados por id. */
  List<Product> findAll();

  /** Busca un producto activo por su id; vacío si no existe o está eliminado. */
  Optional<Product> findById(Long id);

  /** Actualiza un producto existente (por su id), incluido su estado, y lo devuelve actualizado. */
  Product update(Product product);
}
