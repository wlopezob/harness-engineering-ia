# DECISIONS

Registro de decisiones. El chat no es la fuente de verdad (D5 — Artifact Store).

## 2026-06-29 — Módulo inventory (primera feature)

### D-001 — Modelo de dominio separado de la entidad JPA
`domain.model.Product` es un POJO puro; `infrastructure.persistence.ProductEntity`
es la `@Entity` JPA. El adapter de persistencia mapea entre ambos.
**Por qué:** `ArchitectureTest` prohíbe que `domain` importe `jakarta..`. Una sola
clase con anotaciones JPA rompería los dientes de arquitectura.

### D-002 — Endpoint `POST /inventory/products` (sin prefijo `/api`)
Ruta de creación bajo el recurso `inventory/products`. Sin prefijo `/api` por
ahora; si más adelante se agrupa todo bajo `/api`, se actualiza el contrato.
**Por qué:** minimizar superficie de config; el contrato es la verdad y cambiarlo
es trivial.

### D-003 — SKU único → 409
La unicidad del SKU se garantiza con un `unique constraint` en la DB (fuente de
verdad). El caso de uso hace un pre-check (`existsBySku`) para devolver un 409
limpio. La constraint es el guardián real ante carreras.
**Por qué:** el SKU identifica al producto; duplicarlo no tiene sentido en stock.

### D-004 — Schema gestionado por Flyway, no Hibernate
`quarkus.hibernate-orm.database.generation=none` + `flyway.migrate-at-start=true`.
La tabla `product` se crea en `db/migration/V1__create_product_table.sql`.
**Por qué:** ya viene configurado así en el proyecto; migraciones versionadas.

### D-005 — Tests de integración con Quarkus Dev Services
Los `@QuarkusTest` levantan un PostgreSQL efímero vía Dev Services (imagen
`postgres:16-alpine`, ya presente localmente), aislado del Postgres de dev.
**Por qué:** no contaminar la DB de dev; tests reproducibles y aislados.

### D-006 — ID generado por la base de datos
`id BIGINT GENERATED ALWAYS AS IDENTITY` en Postgres; la entidad usa
`@GeneratedValue(strategy = IDENTITY)`.
**Por qué:** la DB es la autoridad del identificador.

## 2026-06-29 — Listar productos (feature 2)

### D-007 — `GET /inventory/products` devuelve `List<ProductResponse>` directo
El método `@GET` devuelve la lista tipada, no `jakarta.ws.rs.core.Response`.
**Por qué:** así smallrye-openapi infiere el schema (array de ProductResponse) y
el contrato generado (`/q/openapi`) es fiel. Con `Response` el contrato saldría
con `schema: {}`. El contrato es la verdad del límite con el front.

### D-008 — Dobles de test con Mockito (HARNESS D)
Los tests unitarios de casos de uso mockean el puerto con Mockito
(`quarkus-junit5-mockito`). Se migró `CreateProductUseCaseTest` (sesión 1 usaba
un fake a mano, que violaba HARNESS D).
**Por qué:** disciplina del repo; además el fake rompía al añadir `findAll` al
puerto.

### D-009 — Contrato generado, sincronizado por OpenApiContractTest
`contracts/openapi.yaml` es la salida de smallrye (`/q/openapi`). Todo cambio de
superficie exige regenerarlo (`curl ... -o`) en el mismo cambio; el test
`OpenApiContractTest` falla si difieren (comparación de string exacto).
**Por qué:** los dientes de HARNESS B ya existen; el front depende del contrato.

## 2026-06-29 — Consultar producto por id (feature 3)

### D-010 — `findById` devuelve `Optional<Product>`; ausencia → 404
El puerto expone `Optional<Product> findById(Long)` (HARNESS C: sin null). El
caso de uso `GetProductUseCase` convierte la ausencia en
`ProductNotFoundException` (dominio), que el mapper traduce a **404** con cuerpo
`ApiError`. Mismo patrón que `DuplicateSkuException`→409.
**Por qué:** "responder apropiadamente" ante un id inexistente = 404 explícito,
no un 200 vacío ni un null.

### D-011 — Dobles con Mockito en variables locales (no campos)
Se mantiene el patrón de [D-008]: mocks como variables locales para no chocar con
`el_nucleo_es_inmutable` (campos del núcleo deben ser final).
**Por qué:** concilia HARNESS C (inmutabilidad) y HARNESS D (Mockito) sin tocar
la regla ArchUnit.

## 2026-06-29 — Contrato fiel (HARNESS B, feature 4)

### D-012 — Fidelidad del contrato vía `@APIResponse`
El recurso REST declara TODOS sus códigos y cuerpos con anotaciones
microprofile-openapi (`@APIResponse`/`@Content`/`@Schema`/`@Header`), que trae
`quarkus-smallrye-openapi` (sin dependencia nueva):
- `create` (devuelve `Response`): 201 `ProductResponse` + header `Location`,
  400 `ApiError`, 409 `ApiError`. Declarar `@APIResponse` elimina el `200`/empty
  que smallrye infería por defecto.
