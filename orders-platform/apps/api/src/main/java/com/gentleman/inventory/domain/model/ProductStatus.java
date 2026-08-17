package com.gentleman.inventory.domain.model;

/** Estado de un producto. Un producto eliminado no se borra: cambia a DELETED. */
public enum ProductStatus {
  ACTIVE,
  DELETED
}
