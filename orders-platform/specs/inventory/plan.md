# Feature: inventory — registrar producto

Primera funcionalidad del backend. Registrar un producto (nombre, SKU, cantidad
en stock) y persistirlo en PostgreSQL, expuesto por un endpoint de creación.

## Comportamiento
- Un producto tiene: `id` (lo asigna la DB), `name`, `sku`, `quantity` (stock).
- Reglas de dominio (POJO puro, sin framework):
  - `name` obligatorio (no nulo/blanco).
  - `sku` obligatorio (no nulo/blanco).
  - `quantity` >= 0 (no negativa).
- `sku` es único en el inventario (identifica al producto). Crear con un SKU ya
  existente es un conflicto.
- Endpoint: `POST /inventory/products`
  - 201 + body del producto creado + header `Location`.
  - 400 si los datos violan una regla de dominio.
  - 409 si el SKU ya existe.
- Persistencia real en PostgreSQL. El schema lo crea **Flyway** (V1), no
  Hibernate (`database.generation=none`).

## Arquitectura (HARNESS A — hexagonal, dientes ArchUnit)
La entidad JPA **no** puede ser el modelo de dominio: ArchUnit prohíbe `jakarta..`
en `domain`. Son dos clases con mapeo en el adapter de persistencia.

```
inventory.domain.model.Product            POJO + reglas (cero framework)
inventory.domain.model.DuplicateSkuException
inventory.domain.port.ProductRepository   interface (puerto)
inventory.application.usecase.CreateProductUseCase   orquesta dominio vía puerto
inventory.infrastructure.persistence.ProductEntity        @Entity JPA
inventory.infrastructure.persistence.ProductRepositoryJpa implements puerto
inventory.infrastructure.rest.ProductResource             adapter HTTP
inventory.infrastructure.rest.{CreateProductRequest,ProductResponse,ApiError}
inventory.infrastructure.rest.{IllegalArgument,DuplicateSku}ExceptionMapper
```
Flecha de dependencia: infrastructure → application → domain. Nunca al revés.

## Casos de test (en orden — esto guía el TDD red→green→triangulate)

### A) Dominio — `Product` (unit puro, sin Quarkus)
1. `create` con datos válidos → producto con name/sku/quantity y `id` nulo.
2. `create` con nombre en blanco → `IllegalArgumentException`.
3. `create` con sku en blanco → `IllegalArgumentException`.
4. `create` con cantidad negativa → `IllegalArgumentException`.

### B) Aplicación — `CreateProductUseCase` (unit puro, fake del puerto)
5. `handle` con datos válidos → guarda vía puerto y devuelve producto con `id`.
6. `handle` con SKU ya existente (`existsBySku`=true) → `DuplicateSkuException`.

### C) Infraestructura — `ProductResource` (@QuarkusTest + rest-assured + Postgres real vía Dev Services)
7. `POST` válido → 201, header `Location`, body con `id` generado por la DB
   (prueba de persistencia real).
8. `POST` nombre en blanco → 400.
9. `POST` cantidad negativa → 400.
10. `POST` SKU duplicado → 409.

### Dientes que se mantienen verdes
- `ArchitectureTest` (capas + dominio sin framework).

## Primer paso
Escribir `ProductTest` (caso 1), verlo ROJO (no existe la clase), crear
`Product` mínimo → VERDE; luego triangular con casos 2–4.

---

# Feature 2: listar productos

Ver el inventario: un endpoint que liste todos los productos registrados,
mostrando id, nombre, SKU y cantidad.

## Comportamiento
- Endpoint: `GET /inventory/products` → 200 con un array JSON de productos
  (`id`, `name`, `sku`, `quantity`), ordenado por `id`.
- Sin productos → array vacío `[]`.

## Diseño
- Puerto: nuevo método `List<Product> findAll()`.
- Caso de uso: `application.usecase.ListProductsUseCase` (orquesta el puerto).
- Adapter JPA: `findAll()` con JPQL `order by id`, mapeando Entity→Product.
- Recurso: `@GET` que devuelve `List<ProductResponse>` **directamente** (no
  `Response`), para que smallrye-openapi genere un contrato fiel (array de
  ProductResponse).

## Dobles de test (HARNESS D)
Se usa **Mockito** para el puerto (no fakes a mano). Se corrige también
`CreateProductUseCaseTest` (sesión 1 usaba un fake a mano → se migra a Mockito).

## Casos de test (en orden)
### A) Aplicación — `ListProductsUseCase` (unit, Mockito)
1. sin productos (`findAll`=lista vacía) → lista vacía.
2. con productos → devuelve todos.

### B) Infra REST — `ProductResource` (@QuarkusTest, Postgres real)
3. `GET` tras crear productos → 200 y el array los contiene con sus campos.

## Contrato (HARNESS B — dientes: OpenApiContractTest)
Cambiar la superficie rompe `OpenApiContractTest` (compara string exacto vs
`/q/openapi`). Tras añadir el `GET`: regenerar el contrato
`curl -s localhost:8080/q/openapi -o ../../contracts/openapi.yaml`, revisar el
diff (debe ser aditivo: +GET, +schema ProductResponse) y commitear.

## Primer paso
Migrar `CreateProductUseCaseTest` a Mockito (refactor, sigue verde). Luego
escribir `ListProductsUseCaseTest` (caso 1) → ROJO.

---

# Feature 3: consultar un producto por id

Dado un id, devolver ese producto (id, nombre, SKU, cantidad). Si no existe,
responder apropiadamente → **404** (no se inventa el producto ni se devuelve 200
vacío).