- `getById`: 200 `ProductResponse`, 404 `ApiError`.
**Por qué:** dientes `OpenApiFidelityTest` (prohíbe `schema: {}`) y la regla de
HARNESS B (prohíbe `Response` crudo sin `@APIResponse`). El contrato es la verdad
del límite con el front: debe describir errores, no solo el happy path.
Reemplaza/complementa el contrato minimalista de [D-009].

## 2026-06-29 — Persistencia con Panache (HARNESS E)

### D-013 — Repository pattern de Panache; fuera EntityManager/JPQL a mano
La persistencia usa **Panache (repository pattern)**:
- `ProductPanacheRepository implements PanacheRepository<ProductEntity>` aporta
  el CRUD.
- `ProductRepositoryAdapter` implementa el puerto `ProductRepository`, delega en
  el Panache repo y mapea `ProductEntity ↔ Product`. (Reemplaza a
  `ProductRepositoryJpa`, que usaba `EntityManager`+JPQL.)
- Mapeo de operaciones: `persist` (IDENTITY asigna el id), `count("sku", sku)`,
  `listAll(Sort.by("id"))`, `findByIdOptional(id)`.
- Dependencia: `quarkus-hibernate-orm` → `quarkus-hibernate-orm-panache` (trae
  hibernate-orm transitivamente).
- Los tipos Panache/JPA **no cruzan** fuera de `infrastructure`; el resto habla
  con el puerto POJO.
**Por qué:** simplifica el adapter (sin JPQL ni EntityManager a mano) cumpliendo
HARNESS E. Refactor de comportamiento idéntico: la suite (22 tests) sigue verde,
con `ProductResourceTest` (Postgres real) como red de seguridad.

## 2026-06-29 — Editar y eliminar producto (feature 5)

### D-014 — Editar (PUT) name+quantity; SKU inmutable; eliminar (DELETE) → 204
- Editar: `PUT /inventory/products/{id}` con `{name, quantity}`. El **SKU no se
  edita** (es el identificador); no va en el body. 200 con el producto; 400 si
  inválido; 404 si no existe.
- Eliminar: `DELETE /inventory/products/{id}` → **204 No Content**; 404 si no
  existe.
- Dominio: `Product.update(name, quantity)` devuelve un **nuevo** Product
  (inmutable, HARNESS C) con el mismo id+sku. Valida con `requireName`/
  `requireQuantity`, reutilizados también por `create`.
- Puerto: `update(Product)` (el adapter muta la entity gestionada vía dirty
  checking) y `deleteById(Long)` (delega en `products.deleteById`; devuelve
  boolean → el use case lanza `ProductNotFoundException` si es false).
**Por qué:** "responder apropiadamente" ante un id inexistente = 404 (mismo
patrón que [D-010]). PUT para reemplazar el estado editable; DELETE idempotente
con 204 sin cuerpo.

### D-015 — ArchUnit gobierna solo producción (DoNotIncludeTests)
`@AnalyzeClasses` excluye las clases de test (`importOptions = DoNotIncludeTests`).
Las reglas de arquitectura aplican al código de producción, no a los tests.
**Por qué:** la regla `el_nucleo_es_inmutable` (HARNESS C) cazaba campos `@Mock`
(HARNESS D), que no pueden ser `final`. Choque C×D resuelto de raíz: las reglas no
deben policiar tests. Libera el `@Mock` en campos idiomático.

## 2026-08-13 — SpotBugs vs. inyección por constructor (HARNESS I)

### D-016 — Excluir EI_EXPOSE_REP2 en `application.usecase`
`spotbugs-exclude.xml` excluye el patrón `EI_EXPOSE_REP2` para el paquete
`application.usecase`. SpotBugs marcaba los 5 casos de uso
(`CreateProductUseCase`, `DeleteProductUseCase`, `GetProductUseCase`,
`ListProductsUseCase`, `UpdateProductUseCase`) por guardar el `ProductRepository`
recibido en el constructor.
**Por qué:** es el patrón de inyección de dependencias prescrito por HARNESS A/D
(el caso de uso recibe el puerto por constructor); no es una fuga real de estado
mutable de dominio. HARNESS I exige justificar falsos positivos antes de
excluirlos — esta es esa justificación. El alcance de la exclusión se limita al
paquete `application.usecase`, no al proyecto completo.

## 2026-08-16 — Lifecycle de work item externo (github-14)

### D-017 — El harness no integra proveedores de work items
El Engineering Harness NO conoce GitHub, Jira ni Azure DevOps, y no depende de un
LLM concreto. Leer el work item externo es una capacidad del **agente** (MCP,
skills, connectors). El CLI (`./harness`) sigue con `verify`, `format` y
`mutation`: impone restricciones, ejecuta verificaciones y genera evidencia; no
obtiene requerimientos. Quedan fuera, por decisión: cliente de API de proveedor,
`gh issue view` dentro del CLI, flag `--source`, y sincronización automática de
work items.
**Por qué:** son dos responsabilidades distintas. Acoplarlas ataría el harness a
un proveedor y a un modelo, y lo volvería inservible en cualquier repo cuyo
trabajo se planifique en otra plataforma. La frontera mantiene el harness
portable. Ver `specs/github-14/plan.md`.

