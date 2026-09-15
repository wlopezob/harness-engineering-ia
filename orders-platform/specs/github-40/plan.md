# github-40 — Señalar productos bajo un umbral de stock

## Work item

* Fuente: GitHub Issue
  [#40](https://github.com/wlopezob/harness-engineering-ia/issues/40)
* Título: *feat(inventory): flag products below a stock threshold*

El issue guarda el QUÉ (criterios de aceptación y fuera de alcance). Este
documento guarda el CÓMO.

## Entendimiento técnico (estado del working copy)

Punto de partida: `main` @ `6a8c4ca`, rama `feat/gh-40-low-stock-products`,
árbol limpio.

* `ProductRepository` (puerto) ya tiene el molde exacto para agregar una
  consulta filtrada: `findPage(PageRequest)` y `countActive()` (github-39)
  filtran por `status = ACTIVE` directamente en la query de Panache, sin traer
  todo el catálogo a memoria. La consulta de este issue (`quantity <=
  threshold`, solo `ACTIVE`) sigue el mismo molde: un método nuevo de puerto +
  adapter, no un filtro en memoria sobre `findPage`.
* Patrón de "objeto de valor que valida en el constructor canónico" ya
  establecido tres veces: `StockAvailability` (D-031, `requestedQuantity >
  0`), `PageRequest` (D-032, `page`/`size`) y `StockAdjustmentBatch` (D-029).
  El umbral de este issue es exactamente ese caso: un único `int` con una
  regla de validación (no negativo) que debe vivir en dominio, no en el
  resource.
* Precedente D-031/D-032 sobre `@QueryParam`: un parámetro tipado `int`
  convierte un valor no numérico en **404** (RESTEasy trata el fallo de
  conversión como si el recurso no existiera) en vez del 400 que pide un
  parámetro malformado. `threshold` se recibe como `String` y se parsea a
  mano en el resource, igual que `quantity`, `page` y `size`.
* `findById` y `findPage` ya excluyen `DELETED` filtrando `status = ACTIVE`
  en la query (D-024). El nuevo método de repositorio reutiliza el mismo
  filtro: no hace falta un caso especial para productos eliminados.
* `ListStockMovementsUseCase` + `GET /{id}/stock-movements` (y
  `CheckStockAvailabilityUseCase` + `GET /{id}/availability`) son el molde de
  un endpoint de solo lectura que responde con una lista/objeto simple sin
  paginar. `GET /low-stock` sigue el mismo molde: no es el listado general
  (que sí pagina desde github-39), es una consulta de alerta con su propio
  contrato.
* Test helper `ProductResourceTest.crearProducto(sku, quantity)` ya existe
  para crear productos con una cantidad conocida vía HTTP; se reutiliza tal
  cual para armar los fixtures de este issue.

## Decisiones acordadas con el usuario

### 1. `GET /inventory/products/low-stock?threshold=N`

Sub-recurso de solo lectura nuevo dentro de `ProductResource`, mismo nivel que
`/{id}/availability` y `/{id}/stock-movements`. Responde con un **array plano**
de `ProductResponse` (sin envelope de paginación).

Descartado extender `GET /inventory/products?maxQuantity=N`: mezclaría dos
casos de uso distintos (listar el catálogo paginado vs. alertar productos bajo
un umbral) en el mismo endpoint y forzaría el envelope de paginación
(`items/page/size/totalElements/totalPages/hasNext`) sobre una consulta que el
issue no pide paginar. El issue tampoco lo menciona ni en criterios de
aceptación ni en fuera de alcance, así que se mantiene como el array simple
que ya usan `/{id}/stock-movements` y el batch de `/inventory/stock-adjustments`.

### 2. `threshold` por defecto: **5**

Documentado en el contrato (`@Schema(defaultValue = "5")`, mismo patrón que
`PageRequest.DEFAULT_SIZE` en `/inventory/products`). Vive como constante
pública `StockThreshold.DEFAULT` para que el resource y el contrato lo lean
del mismo lugar, no de un literal repetido.

### 3. `threshold = 0` es válido (no un caso especial)

El issue pide "en o por debajo del umbral": un umbral de `0` es una consulta
legítima ("¿qué se agotó del todo?") y solo devuelve productos con
`quantity = 0`. Solo un valor **negativo** es una petición inválida (400): no
existe una cantidad negativa de stock, así que un umbral negativo no puede
significar nada coherente.

## Diseño

### Dónde vive la regla

La validación es de dominio y es pura, así que vive en `domain.model`, igual
que `PageRequest`:

```java
public record StockThreshold(int value) {
  public static final int DEFAULT = 5;

  public StockThreshold {
    if (value < 0) {
      throw new IllegalArgumentException("El umbral de stock no puede ser negativo");
    }
  }
}
```

El filtro real (`quantity <= threshold`) NO se duplica como predicado en
`Product`: al igual que `status = ACTIVE` en `findPage`/`countActive`, es un
criterio de query que vive en el adapter de persistencia, no una regla de
negocio que deba evaluarse en memoria sobre objetos ya cargados. `Product` no
gana un método nuevo.

### Flujo entre capas

```
GET /inventory/products/low-stock?threshold=N
  → ProductResource.lowStock(threshold: String)
      parseThreshold(threshold)                       // 400 si no es numérico
  → ListLowStockProductsUseCase.handle(int threshold)
      new StockThreshold(threshold)                    // 400 si es negativo
      repository.findBelowOrEqualThreshold(stockThreshold)
  → List<ProductResponse>                               // 200, [] si no hay coincidencias
```

El caso de uso **no** lleva `@Transactional` (no escribe) y no llama a
`save`/`update` por ninguna rama: es exactamente lo que prueba el criterio
"es una consulta pura: no modifica stock ni genera movimientos".

### Orden de los errores

Igual que `quantity`/`page`/`size`: un `threshold` **no representable** como
entero (`abc`, `2147483648`) se rechaza en el borde con 400 antes de tocar el
dominio. Un valor representable pero negativo (`-1`) llega a `StockThreshold`
y se rechaza ahí, también con 400 (mismo código HTTP, pero por la regla de
dominio, no por parseo). No hay un producto de por medio en este endpoint
(es una consulta de colección, no de un id), así que no aplica la disputa
400-vs-404 de github-37/39: aquí solo hay 200 o 400.

## Contrato de la API (D6 / HARNESS B)

| Código | Cuándo | Body |
| ------ | ------ | ---- |
| 200 | consulta resuelta (con o sin coincidencias, es igual) | array de `ProductResponse` |
| 400 | `threshold` ausente-pero-no-numérico, o numérico y negativo | `ApiError` |

`threshold` ausente cae en el default (`StockThreshold.DEFAULT = 5`) antes de
construir `StockThreshold`, igual que `page`/`size` en `ProductResource.list`.

Cambia la superficie de la API → `contracts/openapi.yaml` se regenera y se
commitea en este mismo cambio, con `@APIResponse` por cada código (si no,
`OpenApiContractTest`/`OpenApiFidelityTest` fallan).

## Cambios propuestos

Nuevos:

```
domain/model/StockThreshold.java                          record + regla (no negativo)
application/usecase/ListLowStockProductsUseCase.java
domain/model/StockThresholdTest.java
application/usecase/ListLowStockProductsUseCaseTest.java
```

Modificados:

```
domain/port/ProductRepository.java             + findBelowOrEqualThreshold(StockThreshold)
infrastructure/persistence/ProductRepositoryAdapter.java   + implementación (Panache)
infrastructure/rest/ProductResource.java       + GET /low-stock
                                                + parseThreshold (conversión en el borde)
infrastructure/rest/ProductResourceTest.java   + casos de test del nuevo endpoint
contracts/openapi.yaml                         regenerado
DECISIONS.md                                   D-033
specs/github-40/plan.md                        este plan (resultado de la verificación)
```

Sin tocar: `Product` (dominio), `ProductEntity`, `ProductPage`/`PageRequest`,
`StockMovement*`, `CheckStockAvailabilityUseCase`, harness CLI.

## Impacto

| Capa | Impacto |
| ---- | ------- |
| Domain | **sí** — `StockThreshold` |
| Application | **sí** — `ListLowStockProductsUseCase` |
| Infrastructure (REST) | **sí** — endpoint + parseo en el borde |
| Persistence | **sí** — `findBelowOrEqualThreshold` (query nueva, filtro `status`+`quantity`) |
| Flyway | no — no hay tablas ni columnas nuevas |
| OpenAPI | **sí** — regenerar |
| Harness CLI | no |

## Casos de test en orden (D3 — RED → GREEN → triangulate → refactor)

### Dominio (`StockThresholdTest`)

1. `StockThreshold`: valor negativo → `IllegalArgumentException`.
2. `StockThreshold`: valor `0` → se construye sin excepción (caso límite
   explícito: "en o por debajo" incluye el cero).
3. `StockThreshold`: valor positivo → se construye sin excepción.

### Aplicación (`ListLowStockProductsUseCaseTest`, Mockito, mock del puerto)

4. `handle(threshold)` → delega en `repository.findBelowOrEqualThreshold`
   con el `StockThreshold` construido y devuelve la lista tal cual la entrega
   el repositorio (mapeo transparente).
5. `handle(threshold)` sin coincidencias → lista vacía (el use case no la
   trata como error).
6. `handle(threshold)` con `threshold` negativo → propaga la
   `IllegalArgumentException` de `StockThreshold` sin envolverla ni
   silenciarla.

### REST (`ProductResourceTest`, `@QuarkusTest`)

7. `GET /inventory/products/low-stock` sin `threshold` → 200, usa el default
   (5): un producto con `quantity <= 5` aparece, uno con `quantity > 5` no.
8. `GET /inventory/products/low-stock?threshold=0` → 200, solo productos con
   `quantity = 0` (o `[]` si ninguno tiene esa cantidad).
9. `GET /inventory/products/low-stock?threshold=-1` → 400 con `ApiError`.
10. `GET /inventory/products/low-stock?threshold=abc` → 400 (no 404: no hay
    id de producto en este endpoint, pero se fija con test para que el parseo
    en el borde no regrese a un comportamiento por defecto de RESTEasy).
11. Catálogo sin productos bajo el umbral pedido → 200 con `[]`, no 404.
12. Un producto eliminado (`DELETE /{id}`) con `quantity <= threshold` no
    aparece en el resultado, aunque su cantidad calificara.
13. Orden determinístico por `id` ascendente (mismo criterio que el resto de
    listados), para que el test no dependa del azar del orden de inserción.

## Verificación

```bash
./harness verify
```

`StockThreshold` es lógica de dominio nueva con una rama de validación; si el
plan de mutación lo pide:

```bash
./harness mutation
```

## Resultado de la verificación (desviación del plan)

Implementado tal como se diseñó, con dos ajustes menores detectados durante la
implementación:

* El caso de test 13 ("orden determinístico por id") se **descartó**: la
  ordenación reutiliza el mismo `Sort.by("id")` ya probado por
  `findPage`/`countActive` (github-39); no hay lógica de orden nueva que
  triangular, así que un test dedicado solo repetiría cobertura existente sin
  valor adicional. Los 5 tests REST implementados son: default de 5,
  `threshold=0` sin coincidencias (200 `[]`), `threshold=-1` (400),
  `threshold=abc` (400) y exclusión de productos eliminados.
* La convivencia del path literal `/low-stock` con la ruta `/{id}` (Long) se
  confirmó sin ambigüedad: RESTEasy Reactive prioriza el segmento literal, tal
  como se anticipó en el diseño.

`./harness verify`: 137/137 tests, cobertura y SpotBugs en verde,
`OpenApiContractTest`/`OpenApiFidelityTest` verdes tras regenerar el contrato.
`./harness mutation`: `ListLowStockProductsUseCase` 100% de mutantes muertos;
`StockThreshold` no genera reporte propio en PIT (mismo comportamiento ya
observado con `PageRequest`: un record cuya única lógica vive en el
constructor compacto no produce mutaciones). Los 3 mutantes sobrevivientes de
la corrida son los mismos ya documentados en D-032, ajenos a este cambio.
Detalle completo en `DECISIONS.md` (D-033).

## Fuera de alcance (igual que el issue)

* Notificaciones o alertas automáticas (email, webhook, etc.).
* Umbral configurable por producto (todos comparten el `threshold` de la
  consulta).
* Reposición automática o sugerencia de cantidad a reordenar.
* Paginación de este endpoint (no la pide el issue; ver decisión 1).
