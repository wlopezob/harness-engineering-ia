# github-32 — Ajustes de stock de varios productos en una sola operación

## Work item

* Fuente: GitHub Issue
  [#32](https://github.com/wlopezob/harness-engineering-ia/issues/32)
* Título: *Apply multiple stock adjustments atomically*

El issue guarda el QUÉ. Este documento guarda el CÓMO. Continúa el trabajo de
`specs/github-21/plan.md` (ajuste individual) y `specs/github-24/plan.md`
(historial de movimientos y transacción del ajuste).

## Entendimiento técnico (estado del working copy)

Punto de partida: `main` @ `64824a7`, rama `feat/gh-32-bulk-stock-adjustments`,
working tree limpio salvo este plan.

Lo que YA existe y este cambio reutiliza sin tocar su comportamiento:

* `domain.model.Product.adjustStock(int delta)` — devuelve un Product NUEVO con
  la cantidad ajustada. Rechaza `delta == 0` (`IllegalArgumentException` → 400),
  calcula en `long` para no desbordar (D-020, → 400) y lanza
  `InsufficientStockException` (→ 409) si el resultado quedaría negativo. **Es
  una función pura: no persiste nada.** Esa pureza es la base de la atomicidad
  de este cambio: se puede calcular el resultado de TODOS los ajustes antes de
  escribir el primero.
* `domain.model.StockMovement.record(before, after, occurredAt)` — deriva el
  delta de las cantidades (D-023).
* `application.usecase.AdjustStockUseCase.handle(Long, int)` — `@Transactional`
  (D-022); los `@Transactional` de los adapters (`REQUIRED`) se unen a esa
  transacción, así que producto y movimiento se commitean o revierten juntos.
  El `Clock` viene inyectado (`ClockProducer`).
* Puertos `ProductRepository` (`findById` devuelve solo ACTIVE, D-024) y
  `StockMovementRepository`.
* Mappers HTTP ya registrados: `IllegalArgumentExceptionMapper` → 400,
  `ProductNotFoundExceptionMapper` → 404, `InsufficientStockExceptionMapper` →
  409, `InvalidRequestBodyExceptionMapper` → 400 con `ApiError` para cuerpos con
  campos no admitidos (`quarkus.jackson.fail-on-unknown-properties=true`).
* Dientes vigentes: `ArchitectureTest` (dominio sin framework),
  `OpenApiContractTest` (contrato == código), `OpenApiFidelityTest` (prohíbe
  `schema: {}`), JaCoCo 80/80, PIT sobre `domain.*` y `application.*` (umbral
  de mutación 80), SpotBugs.

Lo que NO existe hoy: cualquier forma de aplicar más de un ajuste. El único
camino de escritura de stock es `POST /inventory/products/{id}/stock-adjustments`
(D-025), un producto por petición. Dos peticiones seguidas no son una operación:
si la segunda falla, la primera ya está commiteada.

Persistencia: `product` (V1/V2) y `stock_movement` (V3). **Este cambio no
necesita migración**: no hay columnas ni tablas nuevas.

## Decisiones (validadas con el usuario antes de implementar)

### 1. Recurso nuevo `POST /inventory/stock-adjustments`

Body:

```json
{ "adjustments": [ { "productId": 1, "delta": 5 }, { "productId": 2, "delta": -3 } ] }
```

Respuesta **200** con un array de `ProductResponse` (`id`, `name`, `sku`,
`quantity`) en el **orden de la petición**: informa la cantidad resultante de
cada producto ajustado (criterio del issue) sin inventar una segunda forma de
representar un producto ajustado — es exactamente lo que devuelve hoy el ajuste
individual y `GET /inventory/products`.

Vive en un adapter nuevo `infrastructure.rest.StockAdjustmentResource`, no en
`ProductResource`: la operación es sobre el inventario, no sobre un producto, y
así no depende del desempate de JAX-RS entre el segmento literal
`stock-adjustments` y la plantilla `{id}` (de tipo `Long`) del recurso actual.

*Alternativas descartadas:* `POST /inventory/products/stock-adjustments` (queda
colgando de un recurso cuya clave es `{id}`) y sustituir el endpoint individual
por el bulk (rompería el contrato publicado sin que el issue lo pida).

**El endpoint individual se conserva y pasa a delegar en el mismo caso de uso**
(un batch de un elemento), para que la regla de ajuste siga viviendo en un solo
sitio y no puedan divergir: sigue siendo un único camino de escritura de stock
en el sentido de D-025.

El envoltorio `{ "adjustments": [...] }` (en vez de un array desnudo) deja sitio
para campos a nivel de operación sin romper el contrato — el issue deja fuera de
alcance idempotencia y motivo del movimiento, que son exactamente eso.

### 2. Fail-fast: la primera violación rechaza toda la operación

La respuesta de error informa **un** problema, con el código que ya usa la API y
un `ApiError` cuyo mensaje nombra al producto ofensor:

| Situación | Código | Excepción |
| --------- | ------ | --------- |
| `adjustments` vacío, ausente o nulo | 400 | `IllegalArgumentException` |
| `productId` repetido en la misma operación | 400 | `IllegalArgumentException` |
| `productId` nulo en un ajuste | 400 | `IllegalArgumentException` |
| `delta == 0` | 400 | `IllegalArgumentException` (ya en `Product`) |
| resultado fuera del rango de `int` | 400 | `IllegalArgumentException` (D-020) |
| producto inexistente (o eliminado) | 404 | `ProductNotFoundException` |
| el ajuste dejaría el stock negativo | 409 | `InsufficientStockException` |

**Por qué fail-fast:** reutiliza las excepciones y los mappers existentes, no
añade schema de error al contrato y evita decidir qué código gana cuando se
mezclan un 404 y un 409 en la misma petición. El issue exige rechazo total, no
un informe completo de los rechazos. *Descartado:* reporte agregado con
`errors[]`.

**El orden de evaluación es el de la petición**, y por familias: forma del batch
(400) → existencia de los productos (404) → reglas del ajuste (400/409). Así el
error es determinista y no depende del orden en que la base devuelva las filas.

### 3. `StockAdjustment` y `StockAdjustmentBatch` viven en el dominio

* `domain.model.StockAdjustment` — `record (Long productId, int delta)`.
  Constructor compacto: `productId` obligatorio.
* `domain.model.StockAdjustmentBatch` — `record (List<StockAdjustment>
  adjustments)` con factory `of(List<StockAdjustment>)` que rechaza la lista
  nula o vacía y los `productId` repetidos, y guarda una copia inmutable
  (`List.copyOf`).

**Por qué en el dominio y no en el caso de uso:** "un mismo producto no puede
aparecer dos veces en la misma operación" es una regla de negocio, pura y
verificable sin mocks; en el dominio queda dentro del alcance de PIT
(`domain.*`), así que la cubre el umbral de mutación. Cero framework: lo vigila
`ArchitectureTest`.

### 4. Atomicidad: calcular todo, escribir después (y la transacción como red)

`AdjustStockUseCase.handleAll` en pseudocódigo:

```java
@Transactional
List<Product> handleAll(List<StockAdjustment> items) {
  batch = StockAdjustmentBatch.of(items);            // 400: vacío / duplicado / productId nulo

  before = batch.adjustments().stream()              // 404 en el primero que falte,
      .map(a -> repository.findById(a.productId())   //     en orden de petición
                 .orElseThrow(() -> new ProductNotFoundException(a.productId())))
      .toList();

  after = zip(before, batch).map(p.adjustStock(delta)).toList();  // 400 / 409, sin escribir

  Instant now = clock.instant();                     // el mismo instante para todo el batch
  return zip(before, after)
      .map(pair -> { Product saved = repository.update(pair.after);
                     movements.save(StockMovement.record(pair.before, pair.after, now));
                     return saved; })
      .toList();
}
```

Ninguna validación escribe: cuando se rechaza la operación **no se ha llamado a
`update` ni a `save` ni una vez**, así que "ningún producto modifica su stock y
no se genera ningún movimiento" no depende del rollback. El `@Transactional`
sigue siendo la red para un fallo **durante** la escritura (p. ej. el movimiento
del segundo producto), que es lo que prueba `StockAdjustmentAtomicityTest`.

`clock.instant()` se toma **una vez** para todo el batch: los movimientos de una
misma operación comparten `occurredAt`; el desempate por `id desc` del historial
(D-023) ya existía para ese caso.

`handle(Long id, int delta)` queda como
`handleAll(List.of(new StockAdjustment(id, delta))).getFirst()` — mismo
comportamiento observable que hoy (los tests actuales del ajuste individual no
cambian y son el diente de la no-regresión).

## Cambios propuestos

```
apps/api/src/main/java/com/gentleman/inventory/
  domain/model/StockAdjustment.java                 NUEVO   record (productId, delta)
  domain/model/StockAdjustmentBatch.java            NUEVO   of(): no vacío, sin duplicados
  application/usecase/AdjustStockUseCase.java       MOD     + handleAll(...); handle() delega
  infrastructure/rest/StockAdjustmentResource.java  NUEVO   POST /inventory/stock-adjustments
  infrastructure/rest/BulkStockAdjustmentRequest.java NUEVO { adjustments: [...] }
  infrastructure/rest/BulkStockAdjustmentItem.java  NUEVO   { productId, delta }
apps/api/src/test/java/com/gentleman/inventory/
  domain/model/StockAdjustmentBatchTest.java        NUEVO
  application/usecase/AdjustStockUseCaseTest.java   MOD     + casos de handleAll
  infrastructure/rest/BulkStockAdjustmentResourceTest.java NUEVO (@QuarkusTest, extremo a extremo)
  infrastructure/rest/StockAdjustmentAtomicityTest.java    MOD + rollback a mitad del batch
contracts/openapi.yaml                              REGEN   nuevo path + 2 schemas
orders-platform/DECISIONS.md                        + D-029
orders-platform/specs/github-32/plan.md             este documento
```

Sin tocar: migraciones Flyway, `ProductRepository`/`StockMovementRepository`
(los puertos bastan tal cual), adapters de persistencia, `ProductResource` salvo
nada (el endpoint individual no cambia de firma), `harness`, workflows.

## Impacto

| Capa | Impacto |
| ---- | ------- |
| Dominio | **sí** — dos tipos nuevos (`StockAdjustment`, `StockAdjustmentBatch`); `Product` no cambia |
| Aplicación | **sí** — `AdjustStockUseCase.handleAll`; `handle` pasa a delegar |
| Infraestructura REST | **sí** — recurso, request y item nuevos; mappers existentes sin cambios |
| Persistencia | **no** — sin migración, sin cambios en adapters ni entidades |
| Contrato (D6) | **sí** — `POST /inventory/stock-adjustments` + schemas `BulkStockAdjustmentRequest` y `BulkStockAdjustmentItem` |
| Concurrencia | sin cambio: sigue vigente el riesgo aceptado de D-021 (read-modify-write sin bloqueo), ahora sobre N productos |

## Casos de test en orden (D3 — RED → GREEN → triangulate → refactor)

**Dominio** (`StockAdjustmentBatchTest`, sin mocks):

1. `of` conserva los ajustes en el orden recibido. ← **primer RED** (los tipos
   no existen).
2. `of` rechaza una lista vacía.
3. `of` rechaza `null`.
4. `of` rechaza el mismo `productId` dos veces, y el mensaje nombra ese id.
5. `of` acepta el mismo `delta` en productos distintos (triangulación: que no se
   deduplique por delta ni por el objeto entero).
6. `StockAdjustment` rechaza `productId` nulo.
7. La lista que devuelve `of` es inmutable (defensa contra mutación externa).

**Aplicación** (`AdjustStockUseCaseTest`, mocks locales de los puertos):

8. `handleAll` ajusta dos productos y devuelve las cantidades resultantes en el
   orden de la petición.
9. `handleAll` registra **un movimiento por ajuste**, con `previousQuantity` /
   `resultingQuantity` correctos y el mismo `occurredAt` del reloj fijo.
10. `handleAll` con un producto inexistente lanza `ProductNotFoundException` y
    **no llama a `update` ni a `save` ninguna vez**, aunque el producto que iba
    antes en la lista sí existiera.
11. `handleAll` con un ajuste que dejaría el stock negativo lanza
    `InsufficientStockException` y no llama a `update` ni a `save`.
12. `handleAll` con `delta == 0` lanza `IllegalArgumentException` y no escribe.
13. `handle(id, delta)` sigue comportándose igual (los 4 tests actuales del
    ajuste individual deben pasar sin tocarlos, tras la delegación).

**API** (`BulkStockAdjustmentResourceTest`, `@QuarkusTest` + RestAssured):

14. Camino feliz: dos productos (uno entrada, otro salida) → 200, cantidades
    resultantes en el body, y `GET /{id}/stock-movements` muestra el movimiento
    de cada uno.
15. Un `productId` inexistente → 404 y el producto válido de la misma petición
    **conserva su cantidad**; su historial sigue vacío.
16. Un ajuste que dejaría negativo → 409, ninguna cantidad cambia, ningún
    movimiento nuevo.
17. `productId` duplicado → 400 y nada cambia.
18. `adjustments` vacío → 400. `adjustments` ausente → 400.
19. `delta` cero → 400.
20. `productId` ausente en un item → 400.
21. Producto eliminado (soft delete, D-024) dentro del batch → 404 y nada
    cambia.

**Atomicidad** (`StockAdjustmentAtomicityTest`, `@InjectMock` del puerto):

22. Si falla el `save` del movimiento del **segundo** producto, el **primero**
    tampoco cambia su stock (rollback de la transacción; el caso que las
    validaciones previas no cubren porque el fallo ocurre escribiendo).

**Contrato** (dientes existentes):

23. `OpenApiContractTest` en rojo al aparecer el endpoint → regenerar
    `contracts/openapi.yaml` y commitearlo en el mismo cambio (HARNESS B).
    `OpenApiFidelityTest` exige que los cuatro códigos (200/400/404/409) lleven
    schema.

## Verificación (D4)

```bash
./harness verify      # tests + JaCoCo 80/80 + SpotBugs + contrato; evidencia en artifacts/harness/
./harness mutation    # PIT sobre domain.* y application.*: la lógica nueva vive ahí
```

Regeneración del contrato (HARNESS B), con la app en dev:

```bash
cd orders-platform/apps/api && ./mvnw quarkus:dev
curl -s localhost:8080/q/openapi -o ../../contracts/openapi.yaml
```

Y una comprobación manual del rechazo total contra la app en dev (evidencia
además de los tests): ajustar dos productos donde el segundo no existe y ver que
el primero conserva su cantidad.

## Assumptions

* El issue no pide exponer el batch en el front ni cambiar el endpoint
  individual: ambos coexisten.
* "El delta no puede ser cero" ya está implementado en `Product.adjustStock`; el
  batch no lo reimplementa, lo hereda.
* Un producto eliminado (`status = DELETED`) cuenta como inexistente → 404,
  coherente con D-024.
* El límite de tamaño del batch queda fuera de alcance (el issue no lo menciona);
  si aparece la necesidad, es aditivo (400 al superar N).
* Sigue vigente D-021: el ajuste es read-modify-write sin bloqueo. Dos
  operaciones simultáneas sobre el mismo producto pueden perder una; el issue
  deja fuera idempotencia y concurrencia.

## Open questions

Ninguna. Las cuatro decisiones de diseño se cerraron con el usuario antes de
implementar (ruta y forma del recurso, fail-fast, cuerpos, códigos de duplicado
y lista vacía).

## Resultado

### Ciclo TDD (rojos observados)

* **RED 1** — `cannot find symbol: StockAdjustment / StockAdjustmentBatch`.
  GREEN: los dos records, sin ninguna validación todavía.
* **RED 2–4** — `Expected java.lang.IllegalArgumentException to be thrown, but
  nothing was thrown` ×3 (lote vacío, lote nulo, producto repetido). GREEN: las
  tres guardas en la construcción del lote.
* **RED 5–6** — el ítem nulo dentro del lote daba
  `NullPointerException` en vez de un rechazo (habría salido **500**, no 400), y
  el `productId` nulo no se rechazaba. Este rojo **no estaba en el plan**: apareció
  al escribir el caso y era un defecto real del borde. GREEN: guarda de elementos
  nulos en el lote y `productId` obligatorio en `StockAdjustment`.
* **RED 7** — `cannot find symbol: handleAll`. GREEN **ingenuo a propósito**: un
  solo recorrido que busca, ajusta y escribe cada ajuste.
* **RED 8–10 (el rojo que define el issue)** — con ese recorrido, los tres casos
  de rechazo fallaron con el mensaje exacto del defecto:

  ```
  Never wanted here: handleAll_no_escribe_nada_cuando_un_producto_del_lote_no_existe
  But invoked here:  AdjustStockUseCase.lambda$handleAll$2(AdjustStockUseCase.java:59)
  ```

  El `update` del primer producto ya había ocurrido cuando el segundo falló.
  GREEN: las tres fases (cargar → calcular → escribir).
* **REFACTOR** — `handle(id, delta)` pasa a ser
  `handleAll(List.of(new StockAdjustment(id, delta))).getFirst()`; los 4 tests
  del ajuste individual siguieron verdes sin tocarlos.
* **RED 11** — la API respondía `404` al POST del lote (`Expected status code
  <200> but was <404>`). GREEN: `StockAdjustmentResource` + request e ítem.
* **RED 12 (contrato, HARNESS B)** — `OpenApiContractTest`: *El OpenAPI del
  código difiere de contracts/openapi.yaml*. GREEN: contrato regenerado con
  `curl` (nuevo path + `BulkStockAdjustmentRequest` y `BulkStockAdjustmentItem`,
  los cuatro códigos con schema).
* **RED 13 (SpotBugs, HARNESS I)** — `./harness verify` en **FAILED** con 4
  hallazgos Medium `EI_EXPOSE_REP` / `EI_EXPOSE_REP2`: los constructores
  canónicos de ambos records son públicos y no copiaban la lista (la copia
  estaba solo en la factory). GREEN sin excluir nada: la validación y la copia
  se mueven al **constructor compacto**, así ninguna vía de construcción crea un
  lote inválido ni queda atada a una lista ajena.

Los tests de API (10) y el de rollback pasaron a la primera, porque el
comportamiento ya lo habían empujado los rojos de dominio y aplicación. Por eso
se les dio dientes con la ronda de mutación.

### Mutaciones a mano (cada mutante se aplica comprobando que el texto original exista)

| Mutante | Suite que debía matarlo | Resultado |
| ------- | ----------------------- | --------- |
| `handleAll` sin `@Transactional` | `StockAdjustmentAtomicityTest` | **muerto** |
| una sola pasada: escribe mientras recorre | `AdjustStockUseCaseTest` | **muerto** |
| sin control de duplicados | `StockAdjustmentBatchTest` | **muerto** |
| duplicados por delta, no por producto | `StockAdjustmentBatchTest` | **muerto** |
| sin copia defensiva (`List.copyOf`) | `StockAdjustmentBatchTest` | **muerto** |
| sin guarda de elementos nulos | `StockAdjustmentBatchTest` | **muerto** |
| sin guarda de `productId` nulo | `StockAdjustmentBatchTest` | **muerto** |
| lee el reloj una vez por ajuste | `AdjustStockUseCaseTest` | **muerto** |

El último exigió reforzar antes el test del instante: con `Clock.fixed` no se
distingue leer la hora una vez de leerla N veces, así que ese test usa ahora un
`TickingClock` que avanza un segundo en cada lectura.

### Verificación (D4)

* `./harness verify` → **`HARNESS RESULT: PASSED`**, `Tests run: 90, Failures: 0,
  Errors: 0, Skipped: 0` (68 antes del cambio + 22 nuevos), JaCoCo 80/80 y
  SpotBugs en verde. Evidencia:
  `artifacts/harness/20260818T013458Z-64824a7-dirty-ab730f2/`
  (`verification.json`: `result PASSED`, `exitCode 0`, `durationSeconds 15`,
  `source.state ab730f25…`).
* `./harness mutation` → `MUTATION RESULT: COMPLETED`, **47 mutantes, 44 muertos
  (94 %)**, test strength 98 % (umbral 80). El código nuevo mata todos los suyos:
  `AdjustStockUseCase` 6/6, `StockAdjustmentBatch` 3/3. Los 3 supervivientes son
  **preexistentes** y ajenos a este cambio: la frontera `quantity < 0` de
  `Product.requireQuantity` y los accessors de `ProductNotFoundException.id` y
  `DuplicateSkuException.sku` (sin cobertura).
* Suites del harness (gate obligatorio en CI): `contract_test.sh` 14,
  `parity_test.sh` 5, `selftest_gate_test.sh` 10, `state_test.sh` 16 — todas
  `PASSED`, 0 failed.
* Comprobación manual contra la app en dev (Postgres efímero, porque el repo no
  versiona un compose):

  ```
  lote válido      → [{"id":1,…,"quantity":15},{"id":2,…,"quantity":1}]
  lote con id 999999 → {"message":"No existe un producto con id: 999999"} [HTTP 404]
  estado después   → quantity 15 y 1  (el +100 del primer ajuste NO se aplicó)
  movimientos de 1 → un solo movimiento, el del lote válido
  ```

## Desviaciones respecto al plan

1. **Dónde viven las reglas del lote:** el plan las ponía en la factory
   `StockAdjustmentBatch.of`; SpotBugs (`EI_EXPOSE_REP2`) demostró que el
   constructor canónico del record seguía siendo una puerta abierta. Se movieron
   al constructor compacto y `of` quedó como delegación. Mejora el diseño: el
   lote inválido es irrepresentable.
2. **Elementos nulos del JSON:** el plan no los contemplaba. El caso
   `{"adjustments":[null]}` daba `NullPointerException` → 500. Ahora
   `BulkStockAdjustmentRequest` normaliza el hueco (cuerpo sin `adjustments` →
   lote vacío; ítem nulo → ajuste sin producto) y el **dominio** lo rechaza con
   400. La normalización usa `List.copyOf` porque es la copia que SpotBugs
   reconoce en el accessor del record; `Collections.unmodifiableList(new
   ArrayList<>(…))` seguía marcando `EI_EXPOSE_REP`.
3. **Dos tests extra** sobre lo planeado: el del ítem nulo (caso 5–6 arriba) y la
   separación del caso 7 del plan en copia defensiva + lista inmutable.
4. **El test del instante** se reforzó con `TickingClock`; el plan solo pedía
   comprobar `occurredAt`, y con un reloj fijo eso no tenía dientes.
5. **Regeneración del contrato:** hizo falta levantar un Postgres efímero
   (`docker run postgres:16-alpine`, mismos datos que documenta `%dev`) porque el
   repo no versiona el `docker-compose`. El contenedor se eliminó al terminar.
6. Todo lo demás (ruta, códigos, fail-fast, cuerpos, tres fases, delegación del
   ajuste individual, sin migración) salió como estaba planeado.