### D-018 — Separación de artefactos: work item = QUÉ, `plan.md` = CÓMO
El work item externo es la fuente de verdad del **requerimiento** y no se copia
al repositorio. `specs/<work-item>/plan.md` es la fuente de verdad del **diseño
técnico** y vive versionado junto al código. Cuando el trabajo nace de una fuente
externa, `<feature>` usa un identificador trazable (`github-14`, `SUP-123`,
`ado-45821`) en vez del nombre de dominio. `plan.md` no reemplaza a Jira ni a
GitHub Issues: no gestiona trabajo, describe estrategia técnica. Un cambio
trivial puede prescindir de él; lo requieren las tareas con más de un
comportamiento o con decisiones técnicas.
**Por qué:** el chat es temporal y el issue vive fuera del repo. Sin el plan
versionado, el CÓMO no sobrevive a la sesión y el estado del trabajo no puede
entenderse leyendo el repositorio. El ID trazable cierra el vínculo
requerimiento ↔ implementación sin duplicar contenido (D1/D2/D5).
## 2026-08-17 — Ajustes de stock (github-21)

### D-019 — Un endpoint de ajuste con delta firmado, stock insuficiente → 409
El ajuste de stock se expone como
`POST /inventory/products/{id}/stock-adjustments` con body `{ "delta": n }`:
positivo es entrada, negativo es salida. Devuelve **200** con el producto ya
ajustado. Códigos de error: **400** para un ajuste inválido (`delta == 0` o
resultado fuera del rango admitido), **404** si el producto no existe y **409**
si la salida dejaría el stock en negativo.
**Por qué:** entrada y salida son el mismo concepto de negocio (un ajuste) y el
signo decide la dirección; dos rutas `increase`/`decrease` duplicarían superficie
de contrato y tests para la misma regla. El 409 es coherente con
`DuplicateSkuException` (D-013): pedir más stock del disponible es un conflicto
con el estado actual del recurso, no un request malformado — mezclarlo con 400
impediría al cliente distinguir "pediste mal" de "no hay stock". Se descartó 422
por no introducir un cuarto código sin precedente en el repo. La regla vive en
`Product.adjustStock` (dominio), no en el recurso REST. Ver
`specs/github-21/plan.md`.

### D-020 — La aritmética del ajuste se calcula en `long`
`Product.adjustStock` calcula `(long) quantity + delta` y rechaza con 400 el
resultado que exceda `Integer.MAX_VALUE`.
**Por qué:** en `int`, un delta cercano a `Integer.MAX_VALUE` desborda y produce
un resultado **negativo**, que se persistiría como cantidad negativa: viola el
criterio de aceptación del work item por la puerta de atrás. El test
`adjust_stock_rechaza_un_delta_que_desborda_el_rango_de_int` lo demostró en rojo
— antes del fix, una *entrada* de stock respondía "stock insuficiente".

### D-021 — Concurrencia del ajuste: riesgo conocido y aceptado
El ajuste es read-modify-write (`findById` → `adjustStock` → `update`) sin
bloqueo. Dos ajustes simultáneos sobre el mismo producto pueden perder uno
(last-write-wins). **No se resuelve en este cambio.**
**Por qué:** el work item no lo pide y deja fuera de alcance auditoría, reservas
y multi-almacén; resolverlo agregaría una migración (`@Version`) y complejidad
sin un caso de uso que la justifique todavía. Se registra en vez de resolverse en
silencio para que la limitación sea visible. Si aparece la necesidad real: bloqueo
optimista con `@Version` o un `UPDATE` relativo en el adapter de persistencia.

## 2026-08-17 — Historial de movimientos de stock (github-24)

### D-022 — El caso de uso demarca la transacción del ajuste
`AdjustStockUseCase.handle` lleva `@jakarta.transaction.Transactional`. Los
`@Transactional` de los adapters (propagación `REQUIRED`) se unen a esa
transacción, así que el `update` del producto y el `save` del movimiento se
commitean o se revierten juntos; cualquier `RuntimeException` revierte ambos.
Es la primera vez que `application` demarca transacciones; `ArchitectureTest`
lo permite (solo prohíbe `jakarta..` en `domain`).
**Por qué:** el work item exige que ajuste y movimiento sean atómicos. Con la
transacción por método del adapter, el `update` commiteaba solo: el test
`si_falla_el_registro_del_movimiento_el_stock_no_cambia` lo demostró en rojo
(cantidad 15 en vez de 10 tras un fallo simulado del `save`). Se descartaron un
método de puerto compuesto (mezcla dos agregados y esconde la regla en infra) y
un puerto `UnitOfWork` propio (abstracción sin segundo uso).

### D-023 — `StockMovement` como agregado propio; `Clock` inyectado; ruta `stock-movements`
- `domain.model.StockMovement` es un `record` inmutable con
  `id, productId, delta, previousQuantity, resultingQuantity, occurredAt`. La
  factory `record(before, after, occurredAt)` deriva el delta de las cantidades
  para que no pueda grabarse un movimiento inconsistente con ellas.