## Comportamiento
- Endpoint: `GET /inventory/products/{id}` → 200 con el producto.
- Si no existe → 404 con cuerpo `ApiError` (`{message}`).

## Diseño
- Puerto: `Optional<Product> findById(Long id)` (HARNESS C: lo ausente es
  `Optional`, nunca `null`).
- Caso de uso: `application.usecase.GetProductUseCase` →
  `findById(id).orElseThrow(ProductNotFoundException::new)`.
- Dominio: `ProductNotFoundException` (como `DuplicateSkuException`).
- Adapter: `ProductNotFoundExceptionMapper` → 404 (igual que el de 409).
- Recurso: `@GET @Path("/{id}")` devuelve `ProductResponse` (200).

## Casos de test (en orden)
### A) Aplicación — `GetProductUseCase` (unit, Mockito local)
1. existe (`findById`=Optional.of) → devuelve el producto.
2. no existe (`findById`=Optional.empty) → `ProductNotFoundException`.

### B) Infra REST — `ProductResource` (@QuarkusTest, Postgres real)
3. `GET /{id}` de un producto creado → 200 con sus campos.
4. `GET /{id}` inexistente → 404 con `message` (no el 404 vacío del framework).

## Contrato (HARNESS B)
Tras añadir el `GET /{id}`: regenerar `contracts/openapi.yaml` y revisar el diff.

## Primer paso
Escribir `GetProductUseCaseTest` (caso 1) → ROJO (no existe el caso de uso ni
`findById`).

---

# Feature 4: contrato fiel (HARNESS B — fidelidad)

El contrato debe describir el comportamiento REAL, no solo el happy path. Hoy el
POST sale como `200` con `schema: {}` (body sin describir) y los errores no
figuran. Hay que reflejar todos los códigos y cuerpos vía `@APIResponse`.

## Lo que debe quedar en el contrato
- `POST /inventory/products`: **201** con `ProductResponse` (+ header `Location`),
  **400** `ApiError`, **409** `ApiError`. (Quitar el `200`/empty.)
- `GET /inventory/products/{id}`: **200** `ProductResponse`, **404** `ApiError`.
- `GET /inventory/products`: **200** array de `ProductResponse` (ya es fiel).

## Cómo (dientes existentes)
- `OpenApiFidelityTest`: falla si el contrato tiene `schema: {}`.
- `OpenApiContractTest`: falla si generado ≠ committeado.
- Se anota el recurso con `@APIResponse`/`@Content`/`@Schema`/`@Header`
  (de `quarkus-smallrye-openapi`, sin dependencia nueva). Como el POST devuelve
  `Response`, declarar `@APIResponse` reemplaza el `200` inferido por defecto.
- Regenerar `contracts/openapi.yaml` y commitear.

## Primer paso
Ver `OpenApiFidelityTest` ROJO (`schema: {}`). Anotar `create`/`getById`,
regenerar contrato → verde.

---

# Feature 5: editar y eliminar un producto

Gestionar un producto existente: editar su nombre y su cantidad en stock, y
eliminarlo. El **SKU no se cambia** (es el identificador). Si no existe → 404.

## Comportamiento
- `PUT /inventory/products/{id}` con body `{name, quantity}` → 200 con el
  producto actualizado. El SKU se conserva.
  - 400 si datos inválidos (nombre en blanco, cantidad negativa).
  - 404 si no existe.
- `DELETE /inventory/products/{id}` → 204 No Content.
  - 404 si no existe.

## Diseño
- Dominio: `Product.update(name, quantity)` → **nuevo** Product (inmutable) con el
  mismo id+sku y el nombre/cantidad validados (mismas reglas que `create`, sin
  tocar sku). Se extraen validadores `requireName`/`requireQuantity` reutilizados.
- Puerto: `Product update(Product)` y `boolean deleteById(Long)`.
- Casos de uso: `UpdateProductUseCase`, `DeleteProductUseCase`.
- Adapter Panache: `update` busca la entity gestionada y muta name+quantity
  (dirty checking); `deleteById` delega en `products.deleteById(id)`.
- Recurso: `@PUT @Path("/{id}")` (DTO `UpdateProductRequest{name,quantity}`),
  `@DELETE @Path("/{id}")` (void → 204). Anotados con `@APIResponse` (fidelidad).

## Casos de test (en orden)
### A) Dominio — `Product.update` (unit puro)
1. update válido → nuevo Product, mismo id+sku, nuevo name+quantity.
2. update rechaza nombre en blanco → IAE.
3. update rechaza cantidad negativa → IAE.

### B) Aplicación (unit, Mockito local)
4. `UpdateProductUseCase` existe → busca, actualiza, devuelve.
5. `UpdateProductUseCase` no existe → `ProductNotFoundException`.
6. `DeleteProductUseCase` existe (`deleteById`=true) → ok, verifica borrado.
7. `DeleteProductUseCase` no existe (`deleteById`=false) → `ProductNotFoundException`.

### C) Infra REST (@QuarkusTest, Postgres real)
8. `PUT /{id}` existente → 200, name+quantity nuevos, sku igual.
9. `PUT /{id}` inexistente → 404.
10. `PUT /{id}` inválido (nombre en blanco / cantidad negativa) → 400.
11. `DELETE /{id}` existente → 204 y luego `GET /{id}` → 404.
12. `DELETE /{id}` inexistente → 404.

## Contrato
Regenerar; PUT (200/400/404) y DELETE (204/404) documentados vía `@APIResponse`.

## Primer paso
Escribir los casos 1–3 de `ProductTest` (update) → ROJO.
