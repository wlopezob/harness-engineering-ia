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