- La fecha/hora la aporta un `java.time.Clock` inyectado en el caso de uso
  (`infrastructure.config.ClockProducer` expone `Clock.systemUTC()`; los tests
  usan `Clock.fixed`). Ni `Instant.now()` en el núcleo (rompe HARNESS C) ni
  `default now()` en la DB (el movimiento viajaría con `occurredAt` nulo).
- Historial en `GET /inventory/products/{id}/stock-movements` → 200 lista
  (`occurred_at desc, id desc`; `id` desempata el mismo instante) / 404. No se
  reutiliza `stock-adjustments` porque ese POST devuelve `ProductResponse`, no el
  ajuste creado; darle un GET con otra representación sería incoherente.
- Persistencia: `stock_movement` (V3) con FK simple a `product`, sin cascade.
**Por qué:** ver `specs/github-24/plan.md`. El puerto `StockMovementRepository`
mantiene los tipos JPA dentro de infraestructura (HARNESS E).

### D-024 — Borrar un producto es un cambio de estado (soft delete)
Decisión del usuario en github-24: `DELETE /inventory/products/{id}` **no borra
la fila**; el producto pasa a `status = DELETED` (`ProductStatus`, columna V2
con `default 'ACTIVE'`). `Product.markDeleted()` devuelve un nuevo Product;
`DeleteProductUseCase` hace `findById → update(markDeleted())`; el puerto pierde
`deleteById`. `ProductRepository.findById/findAll` devuelven **solo activos**:
para la API un producto eliminado no existe (404 en GET/PUT/DELETE/ajuste/
historial, ausente en la lista); el estado no se expone en `ProductResponse`.
Consecuencias acordadas: el historial del producto eliminado se **conserva** en
la BD (auditoría) pero **no es consultable** (404); el SKU sigue **reservado**
(`unique(sku)` intacto → 409 al reutilizarlo).
**Por qué:** el usuario quiere conservar el historial cuando se elimina un
producto; con borrado físico la FK de `stock_movement` haría fallar el DELETE
(500) o exigiría cascade y perder el historial. El contrato del DELETE no
cambia (204/404), solo su efecto. Alternativas descartadas: rechazar el borrado
con 409 si tiene movimientos (cambia el comportamiento existente) e índice
único parcial para reutilizar el SKU (fuera de alcance).

### D-025 — El `PUT` no edita `quantity`: el stock solo se mueve con un ajuste
Detectado en el review del PR #25: `PUT /inventory/products/{id}` cambiaba la
cantidad **sin registrar movimiento**, así que el historial podía dejar de
explicar el stock (reproducido: `10 + Σ deltas = 12` con stock real `97`, cadena
rota por un salto de +85). Decisión: `UpdateProductRequest` pasa a `{name}` y
`Product.update(name)` conserva la cantidad; el stock solo cambia por
`POST /{id}/stock-adjustments`. Un `PUT` que traiga `quantity` responde **400**
(`quarkus.jackson.fail-on-unknown-properties=true` + `InvalidRequestBodyExceptionMapper`,
que devuelve `ApiError` en vez del 400 sin cuerpo del mapper built-in de
Quarkus). El diente es `el_historial_explica_siempre_la_cantidad_actual`:
comprueba que la cadena de movimientos no tiene saltos y que
`cantidad inicial + Σ deltas == cantidad actual`.
**Por qué:** hace el estado inválido **irrepresentable** en vez de repararlo —
un solo camino de escritura del stock, imposible olvidar el movimiento en un
caso de uso futuro. Se descartó registrar un movimiento desde el `PUT` (deja dos
caminos con reglas distintas — el `PUT` no conoce "stock insuficiente" ni el
rechazo de delta 0 — y, sin campo "motivo" (fuera de alcance del issue), una
corrección sería indistinguible de una entrada real), rechazar solo si el valor
difiere (409 con semántica rara para un `PUT` y expuesto a carreras) y dejarlo
documentado (incumple el objetivo del work item a sabiendas). Hay impacto de
contrato (D6) pero **no hay consumidores** (`apps/` solo contiene `api`): es el
momento más barato para cerrarlo. Matiza [D-014], que estableció `PUT`
name+quantity. Si más adelante hace falta fijar un valor absoluto auditado, se
agrega de forma aditiva al recurso de ajustes (`{"targetQuantity": n}`).

## 2026-08-17 — Identidad del código verificado (github-26)

### D-026 — La evidencia identifica el estado exacto verificado, no solo `HEAD`
`./harness verify` registraba el commit `HEAD` aunque hubiera cambios sin
commit, así que una evidencia podía parecer que correspondía a un commit limpio
cuando se había verificado otra cosa. Ahora el harness calcula, **antes de
ejecutar Maven**, un identificador determinista del árbol:

```
manifiesto = "<git-hash-object del contenido en disco> <path>" por archivo,
             ls-files --cached (deduplicado) + --others --exclude-standard,
             ordenados TODOS juntos byte a byte por path
state      = git hash-object --stdin < manifiesto
```

