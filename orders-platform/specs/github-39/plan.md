# github-39 — Paginar el listado de productos

## Work item

* Fuente: GitHub Issue
  [#39](https://github.com/wlopezob/harness-engineering-ia/issues/39)
* Título: *feat(inventory): paginate product listing*

El issue guarda el QUÉ (criterios de aceptación y fuera de alcance). Este
documento guarda el CÓMO.

## Entendimiento técnico (estado del working copy)

Punto de partida: `main` @ `daf5791`, rama `feat/gh-39-paginate-products`,
árbol limpio.

* `GET /inventory/products` hoy es `ProductResource.list()` →
  `ListProductsUseCase.handle()` → `ProductRepository.findAll()` →
  `products.list("status", Sort.by("id"), ACTIVE)`. Ya ordena por `id`
  ascendente (determinístico); no hay que introducir ningún criterio de orden
  nuevo, solo conservarlo.
* `findAll()` **no se usa en ningún otro sitio** (solo en `ListProductsUseCase`
  y su test), así que se puede reemplazar en vez de dejarlo colgando.
* Patrón de valor de dominio con validación en el constructor canónico:
  `StockAvailability` (record) valida `requestedQuantity > 0` en un
  constructor compacto, no en un factory (lección D-029: en un record el
  canónico es público). Se reutiliza el mismo patrón para los parámetros de
  paginación.
* Patrón de error 400: `IllegalArgumentException` → `IllegalArgumentExceptionMapper`
  → `ApiError(message)`. No hace falta un mapper nuevo.
* Precedente D-031 (github-37): un `@QueryParam` tipado como `int` puede
  convertir un valor no numérico en un **404** en vez de un 400 (RESTEasy trata
  el fallo de conversión como si el recurso no existiera). Por eso `quantity`
  se recibe como `String` y se parsea a mano en el resource. Se aplica el
  mismo patrón a `page` y `size`.
* Tests existentes que golpean `GET /inventory/products` **sin parámetros** y
  asumen que la respuesta es un array JSON en la raíz:
  - `ProductResourceTest` línea ~411 (`delete_de_producto_devuelve_404_en_todos_los_subrecursos...`,
    verifica que un producto borrado no aparece: `body("sku", not(hasItem(...)))`)
  - `ProductResourceTest` línea ~528 (`get_lista_...`, verifica
    `body("sku", hasItems("LIST-A", "LIST-B"))` y accesos por `find { it.sku == ... }`)

  Al cambiar el shape de 200 (array → envelope `{items, page, size,
  totalElements, totalPages, hasNext}`), ambos tests rompen por contrato, no
  por regresión. Se actualizan en el mismo cambio: el path de aserción pasa a
  `items.sku` / `items.find { ... }`, y la request agrega `?size=100` para
  seguir viendo todos los productos creados por la suite en una sola página
  (la suite completa de `apps/api` crea ~50 productos vía HTTP entre
  `ProductResourceTest`, `BulkStockAdjustmentResourceTest` y
  `StockAdjustmentAtomicityTest`; 100 deja margen).
* `contracts/openapi.yaml` es generado (`curl localhost:8080/q/openapi`), no se
  edita a mano: se regenera después de escribir el código, como en cada
  feature previa (HARNESS B).

## Decisiones acordadas con el usuario

### 1. `page` es 0-based

`page=0` es la primera página. Consistente con `io.quarkus.panache.common.Page.of(index, size)`,
que también es 0-based: el adapter pasa el valor tal cual, sin traducir índices.

### 2. `size`: default 20, máximo 100

Sin parámetros, la respuesta trae como máximo 20 productos. `size` fuera del
rango `(0, 100]` es un 400, no un recorte silencioso. Los tests existentes que
hoy asumen "todo el listado en una sola respuesta" piden `?size=100`
explícitamente (ver arriba).

### 3. Shape de la respuesta: `items/page/size/totalElements/totalPages/hasNext`

```json
{
  "items": [ { "id": 1, "name": "...", "sku": "...", "quantity": 10 } ],
  "page": 0,
  "size": 20,
  "totalElements": 42,
  "totalPages": 3,
  "hasNext": true
}
```

`totalPages` y `hasNext` se derivan de `totalElements`/`page`/`size` — no se
guardan como estado independiente, igual que `StockAvailability.available()`
y `missingQuantity()`. Un estado incoherente (`hasNext=true` en la última
página) es irrepresentable.

## Cambios propuestos

### Dominio (`domain.model`) — cero framework

* **`PageRequest`** (record nuevo): `record PageRequest(int page, int size)`.
  - Constructor compacto valida: `page < 0` → `IllegalArgumentException`
    ("La página no puede ser negativa"); `size <= 0` → IAE ("El tamaño de
    página debe ser mayor que cero"); `size > MAX_SIZE` → IAE ("El tamaño de
    página no puede superar {MAX_SIZE}").
  - Constantes públicas: `DEFAULT_SIZE = 20`, `MAX_SIZE = 100`.
* **`ProductPage`** (record nuevo): `record ProductPage(List<Product> items,
  int page, int size, long totalElements)`.
  - Métodos derivados: `totalPages()` (`size == 0` no puede ocurrir, ya
    validado por `PageRequest`; `ceil(totalElements / (double) size)`; con
    `totalElements == 0` da **0**, no 1 — resuelto en TDD: no hay páginas que
    contar sin elementos, y `page=0` sigue siendo una respuesta vacía válida)
    y `hasNext()` (`((long) page + 1) * size < totalElements`).

### Puerto (`domain.port.ProductRepository`)

* Reemplaza `List<Product> findAll()` por:
  - `List<Product> findPage(PageRequest pageRequest)` — productos ACTIVE,
    ordenados por `id`, ventana `page`/`size`.
  - `long countActive()` — total de productos ACTIVE (para `totalElements`).

### Aplicación (`application.usecase.ListProductsUseCase`)

* `handle(int page, int size)`:
  1. Construye `new PageRequest(page, size)` (lanza IAE si es inválido → 400
     vía el mapper existente).
  2. `List<Product> items = repository.findPage(pageRequest)`.
  3. `long total = repository.countActive()`.
  4. Devuelve `new ProductPage(items, pageRequest.page(), pageRequest.size(), total)`.

### Infraestructura — persistencia (`infrastructure.persistence`)

* `ProductRepositoryAdapter`:
  - `findPage(PageRequest pr)` → `products.find("status", Sort.by("id"), ACTIVE)
    .page(Page.of(pr.page(), pr.size())).list()`, mapeado con `toDomain`.
  - `countActive()` → `products.count("status", ACTIVE)`.
  - Se elimina `findAll()`.

### Infraestructura — REST (`infrastructure.rest`)

* **`ProductPageResponse`** (record nuevo, mismo patrón que
  `StockAvailabilityResponse`): `record ProductPageResponse(List<ProductResponse>
  items, int page, int size, long totalElements, int totalPages, boolean
  hasNext)` con `from(ProductPage)`.
* `ProductResource.list(...)`:
  - Recibe `@QueryParam("page") String page` y `@QueryParam("size") String size`
    (mismo motivo que `quantity` en github-37: evitar el 404 falso de RESTEasy
    ante un valor no numérico).
  - Parseo en el borde: ausente → default (`"0"` / `String.valueOf(PageRequest.DEFAULT_SIZE)`);
    no numérico → `IllegalArgumentException` ("La página/el tamaño debe ser un
    número entero: {valor}") → 400 vía el mapper existente.
  - `@Parameter` + `@Schema(type = INTEGER, format = "int32", defaultValue = "0"/"20")`
    para que el contrato generado documente el default como entero (evita el
    problema de github-37 con `@DefaultValue` produciendo un default string en
    un schema integer).
  - Body de éxito: `200` con `ProductPageResponse`.
  - Body de error: `400` con `ApiError` (página negativa, tamaño ≤ 0, tamaño >
    máximo, o valor no numérico).

### Contrato (`contracts/openapi.yaml`)

Se regenera con `curl -s localhost:8080/q/openapi -o ../../contracts/openapi.yaml`
después de implementar, y se revisa el diff: nuevos parámetros `page`/`size`
en el `GET /inventory/products`, nuevo schema `ProductPageResponse`, y la
respuesta `400` documentada (hoy el `GET` no documenta ningún error).

## Fuera de alcance (igual que el issue)

* Filtros por campo (nombre, sku, status).
* `sort=` configurable por el cliente.
* Cambios en `GET /{id}` ni otros endpoints de inventory.

## Casos de test (orden sugerido, RED → GREEN → triangulate → refactor)

### Dominio

1. `PageRequest`: página negativa → IAE.
2. `PageRequest`: tamaño cero → IAE.
3. `PageRequest`: tamaño negativo → IAE.
4. `PageRequest`: tamaño mayor al máximo → IAE.
5. `PageRequest`: página y tamaño válidos → se construye sin excepción.
6. `ProductPage`: `totalElements=0` → `hasNext()=false`.
7. `ProductPage`: última página exacta (`(page+1)*size == totalElements`) →
   `hasNext()=false`.
8. `ProductPage`: página intermedia (`(page+1)*size < totalElements`) →
   `hasNext()=true`.
9. `ProductPage`: `totalPages()` con división exacta y con resto (ceil).

### Aplicación (`ListProductsUseCaseTest`, con mock del puerto)

10. `handle(0, 20)` sin productos → `items` vacío, `totalElements=0`,
    `hasNext=false`.
11. `handle(0, 20)` con productos → delega en `findPage`/`countActive` y
    arma el `ProductPage` correctamente.
12. `handle` con página/tamaño inválido → propaga la `IllegalArgumentException`
    de `PageRequest` (no la envuelve ni la silencia).

### REST (`ProductResourceTest`, `@QuarkusTest`)

13. `GET /inventory/products` sin parámetros → 200, `page=0`, `size=20`,
    shape `items/totalElements/totalPages/hasNext` presente.
14. `GET /inventory/products?page=-1` → 400 con `ApiError`.
15. `GET /inventory/products?size=0` → 400.
16. `GET /inventory/products?size=101` (por encima del máximo) → 400.
17. `GET /inventory/products?page=abc` → 400 (no 404).
18. `GET /inventory/products?page=1&size=1` con ≥2 productos conocidos →
    la segunda página no repite ni salta el primero (orden determinístico).
19. Actualizar los 2 tests existentes que listan sin parámetros (ver arriba)
    al nuevo shape (`items.*`) y a `?size=100`.

## Verificación

```bash
./harness verify
```

`PageRequest`/`ProductPage` son lógica de dominio nueva con ramas (validación,
`hasNext`, `totalPages`); si el plan de mutación lo pide:

```bash
./harness mutation
```
