# github-24 — Historial de movimientos de stock

## Work item

* Fuente: GitHub Issue
  [#24](https://github.com/wlopezob/harness-engineering-ia/issues/24)
* Título: *Record stock movement history*

El issue guarda el QUÉ (criterios de aceptación y fuera de alcance). Este
documento guarda el CÓMO. Continúa el trabajo de `specs/github-21/plan.md`
(ajustes de stock), cuyo assumption "el ajuste no genera historial" queda
superado por este work item.

## Entendimiento técnico (estado del working copy)

Punto de partida: `main` @ `a5515d1`, 48 tests verdes.

* `AdjustStockUseCase.handle(id, delta)` hace `findById` → `Product.adjustStock`
  → `repository.update`. Es el único lugar donde cambia la cantidad por un
  ajuste: ahí se cuelga el registro del movimiento.
* Los métodos de `ProductRepositoryAdapter` son `@Transactional` **cada uno por
  separado**; el caso de uso no abre transacción. Hoy `update` commitea solo. Si
  se agrega un segundo `save` después, un fallo entre ambos dejaría el stock
  cambiado sin movimiento → viola la atomicidad exigida.
* La regla de rechazo (delta 0, overflow, stock insuficiente) vive en
  `Product.adjustStock` y lanza **antes** de tocar persistencia: un ajuste
  rechazado no llega a persistir nada. Eso ya cubre "un ajuste rechazado no
  genera movimiento" siempre que el movimiento se grabe después del dominio.
* Patrón de errores: `ProductNotFoundException` → 404 con `ApiError`
  (`GetProductUseCase` es el molde para "si el producto no existe → 404").
* Persistencia: Panache repository pattern (HARNESS E), schema por Flyway
  (`V1__create_product_table.sql`). Un nuevo agregado = entity + panache repo +
  adapter + migración.
* `DELETE /inventory/products/{id}` hoy **borra la fila** (`products.deleteById`).
  Una tabla de movimientos con FK a `product` haría fallar ese borrado (500).
  Decisión del usuario (ver 5): el borrado pasa a ser un cambio de estado.
* SpotBugs ya excluye `EI_EXPOSE_REP2` en `application.usecase` ([D-016]); PIT
  muta `domain.*` y `application.*` (los tests nuevos de esas capas deben tener
  dientes).
* Jackson en Quarkus serializa `Instant` como ISO-8601 (string) y smallrye lo
  describe como `type: string, format: date-time`.

## Decisiones acordadas / propuestas

### 1. Atomicidad: transacción en el caso de uso

`AdjustStockUseCase.handle` se anota `@jakarta.transaction.Transactional`. Los
`@Transactional` de los adapters (propagación `REQUIRED` por defecto) se **unen**
a esa transacción; `update` del producto y `save` del movimiento se commitean o
se revierten juntos. Cualquier `RuntimeException` (incluida una falla al grabar
el movimiento) revierte también el cambio de cantidad.

Alternativas descartadas:
* Un método de puerto compuesto (`ProductRepository.adjust(product, movement)`)
  — mezcla dos agregados en un puerto y esconde la regla en infraestructura.
* Un puerto `UnitOfWork`/`TransactionRunner` propio — abstracción extra sin
  segundo uso.

`ArchitectureTest` lo permite: solo prohíbe `jakarta..` en `domain`;
`application` ya usa `jakarta.enterprise.context`. Se registra en
`DECISIONS.md` porque es la primera vez que la capa de aplicación demarca
transacciones.

### 2. El movimiento se modela como agregado propio en el dominio

```java
// domain/model/StockMovement.java  (record, inmutable, HARNESS C)
record StockMovement(Long id, Long productId, int delta,
                     int previousQuantity, int resultingQuantity,
                     Instant occurredAt)

static StockMovement record(Product before, Product after, Instant occurredAt)
  // productId = before.id(); previous = before.quantity(); resulting = after.quantity()
  // delta = resulting - previous; id = null (lo asigna la DB, como Product)
```

`delta` se deriva de before/after (no se pasa por separado) para que sea
imposible grabar un movimiento inconsistente con las cantidades.

Puerto nuevo `domain/port/StockMovementRepository`:

```java
StockMovement save(StockMovement movement);         // devuelve con id asignado
List<StockMovement> findByProductId(Long productId); // más reciente primero
```

### 3. Fecha y hora: `java.time.Clock` inyectado en el caso de uso

El caso de uso recibe `Clock` por constructor y hace `clock.instant()`.
Un producer CDI en infraestructura (`infrastructure/config/ClockProducer`)
expone `Clock.systemUTC()`. En tests unitarios se pasa `Clock.fixed(...)`.

Por qué no `Instant.now()` en el dominio: rompe "misma entrada → mismo
resultado" (HARNESS C) y hace el test no determinista. Por qué no un `default
now()` en la DB: el `StockMovement` viajaría por el núcleo con `occurredAt`
nulo hasta el insert (prohibido: sin null).

### 4. Ruta de consulta: `GET /inventory/products/{id}/stock-movements` (acordada)

Se usa el lenguaje del issue ("movimiento") y no se reutiliza
`stock-adjustments` porque ese recurso responde con `ProductResponse` (es una
acción sobre el producto), no con el ajuste creado; darle un `GET` que devuelva
otro tipo de objeto sería incoherente. Sin paginación (fuera de alcance).

### 5. Borrar un producto = cambio de estado, no borrado físico (pedido del usuario)

El usuario decidió: al borrar, el producto **solo cambia de estado y no se borra
de la BD** (soft delete). Consecuencias:

* Dominio: `Product` gana `status` (`ProductStatus { ACTIVE, DELETED }`).
  `create` → `ACTIVE`; `restore` recibe el status; nuevo `markDeleted()`
  devuelve un **nuevo** Product en `DELETED` (inmutable, HARNESS C).
* Puerto `ProductRepository`: `findById` y `findAll` devuelven **solo productos
  `ACTIVE`** (documentado en el Javadoc del puerto). `deleteById` se **elimina**
  del puerto: borrar es persistir la transición de estado vía `update`.
* `DeleteProductUseCase`: `findById(id).orElseThrow(404)` →
  `repository.update(existing.markDeleted())`. Un producto ya eliminado no se
  encuentra → 404 (mismo comportamiento externo que hoy: el test
  `delete_elimina_el_producto_y_luego_da_404` sigue verde sin cambios).
* Adapter: `update` también sincroniza `entity.status`; `findById`/`findAll`
  filtran `status = ACTIVE`; se borra el método `deleteById`.
* Comportamiento externo de un producto `DELETED`: 404 en `GET /{id}`, `PUT`,
  `DELETE`, `POST /stock-adjustments` y `GET /stock-movements`; ausente en
  `GET /inventory/products`. Es decir, **para la API un producto eliminado no
  existe**; el estado no se expone (`ProductResponse` no cambia).
* Historial: la FK `stock_movement.product_id → product.id` es simple, **sin
  cascade**: la fila del producto nunca se borra, la FK nunca se viola y la
  auditoría sobrevive al borrado lógico.
* Migración `V2__add_product_status.sql`:
  `alter table product add column status varchar(16) not null default 'ACTIVE';`
  Los productos existentes quedan activos.

Contrato del `DELETE`: sin cambios de superficie (204 / 404); cambia el efecto.

**SKU de un producto eliminado (acordado con el usuario):** el `unique (sku)`
de la tabla se mantiene tal cual, así que crear un producto con el SKU de uno
eliminado responde **409** (el SKU sigue reservado). No se toca la constraint.
(Alternativa descartada: índice único parcial `where status = 'ACTIVE'` para
permitir reutilizarlo.)

**Historial de un producto eliminado (acordado con el usuario):** se conserva
en la BD como auditoría, pero **no es consultable por la API**: `GET
.../stock-movements` responde 404 igual que cualquier otro endpoint sobre un
producto eliminado. Para la API, un producto eliminado no existe.

### 6. Orden: `occurred_at desc, id desc`

"Más reciente primero" por fecha; `id desc` como desempate determinista si dos
movimientos caen en el mismo instante (precisión de microsegundos en Postgres).

## Contrato de la API (D6 / HARNESS B)

Nuevo endpoint:

| Método/Ruta | Código | Cuándo | Body |
| ----------- | ------ | ------ | ---- |
| `GET /inventory/products/{id}/stock-movements` | 200 | producto activo (lista vacía si no tiene movimientos) | `StockMovementResponse[]` |
| | 404 | no existe producto activo con ese id | `ApiError` |

```json
// StockMovementResponse
{ "id": 1, "productId": 7, "delta": -3,
  "previousQuantity": 10, "resultingQuantity": 7,
  "occurredAt": "2026-08-17T15:04:05.123456Z" }
```

`POST /{id}/stock-adjustments` y `DELETE /{id}` **no cambian** su superficie
(mismos códigos y bodies); cambia su efecto.

Cambia la superficie → `contracts/openapi.yaml` se regenera y se commitea en
**este mismo cambio**; el 200 y el 404 van con `@APIResponse` (si no,
`OpenApiFidelityTest` falla por `schema: {}`).

## Cambios propuestos

Nuevos:

```
domain/model/ProductStatus.java                       enum ACTIVE, DELETED
domain/model/StockMovement.java                       record + factory record(before, after, at)
domain/port/StockMovementRepository.java              save / findByProductId
application/usecase/ListStockMovementsUseCase.java    404 si no existe el producto
infrastructure/persistence/StockMovementEntity.java   @Entity stock_movement
infrastructure/persistence/StockMovementPanacheRepository.java
infrastructure/persistence/StockMovementRepositoryAdapter.java
infrastructure/config/ClockProducer.java              @Produces Clock.systemUTC()
infrastructure/rest/StockMovementResponse.java        record
resources/db/migration/V2__add_product_status.sql
resources/db/migration/V3__create_stock_movement_table.sql
```

Modificados:

```
domain/model/Product.java                     + status, markDeleted(); restore con status
domain/port/ProductRepository.java            findById/findAll = solo ACTIVE; - deleteById
application/usecase/DeleteProductUseCase.java findById → update(markDeleted())
application/usecase/AdjustStockUseCase.java   + StockMovementRepository + Clock,
                                              @Transactional, graba el movimiento
infrastructure/persistence/ProductEntity.java + status (@Enumerated STRING)
infrastructure/persistence/ProductRepositoryAdapter.java
                                              filtra ACTIVE, update sincroniza status,
                                              - deleteById
infrastructure/rest/ProductResource.java      + GET /{id}/stock-movements
contracts/openapi.yaml                        regenerado
DECISIONS.md                                  D-022 transacción en el use case,
                                              D-023 modelo/ruta del movimiento + Clock,
                                              D-024 soft delete de producto
```

Sin tocar: `StockAdjustmentRequest`, `ProductResponse`, mappers de excepciones,
`V1`.

Migración `V3`:

```sql
create table stock_movement (
    id                 bigint generated by default as identity primary key,
    product_id         bigint  not null references product (id),
    delta              integer not null,
    previous_quantity  integer not null,
    resulting_quantity integer not null,
    occurred_at        timestamp with time zone not null
);
create index ix_stock_movement_product_occurred
    on stock_movement (product_id, occurred_at desc, id desc);
```

Flujo del ajuste (caso de uso, una sola transacción):

```
existing  = products.findById(id) orElseThrow 404   // solo activos
adjusted  = existing.adjustStock(delta)             // 400/409 lanzan aquí: nada persistido
saved     = products.update(adjusted)
movements.save(StockMovement.record(existing, adjusted, clock.instant()))
return saved
```

## Impacto

| Capa | Impacto |
| ---- | ------- |
| Domain | **sí** — `StockMovement`, `ProductStatus`, `Product.markDeleted`, puertos |
| Application | **sí** — `AdjustStockUseCase` (transacción + registro), `DeleteProductUseCase` (soft delete), `ListStockMovementsUseCase` |
| Infrastructure (REST) | **sí** — endpoint GET + response |
| Persistence | **sí** — entity/adapter de movimientos, status en producto, V2 y V3, `ClockProducer` |
| OpenAPI | **sí** — regenerar (solo el GET nuevo) |
| Harness CLI | no |

## Casos de test en orden (D3 — RED → GREEN → triangulate → refactor)

### A) Dominio — `StockMovementTest` (unit puro)

1. `record(before, after, at)` toma productId y cantidad anterior de `before`,
   la resultante de `after`, calcula el delta y conserva `occurredAt`; el id
   queda sin asignar. ← **primer RED** (la clase no existe)
2. *(triangulación)* con una salida (`after.quantity < before.quantity`) el
   delta es negativo — mata la implementación que use `Math.abs` o
   `before - after`.

### B) Dominio — `ProductTest` (soft delete)

3. `create` deja el producto en `ACTIVE`.
4. `markDeleted` devuelve un producto `DELETED` conservando id, nombre, sku y
   cantidad (y no muta el original).

### C) Aplicación — `AdjustStockUseCaseTest` (Mockito local, AAA)

5. `handle` graba un movimiento con producto, delta, cantidad anterior,
   resultante y el instante del `Clock` fijo (`ArgumentCaptor`).
6. Ajuste rechazado por el dominio (stock insuficiente) → **no** llama a
   `movements.save` ni a `update` (`verify(..., never())`).
7. Producto inexistente → 404 y `never()` sobre ambos repositorios (extiende el
   test existente).

### D) Aplicación — `DeleteProductUseCaseTest` (se reescribe: cambia el puerto)

8. `handle` persiste el producto marcado `DELETED` vía `update`
   (`ArgumentCaptor` sobre el status).
9. Producto inexistente → 404 y `never()` sobre `update`.

### E) Aplicación — `ListStockMovementsUseCaseTest`

10. Devuelve la lista del puerto para un producto existente.
11. Producto inexistente → `ProductNotFoundException` y `never()` sobre
    `findByProductId`.

### F) Infra REST — `ProductResourceTest` (`@QuarkusTest`, Postgres real)

12. Tras dos ajustes (+5, -3), `GET .../stock-movements` devuelve 200 con dos
    movimientos **en orden inverso** (primero el -3) y los campos correctos
    (`previousQuantity`/`resultingQuantity` encadenados: 10→15, 15→12).
13. Un ajuste rechazado (409) no agrega movimiento (la lista sigue con los
    mismos elementos).
14. Producto sin ajustes → 200 con lista vacía.
15. `GET .../stock-movements` a id inexistente → 404.
16. Producto eliminado: `DELETE` → 204; luego `POST /stock-adjustments` → 404,
    `GET /stock-movements` → 404, no aparece en `GET /inventory/products`, y un
    segundo `DELETE` → 404.
17. `DELETE` de un producto **con movimientos** → 204 (sin la decisión 5 la FK
    daría 500).
18. Crear un producto con el SKU de uno eliminado → 409 (fija la decisión del
    SKU reservado).

### G) Infra — `StockAdjustmentAtomicityTest` (`@QuarkusTest` + `@InjectMock`)

19. Si `StockMovementRepository.save` lanza `RuntimeException`, el `POST` falla
    y un `GET` posterior muestra la **cantidad original**: el ajuste no se
    persistió sin su movimiento. Se ve **rojo** antes de anotar `@Transactional`
    en el caso de uso (el `update` del adapter commitea solo) y verde después.
    Clase separada para que el mock no contamine `ProductResourceTest`.

### H) Contrato

20. Regenerar `contracts/openapi.yaml`; `OpenApiContractTest` y
    `OpenApiFidelityTest` en verde.

Red de seguridad: los 48 tests actuales. Únicos que cambian: los 2 de
`DeleteProductUseCaseTest` (el puerto pierde `deleteById`; se reescriben en D).
Los tests REST existentes de `DELETE` y de `stock-adjustments` deben seguir
verdes **sin tocarlos**: la superficie no cambia.

## Verificación (D4)

```bash
./harness verify
./harness mutation     # cambia lógica de domain/application
```

Esperado: `HARNESS RESULT: PASSED`, ~66 tests, JaCoCo ≥ 80 % líneas y ramas,
Spotless y SpotBugs limpios; PIT sin mutantes sobrevivientes en el código
nuevo. Evidencia en `artifacts/harness/<timestamp>-<sha>/`.

### Resultado

* `./harness verify` → **PASSED**, 64 tests (48 previos + 16 nuevos), JaCoCo
  OK, SpotBugs 0 hallazgos, `OpenApiContractTest`/`OpenApiFidelityTest`/
  `ArchitectureTest` verdes. Evidencia:
  `artifacts/harness/20260817T120718Z-a5515d1/`.
* `./harness mutation` → 40 mutantes, 93 % eliminados, test strength 97 %.
  Sobre el código de este cambio no sobrevive ninguno. Los 3 restantes son los
  mismos preexistentes de github-21 (`Product.requireQuantity` boundary y los
  accessors sin usar de `ProductNotFoundException` y `DuplicateSkuException`).
* Ciclo TDD observado: primer RED en `StockMovementTest` (clase inexistente);
  RED clave en `StockAdjustmentAtomicityTest` (cantidad 15 en vez de 10 sin
  `@Transactional`), verde tras anotar el caso de uso. Los 6 tests REST del
  soft delete pasaron a la primera porque el comportamiento salió del GREEN del
  caso de uso; se les dio dientes con una mutación manual (quitar el filtro de
  `status` en `findById` → `Expected 404 but was 200`).

### Desviaciones del plan

* Conteo final: 16 tests nuevos (el plan estimaba ~18): los casos 8–9 del plan
  reescriben los 2 tests existentes de `DeleteProductUseCaseTest` en vez de
  sumarse, y el caso 16 agrupa la invisibilidad del producto eliminado en un
  solo test REST.

## Revisión del PR #25 — el `PUT` rompía el historial (RESUELTO: alternativa A)

Detectado en review, con el PR #25 **abierto**. El usuario aprobó la
**alternativa A + rechazo explícito (400)**, entregada en el mismo PR con commit
propio. El análisis se conserva íntegro: es el porqué de la decisión.

### El problema

Hay **dos** caminos de escritura de `quantity` y solo uno deja rastro:

| Camino | Regla | ¿Movimiento? |
| ------ | ----- | ------------ |
| `POST /{id}/stock-adjustments` | `Product.adjustStock` (delta ≠ 0, sin negativos, sin overflow) | **sí**, atómico |
| `PUT /{id}` | `Product.update` (solo `quantity >= 0`) | **no** |

Reproducido contra la app real (dev mode + Postgres), producto con cantidad
inicial 10:

```
POST /stock-adjustments {"delta":5}    → quantity 15
PUT  /{id} {"name":"…","quantity":100} → quantity 100      ← sin movimiento
POST /stock-adjustments {"delta":-3}   → quantity 97

GET /{id}/stock-movements
  id=2 delta=-3   100 -> 97
  id=1 delta=+5    10 -> 15
  cadena: mov 1 termina en 15, mov 2 arranca en 100 → ROTA (salto de +85)
  10 + Σ deltas (+2) = 12 ; stock real = 97
```

### Por qué importa

* Rompe el objetivo del work item ("conocer cómo cambió su cantidad a lo largo
  del tiempo"): el historial puede **omitir el cambio más grande** y nadie lo
  nota; peor que no tener historial, porque parece completo.
* Rompe dos invariantes implícitas del modelo:
  `resultingQuantity[n] == previousQuantity[n+1]` (cadena continua) y
  `cantidad inicial + Σ deltas == cantidad actual`.
* El `PUT` es además una puerta trasera a las reglas de stock: fija cualquier
  valor no negativo sin pasar por "stock insuficiente" ni por el rechazo de
  delta 0.
* La creación **no** rompe nada: la cantidad inicial es el `previousQuantity`
  del primer movimiento y ancla la cadena. El único agujero es el `PUT`.

### Alternativas

**A — El `PUT` deja de editar `quantity` (el stock solo cambia por ajustes).**
`UpdateProductRequest` pasa a `{name}` y `Product.update(name)` conserva la
cantidad. La invariante se cumple **por construcción**: no existe forma de mover
stock sin movimiento, ni hoy ni en un caso de uso futuro.
*Coste:* cambia el schema del contrato (D6). Hoy **no hay consumidores**
(`apps/web` no existe; `apps/` solo contiene `api`), así que el coste de romper
es prácticamente cero y solo crece con el tiempo. Para fijar un valor absoluto
hay que calcular el delta (`objetivo − actual`) — que además es más robusto ante
concurrencia que un `PUT` absoluto: dos deltas concurrentes suman, dos escrituras
absolutas se pisan (D-021).

**B — El `PUT` registra el movimiento de la diferencia.**
`UpdateProductUseCase` se vuelve `@Transactional`, calcula `delta = nuevo −
actual` y registra el movimiento cuando `delta != 0`. Contrato **sin cambios**.
*Coste:* quedan dos caminos con reglas distintas (el `PUT` no conoce "stock
insuficiente" ni el rechazo de delta 0) y el registro del movimiento hay que
recordarlo en cada caso de uso nuevo que toque `quantity`. Además el movimiento
nacido de un `PUT` es una *corrección*, no una entrada/salida, y el issue deja
"motivo del movimiento" **fuera de alcance**: no habría cómo distinguir una
corrección de +85 de una entrada real de +85.

**C — Dejarlo como está y documentar la limitación.**
Coste cero y defendible por alcance (el issue no menciona el `PUT`).
*Coste:* el criterio de aceptación queda incumplido a sabiendas y el historial
miente en silencio. Se lista por honestidad; no se recomienda.

**D — El `PUT` acepta `quantity` solo si coincide con la actual (si difiere →
409, apuntando a `/stock-adjustments`).**
Mantiene el schema (permite el ciclo GET → editar → PUT del recurso completo) y
la invariante se cumple como en A.
*Coste:* un campo obligatorio que no se puede cambiar es semántica rara para un
`PUT`; agrega un 409 nuevo; quien solo quiera renombrar puede comerse un 409 por
una carrera con un ajuste ajeno.

### Recomendación: **A**

Es la única que hace el estado inválido **irrepresentable** en vez de repararlo:
un solo camino de escritura, una sola regla de stock, un solo lugar donde se
registra el movimiento. Coherente con el repo (la regla vive en el dominio) y
con D6: hay impacto de contrato, pero sin consumidores este es el momento más
barato para hacerlo. Si más adelante se necesita fijar un valor absoluto
auditado, se agrega de forma aditiva al recurso de ajustes (p. ej.
`{"targetQuantity": n}`, que calcula el delta y registra el movimiento), sin
reabrir la puerta trasera.

### Sub-decisión (si se aprueba A): ¿qué pasa si el cliente sigue mandando `quantity`?

* **A1 — Rechazo explícito (400).** Requiere activar
  `quarkus.jackson.fail-on-unknown-properties=true`, que aplica a **toda** la
  API (un `POST /products` con un campo de más también pasaría a 400).
  *Recomendado:* un cliente viejo se entera en vez de creer que cambió el stock.
* **A2 — Ignorar en silencio.** Es el comportamiento por defecto de Quarkus:
  cero cambios extra, pero el cliente cree que ajustó stock y no lo hizo.

### Dónde aterriza el cambio

En el **PR #25**, con commit propio. El criterio "el historial explica el stock"
pertenece a #24 y el PR sigue abierto: mergear primero y arreglar después
dejaría en `main` una versión que ya sabemos inconsistente. Es un cambio de
superficie → contrato regenerado en el mismo cambio (D6) y `DECISIONS.md` con
una decisión nueva que matiza [D-014] (que estableció `PUT` name+quantity).

### Casos de test si se aprueba A (D3, en orden)

1. Dominio `ProductTest`: `update(name)` cambia el nombre y **conserva** la
   cantidad. ← primer RED (la firma no existe).
2. Aplicación `UpdateProductUseCaseTest`: `handle(id, name)` persiste el
   producto con la cantidad que tenía (`ArgumentCaptor`), sin recibirla.
3. REST: `PUT {"name":"Teclado v2"}` → 200 con la cantidad intacta (reescribe
   `put_actualiza_nombre_y_cantidad_conservando_sku`).
4. REST: desaparece del `PUT` el 400 por cantidad negativa (se recorta esa mitad
   de `put_con_datos_invalidos_devuelve_400`; sigue el 400 por nombre en blanco).
5. REST (solo con A1): `PUT {"name":"x","quantity":99}` → 400.
6. **REST — el diente del criterio del issue:** crear(10) → ajustar(+5) →
   `PUT` de nombre → ajustar(−3) → el historial es una cadena continua
   (`resulting[n] == previous[n+1]`) y `10 + Σ deltas == quantity` actual. Este
   test, escrito hoy contra el código del PR con un `PUT` de cantidad, sale
   **rojo** (12 ≠ 97); es el que impide la regresión.
7. Contrato regenerado; `OpenApiContractTest`/`OpenApiFidelityTest` verdes.

Red de seguridad: los 64 tests del PR. Cambian solo los 3 del `PUT` (puntos 2–4).

### Resultado (alternativa A implementada)

* **Primer RED — el diente del criterio:**
  `el_historial_explica_siempre_la_cantidad_actual` falló con
  `expected: <12> but was: <97>` contra el código del PR. Está escrito sin
  asertar el status del `PUT`, así que **el mismo test sin tocar** pasó a verde
  cuando el `PUT` empezó a rechazar la cantidad (12 == 12).
* REDs siguientes: `Product.update` (`required: String,int / found: String`),
  el caso de uso y el recurso REST en cascada, y el 400 sin cuerpo
  (`message: null`) que exigió el mapper.
* `./harness verify` → **PASSED**, 65 tests, JaCoCo OK, SpotBugs 0.
  Evidencia: `artifacts/harness/20260817T131234Z-1add509/`.
* `./harness mutation` → 40 mutantes, 93 % eliminados, test strength 97 %; los
  3 restantes son los preexistentes de github-21.
* Contrato regenerado: `quantity` desaparece de `UpdateProductRequest` y cambia
  la descripción del 400. Es el único cambio de superficie.

### Desviaciones respecto a este apartado

* El test de la invariante (punto 6 de la lista) se escribió **primero**, para
  poder verlo rojo; el resto siguió el orden previsto.
* Apareció un componente no previsto: `InvalidRequestBodyExceptionMapper`. Con
  `fail-on-unknown-properties=true` el 400 llegaba **sin cuerpo**, porque lo
  emitía el mapper built-in de Quarkus para `MismatchedInputException`; un
  mapper propio para ese tipo exacto lo sustituye y devuelve `ApiError`, como el
  resto de errores de la API.
* Se eliminó `update_rechaza_cantidad_negativa` (dominio): `update` ya no recibe
  cantidad, así que el caso dejó de existir. `requireQuantity` sigue vigente
  para `create`.

### Impacto (alternativa A)

| Capa | Impacto |
| ---- | ------- |
| Domain | `Product.update(String name)` (pierde el parámetro `quantity`) |
| Application | `UpdateProductUseCase.handle(id, name)` |
| Infrastructure (REST) | `UpdateProductRequest` = `{name}`; `ProductResource.update` |
| Persistence | no |
| OpenAPI | **sí** — regenerar (schema de `UpdateProductRequest`) |
| Config | `quarkus.jackson.fail-on-unknown-properties=true` (A1) |
| Infrastructure (REST) | `InvalidRequestBodyExceptionMapper` → 400 con `ApiError` |

## Assumptions

* ~~Un movimiento por ajuste exitoso: la cantidad solo cambia por ajuste. El
  `PUT /inventory/products/{id}` (que también edita `quantity`) **no** genera
  movimiento~~ → **assumption invalidada en el review del PR #25**: el `PUT`
  sí cambia la cantidad, así que el historial puede dejar de explicar el stock.
  Ver "Revisión del PR #25" arriba.
* La creación del producto (cantidad inicial) y el borrado lógico tampoco
  generan movimiento.
* El estado del producto no se expone en la API (todo lo visible es `ACTIVE`).
* El SKU de un producto eliminado sigue reservado (409 al reutilizarlo) y su
  historial se retiene sin ser consultable — ver decisión 5.
* Sin usuario, motivo, reservas, multi-almacén ni paginación (fuera de alcance
  explícito).
* La respuesta del `POST /stock-adjustments` sigue siendo `ProductResponse`; no
  se devuelve el movimiento creado (mantener el contrato estable).

## Open questions

Ninguna. El agujero del `PUT` se cerró con la alternativa **A + A1**, aprobada
por el usuario y entregada en el PR #25.

Resueltas con el usuario: soft delete en vez de borrado físico, ruta
`stock-movements`, SKU reservado tras el borrado, historial retenido pero no
consultable para productos eliminados.
