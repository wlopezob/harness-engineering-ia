# github-37 — Disponibilidad de stock de un producto

## Work item

* Fuente: GitHub Issue
  [#37](https://github.com/wlopezob/harness-engineering-ia/issues/37)
* Título: *feat(inventory): expose product stock availability*

El issue guarda el QUÉ (criterios de aceptación y fuera de alcance). Este
documento guarda el CÓMO.

## Entendimiento técnico (estado del working copy)

Punto de partida: `main` @ `1e64585`, rama `feat/gh-37-stock-availability`,
árbol limpio, **90 tests verdes**.

* `Product` (dominio, HARNESS C) es inmutable: `create`/`restore` como factories
  y `update`/`adjustStock`/`markDeleted` devolviendo **un nuevo** `Product`.
  Ya expone `quantity()`. No hay ningún método de solo consulta todavía.
* `ProductRepository.findById` devuelve `Optional<Product>` y **ya filtra
  `DELETED`** (`ProductRepositoryAdapter`: `where id = ?1 and status = ?2`,
  D-024). El puerto **no necesita métodos nuevos**: la consulta de
  disponibilidad se resuelve con lo que ya existe.
* Patrón de errores establecido: `IllegalArgumentException` → 400,
  `ProductNotFoundException` → 404, `DuplicateSkuException` → 409,
  `InsufficientStockException` → 409; cada uno con su `ExceptionMapper` y body
  `ApiError`.
* `ListStockMovementsUseCase` + `GET /{id}/stock-movements` son el molde exacto
  de lo que hay que escribir: sub-recurso de solo lectura que primero resuelve
  el producto (`findById().orElseThrow(...)`) y luego responde.
* SpotBugs: `EI_EXPOSE_REP2` ya está excluido para `application.usecase`
  (D-016), así que el caso de uso nuevo no rompe HARNESS I.
* `StockAdjustmentBatch` dejó la lección de D-029: en un `record`, las reglas
  van en el **constructor compacto**, no en un factory, porque el constructor
  canónico es público por serlo un record.

## Decisiones acordadas con el usuario

### 1. `GET /inventory/products/{id}/availability?quantity=N`

Sub-recurso de solo lectura, igual que `/{id}/stock-movements`. `GET` es
idempotente por contrato: la propia forma del endpoint sostiene el criterio
"consultar la disponibilidad no modifica el stock", en vez de dejarlo como una
promesa de la implementación.

Descartados: `POST /inventory/availability-checks` (un POST para una consulta
pura sugiere escritura; el lote no lo pide el issue) y ampliar `GET /{id}` con
`?quantity=N` (volvería condicional el schema de `ProductResponse` y mezclaría
dos conceptos en un mismo recurso).

### 2. Producto inexistente o eliminado → 404

Coherente con todo el repo: `findById` ya no ve los `DELETED` (D-024) y
`GET /{id}`, `PUT`, `DELETE` y los ajustes responden 404 en el mismo caso. Así
el cliente distingue **"no hay producto"** de **"hay producto y no alcanza"**,
que son dos situaciones distintas y necesitan tratamientos distintos.

Descartado responder 200 con `available: false`: cumpliría la letra del
criterio ("no reportarlo como disponible") pero escondería el error de id
dentro de una respuesta de éxito.

### 3. Cuerpo completo, `missingQuantity` siempre presente

```json
{
  "productId": 12,
  "requestedQuantity": 10,
  "availableQuantity": 8,
  "available": false,
  "missingQuantity": 2
}
```

Schema estable: ningún campo opcional y ningún `null` (HARNESS C). Cuando el
stock alcanza, `missingQuantity` es **0**, no se omite. El eco de
`requestedQuantity` deja la respuesta interpretable por sí sola en logs y
trazas del cliente.

## Diseño

### Dónde vive la regla

La regla es de dominio y es **pura**, así que vive en `domain.model`:

```
Product.checkAvailability(int requestedQuantity) → StockAvailability
```

`Product` no cambia de estado y no devuelve un `Product`: devuelve un objeto de
valor nuevo. El caso de uso solo resuelve el producto y delega.

```java
public record StockAvailability(Long productId, int requestedQuantity, int availableQuantity) {
  // constructor compacto: requestedQuantity > 0   (D-029: la regla en el canónico, no en un factory)
  // available()        → availableQuantity >= requestedQuantity
  // missingQuantity()  → available() ? 0 : requestedQuantity - availableQuantity
}
```

`available` y `missingQuantity` se **derivan**, no se almacenan: un
`StockAvailability` con `available=true` y `missingQuantity=5` es
irrepresentable. Los dos operandos son enteros no negativos, así que la resta
no puede desbordar (no hace falta el `long` de D-020).

### Orden de los errores: 404 antes que 400

El caso de uso carga el producto y solo entonces el dominio valida la cantidad
solicitada. Consecuencia: `GET /inventory/products/99999999/availability?quantity=0`
responde **404**, no 400. Es el mismo orden que `AdjustStockUseCase` (primero
el producto ausente, después el delta cero, D-029) y se fija con un test para
que no dependa del azar.

### Flujo entre capas

```
GET /{id}/availability?quantity=N
  → ProductResource.availability(id, quantity)
  → CheckStockAvailabilityUseCase.handle(id, quantity)
      repository.findById(id).orElseThrow(ProductNotFoundException::new)   // 404
      product.checkAvailability(quantity)                                  // 400 si quantity <= 0
  → StockAvailabilityResponse.from(...)                                    // 200
```

El caso de uso **no** lleva `@Transactional` (no escribe) y **no** llama a
`update` ni a `save` por ninguna rama: eso es exactamente lo que prueba el
diente del criterio "consultar no modifica el stock".

## Contrato de la API (D6 / HARNESS B)

| Código | Cuándo | Body |
| ------ | ------ | ---- |
| 200 | consulta resuelta (alcance o no, es igual) | `StockAvailabilityResponse` |
| 400 | `quantity` ausente, cero o negativa | `ApiError` |
| 404 | no existe producto con ese id, o está eliminado | `ApiError` |

`quantity` ausente cae en el valor por defecto del `int` (0) y lo rechaza la
misma regla de dominio: no hay un segundo camino de validación.

Cambia la superficie de la API → `contracts/openapi.yaml` se regenera y se
commitea en **este mismo cambio**, con `@APIResponse` por cada código (si no,
`OpenApiFidelityTest` falla por `schema: {}`).

**Verificado durante la implementación (desviación del plan).** Con el
parámetro declarado como `int`, `?quantity=abc` devolvía **404**: JAX-RS
convierte el fallo de conversión de un `@QueryParam` en `NotFoundException`, así
que la API respondía "no existe un producto con ese id" **sobre un producto que
sí existe**. El contrato no mentía por omisión sino por significado, que es peor.

Corrección (en el código, no en el YAML): el resource recibe `quantity` como
`String` y lo convierte en el borde (`parseQuantity`), lanzando
`IllegalArgumentException` → **400** por el mapper que ya existía. Para que el
contrato siga describiendo la verdad, el parámetro se declara explícitamente
`integer/int32` y **requerido** con `@Parameter`. Ausente → `null` → 0, que
rechaza la misma regla del dominio.

Consecuencia sobre el orden de errores: una cantidad **no representable**
(`abc`, `2147483648`) se rechaza en el borde con 400 *antes* de mirar el
producto, mientras que una cantidad representable pero inválida (`0`, `-1`)
sigue cediendo el paso al 404. Es la separación correcta: petición malformada
(sintaxis) vs. regla de negocio (dominio).

## Cambios propuestos

Nuevos:

```
domain/model/StockAvailability.java                       record + reglas derivadas
application/usecase/CheckStockAvailabilityUseCase.java
infrastructure/rest/StockAvailabilityResponse.java        record HTTP
application/usecase/CheckStockAvailabilityUseCaseTest.java
```

Modificados:

```
domain/model/Product.java                 + checkAvailability(int) → StockAvailability
infrastructure/rest/ProductResource.java  + GET /{id}/availability
                                          + parseQuantity (conversión en el borde)
contracts/openapi.yaml                    regenerado
DECISIONS.md                              D-031
specs/github-37/plan.md                   este plan (resultado de la verificación)
```

Sin tocar: `ProductRepository` (puerto), `ProductEntity`,
`ProductRepositoryAdapter`, migraciones Flyway, `StockMovement*`, harness CLI.

## Impacto

| Capa | Impacto |
| ---- | ------- |
| Domain | **sí** — `checkAvailability` + `StockAvailability` |
| Application | **sí** — `CheckStockAvailabilityUseCase` |
| Infrastructure (REST) | **sí** — endpoint + response |
| Persistence | no — se reutiliza `findById` |
| Flyway | no — no hay tablas ni columnas nuevas |
| OpenAPI | **sí** — regenerar |
| Harness CLI | no |

## Casos de test en orden (D3 — RED → GREEN → triangulate → refactor)

### A) Dominio — `ProductTest` (unit puro, sin Quarkus)

1. `checkAvailability` con stock **mayor** que lo solicitado → disponible.
   ← **primer RED**
2. stock **menor** → no disponible y `missingQuantity` = solicitado − disponible.
3. *(límite)* stock **exactamente igual** → disponible — mata el `>` en vez de
   `>=`.
4. `missingQuantity` es **0** cuando el stock alcanza — mata la implementación
   que devuelve la resta negativa.
5. cantidad solicitada **0** → `IllegalArgumentException`.
6. cantidad solicitada **negativa** → `IllegalArgumentException`.
7. *(el criterio del issue, con dientes)* consultar la disponibilidad **no
   cambia** la cantidad del producto: el `Product` conserva su `quantity` y la
   consulta devuelve un objeto nuevo.

Si PIT deja mutantes vivos en las reglas derivadas de `StockAvailability`, se
agrega un `StockAvailabilityTest` enfocado; mientras el valor se pruebe por su
entrada real (`Product`), no se duplica.

### B) Aplicación — `CheckStockAvailabilityUseCaseTest` (Mockito local, AAA)

8. `handle` devuelve la disponibilidad del producto encontrado.
9. producto inexistente (o eliminado: `findById` devuelve vacío) →
   `ProductNotFoundException`.
10. `handle` **nunca escribe**: `verify(repository, never()).update(any())` y
    `never()).save(any())`, en el camino feliz y en el rechazado.

### C) Infra REST — `ProductResourceTest` (`@QuarkusTest`, Postgres real)

11. stock suficiente → 200 con `available=true` y `missingQuantity=0`.
12. stock insuficiente → 200 con `available=false` y `missingQuantity` correcto.
13. `quantity=0` → 400; `quantity=-1` → 400; `quantity` ausente → 400.
14. id inexistente → 404.
15. producto **eliminado** (DELETE previo) → 404.
16. *(end-to-end del criterio)* consultar disponibilidad y después `GET /{id}`:
    la cantidad es la misma que antes de consultar.
17. id inexistente **y** `quantity=0` → 404 (fija el orden decidido arriba).

18. *(agregado durante la implementación)* `quantity=abc` y
    `quantity=2147483648` → **400**. Es el test que destapó el 404 falso
    descrito arriba; se escribió esperando 400, se vio rojo con 404 y el arreglo
    fue del código.

### D) Contrato

19. Regenerar `contracts/openapi.yaml`; `OpenApiContractTest` y
    `OpenApiFidelityTest` en verde.

Los 90 tests actuales son la red de seguridad: ninguno debe cambiar. Si alguno
se pone rojo, el cambio rompió comportamiento existente.

## Verificación (D4)

```bash
./harness verify
```

Esperado: `HARNESS RESULT: PASSED`, JaCoCo ≥ 80 % líneas y ramas, Spotless y
SpotBugs limpios. Evidencia en `artifacts/harness/<timestamp>-<sha>/`.

Al agregar lógica de dominio con ramas nuevas:

```bash
./harness mutation
```

### Resultado

* `./harness verify` → **PASSED**, **108 tests** (90 de base + 18 nuevos), 0
  fallos, JaCoCo dentro de umbral, Spotless y SpotBugs limpios.
  Evidencia: `artifacts/harness/20260819T014807Z-1e64585-dirty-835226d/`.
  La primera corrida salió **FAILED** por Spotless (formato del código nuevo);
  se corrigió con `./harness format`, no bajando la regla.
* `./harness mutation` → **COMPLETED**: 56 mutantes, 53 eliminados (95 %),
  test strength 98 %. Evidencia:
  `artifacts/harness/20260819T014841Z-1e64585-dirty-835226d-mutation/`.
  **Sobre el código de este cambio no sobrevive ningún mutante** (8/8 muertos:
  `Product.checkAvailability`, `StockAvailability.available` ×3,
  `StockAvailability.missingQuantity` ×3, `CheckStockAvailabilityUseCase.handle`).
  Ahí está el diente de los dos tests que pasaron a la primera: el
  `ConditionalsBoundaryMutator` que convierte `>=` en `>` lo mata el caso del
  límite exacto.
  Los 3 supervivientes son los preexistentes ya documentados en github-21
  (`Product.requireQuantity` y los accessors sin usar de
  `ProductNotFoundException` y `DuplicateSkuException`): deuda previa, ajena a
  este work item.
  PIT no analiza `infrastructure`, así que `parseQuantity` no tiene mutantes;
  su comportamiento lo sostienen los tests `@QuarkusTest` del caso 18.

## Assumptions

* La disponibilidad se calcula contra la cantidad total del producto: no hay
  reservas, apartados ni stock comprometido en el modelo actual, y el issue los
  deja fuera de alcance.
* Un solo producto por consulta: el issue no pide lote. Si aparece, el sitio
  natural es un endpoint propio como el de github-32, no este.
* Sin control de acceso por rol: el repo todavía no tiene autenticación.
* La consulta no deja rastro en el historial de movimientos: no es un movimiento
  de stock.

## Open questions

Ninguna bloqueante. Resueltas con el usuario: forma del endpoint, tratamiento
del producto inexistente o eliminado y cuerpo de la respuesta.