El orden es **global**, no por grupos: git lista primero los tracked y luego los
untracked, así que agrupar hacía que un `git add` sobre un archivo sin modificar
cambiara el identificador — describía el índice, no el contenido. `LC_ALL=C` en
bash y el `StringComparer` ordinal de .NET en `harness.cmd`, porque el
`sort.exe` de Windows ordena según el locale.

`verification.json` sube a `schemaVersion 1.1` y añade un bloque `source` con
`dirty`, `state`, `stateAlgorithm`, `scope`, `changedFiles` y `manifest`; el
manifiesto se guarda como evidencia (`source-state.txt`), de modo que el
identificador es **auditable y recomputable** con un solo comando de git. Con
cambios locales, el directorio pasa a llamarse
`<timestamp>-<sha>-dirty-<state7>` y la consola lo avisa al principio y al
final. Alcance: todo el árbol versionable (tracked + untracked no ignorados);
`artifacts/` y `target/` están en `.gitignore`, así que lo que genera la propia
verificación no altera la identidad.

**Por qué `git hash-object` y no `sha256sum`:** no escribe nada en el
repositorio (verificado: 555 objetos antes y después), git ya es un requisito
del harness — `sha256sum` no existe en macOS y en Windows haría falta
`certutil`/PowerShell — y sabe normalizar los finales de línea. Esa
normalización hay que **fijarla** con `-c core.autocrlf=input`: por defecto cada
máquina aplica su propia config, y para archivos que git guarda con CRLF (los
`mvnw.cmd` del repo) Linux hashea el CRLF tal cual mientras Windows lo convierte
a LF. Lo destapó el job de paridad: 2 de 122 líneas del manifiesto diferían. **Por qué
un sort explícito y no el orden que emite git:** git lista cada grupo en orden
byte-wise, pero primero los tracked y después los untracked, así que ese orden
depende del índice — un `git add` lo alteraba. El orden se impone sobre todos
los paths juntos, y como el `sort.exe` de Windows ordena según el locale, la
comparación byte a byte se fija en cada implementación (`LC_ALL=C sort` en bash,
`StringComparer` ordinal de .NET en `harness.cmd`). Descartados: `git stash create` (no incluye untracked) y el índice temporal con
`write-tree` (identificador canónico y más rápido, pero **escribe blobs** en
`.git/objects`).

La paridad entre `./harness` y `harness.cmd` **se mide**: el workflow
`harness-selftest.yml` calcula el `state` en `ubuntu-latest` y en
`windows-latest` y falla si difieren. Fue la primera ejecución real de
`harness.cmd` y destapó cuatro defectos (barra final en `ROOT_DIR`, manifiesto
que contaminaba su propio cálculo, normalización de fin de línea sin fijar y
orden dependiente del índice); hoy está en verde. Se estrena `tests/` con
`tests/harness/state_test.sh` (bash plano, sin dependencias nuevas) y el
subcomando `./harness state`, que era la única forma de tener un RED antes del
código sin esperar a Maven. Ver `specs/github-26/plan.md`.

## 2026-08-17 — El Harness Self-Test bloquea el merge (github-28)

### D-027 — Un job agregador `Harness self-test` es el único required check del workflow
El workflow `Harness Self-Test` tenía cuatro jobs en verde que **no bloqueaban
nada**: el ruleset de `main` solo exigía `Maven verify` y `Dependency review`,
así que un PR que rompiera `./harness state` o la paridad bash/cmd se podía
mergear. Ahora el workflow expone un job final `gate` con `name: Harness
self-test`, que depende de **todos** los demás jobs (`self-test`,
`state-linux`, `state-windows`, `parity`), corre con `if: always()` y falla
salvo que cada `needs.<job>.result` sea `success`. Ese nombre es el contrato
con el ruleset: `main` exige ahora tres checks —`Maven verify`,
`Dependency review`, `Harness self-test`— con la misma configuración anterior
(`strict`, `do_not_enforce_on_create`, sin bypass).

**Por qué un agregador y no exigir los cuatro jobs:** los required checks se
identifican por el `name:` del job, así que exigirlos uno a uno acopla el
ruleset a nombres descriptivos y **un job nuevo no sería obligatorio** hasta
que alguien tocara el ruleset. Con el agregador el ruleset no cambia al
evolucionar el workflow, y la suite `tests/harness/selftest_gate_test.sh`
falla si el `needs` del gate no lista exactamente todos los demás jobs.
**Por qué `skipped` y `cancelled` cuentan como rojo:** GitHub reporta un job
omitido como *Success* y no bloquea el merge aunque sea required; si
`state-windows` falla, `parity` se omite y sin el gate el check omitido
"pasaría". **Por qué `if: always()` y no el `!cancelled()` que recomienda la
documentación:** con `!cancelled()` el gate quedaría omitido tras una
cancelación — justo el caso que debe quedar en rojo. El gate no puede
colgarse (evalúa un JSON que ya está en el runner) y `timeout-minutes: 2` es
la red. **Por qué la lógica vive en `.github/scripts/needs_all_succeeded.sh`
y no inline en el YAML:** un `run:` no se ejecuta en local, y D3 exige ver el
rojo antes del código; el script se prueba con JSON fabricado (`failure`,
`cancelled`, `skipped`, `{}`, entrada inválida) sin necesitar GitHub. Coste:
un checkout *sparse* de `.github/scripts` en el gate.

