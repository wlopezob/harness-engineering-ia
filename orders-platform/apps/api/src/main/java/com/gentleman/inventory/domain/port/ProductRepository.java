package com.gentleman.inventory.domain.port;

import com.gentleman.inventory.domain.model.Product;

import java.util.List;
import java.util.Optional;

/**
 * Puerto de salida del dominio hacia la persistencia.
 * El dominio define la interface; la infraestructura la implementa.
 */
public interface ProductRepository {

    /** Persiste el producto y lo devuelve con su id asignado. */
    Product save(Product product);

    /** Indica si ya existe un producto con ese SKU. */
    boolean existsBySku(String sku);

    /** Devuelve todos los productos registrados, ordenados por id. */
    List<Product> findAll();

    /** Busca un producto por su id; vacío si no existe. */
    Optional<Product> findById(Long id);

    /** Actualiza un producto existente (por su id) y lo devuelve actualizado. */
    Product update(Product product);

    /** Elimina el producto con ese id; true si existía y se eliminó. */
    boolean deleteById(Long id);
}