El runner de `tests/harness/state_test.sh` se extrajo a
`tests/harness/testlib.sh` al aparecer la segunda suite (sin cambio de
comportamiento: 16 passed antes y después). Ver `specs/github-28/plan.md`.

## 2026-08-17 — Paridad bash/cmd del harness (github-30)

### D-028 — El contrato público del harness se prueba con una sola suite contra las dos implementaciones
`./harness` y `harness.cmd` divergían en lo que **permitían hacer**: `mutation`
solo existía en bash, el `help` de cmd no lo listaba, un comando desconocido
mostraba en cmd un usage recortado, cmd aceptaba `VERIFY` (`if /I`), y la
evidencia de `verify` en cmd no copiaba `pit-reports`. Ahora las dos exponen
**el mismo conjunto de comandos** (`verify format mutation state help`, más
los alias `--help`/`-h`), el mismo texto de `help` módulo el nombre del
programa, la misma semántica de éxito/fallo (exit code de Maven propagado,
banner `… RESULT: FAILED`, `2` en validaciones previas y comando desconocido)
y la misma invocación de Maven por comando con el wrapper de cada plataforma
(`./mvnw` / `mvnw.cmd`). Paridad **no** significa el mismo código: cada
script sigue siendo idiomático de su plataforma.

**Cómo se mide, en dos capas.** (1) `tests/harness/contract_test.sh` es
**una sola suite** parametrizada por `HARNESS_IMPL=bash|cmd`: crea un repo
temporal con wrappers de Maven de mentira (`mvnw`/`mvnw.cmd`) que registran
los argumentos y salen con `HARNESS_TEST_MVNW_EXIT`, y ejecuta las **mismas
aserciones** contra el script de la plataforma. En CI corre en `ubuntu-latest`
contra `./harness` y en `windows-latest` (Git Bash, `cmd //c harness.cmd`)
contra `harness.cmd`; los dos jobs entran en el `needs` del gate, así que una
regresión de paridad deja rojo `Harness self-test`. (2)
`tests/harness/parity_test.sh` compara los dos scripts **sin ejecutarlos**
(despacho, help, argumentos de Maven, wrapper por plataforma) y corre también
en local: es lo que detecta que se añada o quite un comando en una
implementación y no en la otra sin esperar a Windows.

**Por qué una suite bash y no PowerShell:** `pwsh` existe en los tres runners
pero no en la máquina de desarrollo; con Git Bash la misma suite corre en
local (lado bash) y en `windows-latest` (lado cmd), y reutiliza el runner
`testlib.sh`. **Por qué paridad estricta de mayúsculas:** "un comando
desconocido debe fallar en ambas" no admite que `VERIFY` funcione solo en
Windows; se quitó el `/I`. **Por qué se igualó hacia el banner `FAILED`:**
bash abortaba por `set -e` con el exit code correcto pero sin resultado
principal; se alineó al lado más informativo sin cambiar ningún exit code.
Fuera de alcance mantenido: `mutation` no genera evidencia ni corre en cada
PR y nada de PIT cambia.

**Lo que demostró ejecutar de verdad:** el job de Windows encontró tres
defectos de `harness.cmd` que ninguna inspección de texto habría visto —
`find` resolvía al GNU find bajo Git Bash y recorría `C:\` entero (ahora
`%SystemRoot%\System32\find.exe`), un `set /a` con paréntesis dentro de un
bloque abortaba `verify` con 255 (ahora entre comillas) y un `exit /b 2` en un
`if` anidado llegaba como 0 a `cmd /c` (ahora `goto` a una etiqueta de nivel
superior). Y un cuarto que la suite **no vio hasta endurecerla**: `verify`
imprimía `Unbalanced parenthesis.` y dejaba `durationSeconds` en 0 de 00:00
a 09:59 porque `%TIME%` lleva un espacio inicial con horas de un dígito; el
job estaba en verde porque solo se miraban banners y exit codes. Ahora la
duración sale de un epoch UTC (una sola llamada a PowerShell, como `date +%s`
en bash) y **la suite de contrato falla ante cualquier error interno del
intérprete, exige que `verification.json` sea JSON real con `durationSeconds`
numérico que mida de verdad (Maven de mentira duerme 2 s) y `exitCode` igual
al código de salida**, con Maven pasando y fallando. Reglas que quedan: **en
`harness.cmd`, ejecutables externos con homónimo GNU van con ruta absoluta,
la aritmética de `set /a` va entre comillas, los `exit /b` con código van en
nivel superior, y nada se parsea de `%TIME%`; en la suite, una corrida con
errores internos del intérprete nunca es verde.** Ver `specs/github-30/plan.md`.

## 2026-08-17 — Ajustes de stock de varios productos (github-32)

### D-029 — Un lote de ajustes es una operación: se calcula entero antes de escribir nada
`POST /inventory/stock-adjustments` con `{ "adjustments": [ { "productId", "delta" } ] }`
aplica varios ajustes como UNA operación y responde **200** con un array de
`ProductResponse` en el orden de la petición (la cantidad resultante de cada
producto). El endpoint individual
`POST /inventory/products/{id}/stock-adjustments` **se conserva** y pasa a
delegar: `handle(id, delta)` es `handleAll(List.of(new StockAdjustment(id, delta)))`.
Vive en un adapter propio (`StockAdjustmentResource`) porque la operación es
sobre el inventario, no sobre un producto, y así no depende del desempate de
JAX-RS entre el segmento literal y la plantilla `{id}`.

**Cómo se garantiza "todos o ninguno".** `AdjustStockUseCase.handleAll` trabaja
en tres fases: (1) construir el lote —`StockAdjustmentBatch` rechaza lote vacío,
elemento nulo y producto repetido—, (2) cargar los productos —el primero que
falte lanza `ProductNotFoundException`—, (3) calcular con
`Product.adjustStock`, que es **puro** —delta cero y stock insuficiente rechazan
aquí— y solo entonces (4) escribir `update` + `save` de cada movimiento. Las
tres primeras fases no escriben: cuando el lote se rechaza, `update` y `save`
**no se han llamado ni una vez**, así que la garantía no depende del rollback. El
`@Transactional` de D-022 sigue siendo la red para el fallo que ocurra ya
escribiendo (el diente:
`si_falla_el_movimiento_del_segundo_producto_ninguno_cambia_su_stock`).
**Por qué importa el orden:** la implementación ingenua —un solo recorrido que
busca, ajusta y escribe— pasa el camino feliz y **falla el issue en silencio**;
se implementó primero a propósito y el rojo lo dejó por escrito (`update`
invocado desde `lambda$handleAll$2` cuando el test lo prohibía).

**Códigos (fail-fast, en orden de petición):** 400 lote vacío / producto
repetido / ajuste sin producto / delta cero / desbordamiento; 404 producto
inexistente o eliminado (D-024); 409 stock resultante negativo. Se descartó el
reporte agregado de todos los problemas: exigiría un schema de error nuevo y
decidir qué código gana al mezclar 404 y 409, y el issue pide rechazo total, no
un informe. Todos los movimientos del lote comparten el `occurredAt`: el reloj
se lee **una vez** por operación (el desempate por `id desc` del historial ya
existía, D-023).

**Las reglas del lote viven en el constructor compacto de
`StockAdjustmentBatch`, no en su factory.** Lo forzó SpotBugs (`EI_EXPOSE_REP2`,
HARNESS I): con la validación y la copia solo en `of`, el constructor canónico
del record —público por serlo un record— seguía admitiendo un lote inválido o
atado a una lista mutable ajena. Con las reglas en el constructor, el estado
inválido es irrepresentable por cualquier vía. Misma razón en
`BulkStockAdjustmentRequest`, que además normaliza los huecos del cliente
(cuerpo sin `adjustments` → lote vacío; elemento nulo → ajuste sin producto) para
que los rechace **el dominio** con 400: antes, `{"adjustments":[null]}` reventaba
en `NullPointerException` → 500. Se usa `List.copyOf` porque es la copia que
SpotBugs reconoce en el accessor de un record.

Sin migración Flyway: el lote no añade tablas ni columnas. Sigue vigente D-021
(read-modify-write sin bloqueo), ahora sobre N productos; el issue deja fuera
idempotencia, ejecución parcial y concurrencia. Ver `specs/github-32/plan.md`.

## 2026-08-18 — Evidencia estructurada del mutation testing (github-34)

### D-030 — Una corrida de `mutation` deja evidencia auditable, igual que un `verify`
`harness mutation` ejecutaba PIT y dejaba el resultado en `target/pit-reports`:
después no se podía responder qué código exacto se analizó, si el árbol estaba
limpio, sobre qué commit, cuándo, si PIT terminó bien ni dónde quedaron los
reportes — justo lo que `verify` sí contesta desde D-026. Ahora `mutation`
captura la identidad del código **antes** de lanzar Maven (el mismo
`source.state`) y deja en
`artifacts/harness/<ts>-<sha>[-dirty-<state7>]-mutation/`: `command.log` con la
salida completa de Maven/PIT, `pit-reports/` con el reporte copiado, el
manifiesto `source-state.txt` y el documento `mutation.json`, que registra
schema, comando, componente, resultado, exit code, inicio, fin, duración, git,
identidad del source y las referencias a esa evidencia. El exit code de PIT se
sigue propagando tal cual.

**Por qué la identidad se captura al principio:** PIT escribe durante la
corrida, y un `state` calculado al final describiría un árbol que ya incluye lo
que generó la propia herramienta. Capturarlo antes es lo que hace que la
evidencia diga *qué se analizó* y no *qué quedó después*. La suite lo prueba
con un Maven de mentira que crea un archivo **no ignorado** mientras corre:
si el cálculo se moviera al final, el caso se pone rojo.

**Por qué un documento propio (`mutation.json`, `schemaVersion 1.0`) y no
`verification.json`:** el tipo de evidencia se reconoce por el nombre del
archivo, sin leer un campo, y un glob sobre `artifacts/harness/*` no mezcla dos
tipos. Nace en `1.0` —y no en el `1.1` de `verify`— porque acoplar las dos
numeraciones obligaría a tocar un documento cada vez que evolucione el otro.
**Por qué el sufijo `-mutation` y no un subárbol `artifacts/harness/mutation/`:**
el timestamp va delante, así que toda la evidencia del harness sigue ordenada
cronológicamente en un solo sitio y el nombre dice qué comando la produjo; el
bloque `-dirty-<state7>` conserva el significado de D-026.
**Por qué `result: COMPLETED/FAILED`:** es exactamente lo que imprime el banner
del comando; usar `PASSED` habría creado dos vocabularios para un mismo
resultado. **Por qué también hay evidencia cuando falla:** el fallo de PIT (no
alcanzar el threshold) y el fallo previo a Maven (backend o wrapper ausentes,
exit 2) son justo las corridas que hay que poder auditar; una corrida que falló
no puede quedar sin rastro.

**Lo que NO cambia:** nada de PIT (goals, thresholds, paquetes analizados,
operadores), `mutation` sigue sin ejecutarse en cada PR, y el bloque
`environment` sigue siendo el mínimo de D-026 (CI y run id) — la identidad
completa del entorno de ejecución (JDK, Maven, SO, versión de PIT) es otro work
item, no se inventa aquí.

**Cómo se prueba:** las garantías viven en `tests/harness/contract_test.sh`,
**una sola suite** que en CI corre contra `./harness` en `ubuntu-latest` y
contra `harness.cmd` en `windows-latest` (D-028), así que "bash y Windows
producen evidencia equivalente" es una aserción ejecutada, no una promesa. La
paridad **estática** compara además, sin ejecutar nada, el conjunto y el orden
de los campos del documento que escribe cada script: añadir un campo en una
implementación y no en la otra falla en local, sin esperar a Windows. Como los
self-tests del harness no tienen mutation testing, los casos nuevos se
comprobaron con una batería de mutantes a mano sobre los dos scripts.

**El reporte adjunto es el de esta corrida, no el que quedó en `target/`:**
`mutation` no ejecuta `clean`, así que copiar `target/pit-reports` "si existe"
adjuntaba el reporte de la corrida anterior cuando esta fallaba antes de que
PIT escribiera (p. ej. en `test-compile`) — evidencia con el `source.state` del
código B junto al reporte del código A, el mismo pecado que este trabajo vino a
corregir. Ahora el directorio se **descarta antes de lanzar Maven**: lo que
quede después es de esta corrida por construcción, sin heurísticas (descartadas
comparar marcas de tiempo, que no prueban autoría, y deducirlo del texto que
imprime PIT). Y `evidence.pitReports` vale `null` cuando no hubo reporte:
prometer un directorio que no existe es afirmar una evidencia que nadie
produjo. Nada de PIT cambia; `verify` no tenía el problema porque ejecuta
`clean`. El descarte va **antes de cualquier validación**, no solo antes de
Maven: todos los caminos del comando —incluido el que sale con 2 sin llegar a
ejecutarlo— terminan en el mismo cierre, que copia lo que haya en `target/`, así
que una limpieza colocada dentro de una rama deja el resto de caminos
descubiertos. La garantía tiene que vivir antes de la bifurcación, no dentro de
una rama. Lo destapó la revisión del PR, no el CI: el caso solo aparece cuando
la corrida falla **antes** de PIT, que es justo el camino que la suite no
recorría.

**Lo que demostró ejecutar de verdad, otra vez:** el primer push dejó rojos los
dos jobs de contrato con el mismo fallo, y no era del harness sino de la suite:
un helper nuevo capturaba el stdout de `run_harness`, que con
`HARNESS_TEST_VERBOSE=1` —lo que usan los dos jobs— lleva el volcado de la
corrida entera, así que comparaba el dump en vez del identificador. En local,
sin esa variable, todo estaba verde. Ahora el volcado va a **stderr**, de forma
que ningún helper pueda arrastrarlo dentro de un valor, y el helper deja su
resultado en una variable en vez de en stdout (lo que además evita que la
subshell se trague sus aserciones). Regla que queda: **una suite que solo se
ejecuta en un modo no está probada en el otro**; el contrato se corre en local
con y sin `HARNESS_TEST_VERBOSE=1`.

Refactor incluido: `verify` y `mutation` comparten en bash el arranque de la
corrida (`start_run_evidence`, `print_run_header`) y en cmd la resolución del
entorno (`:resolve_environment`), para que la evidencia de los dos comandos no
pueda divergir por descuido. Ver `specs/github-34/plan.md`.
