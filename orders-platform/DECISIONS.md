# DECISIONS

Registro de decisiones. El chat no es la fuente de verdad (D3).

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

### D-010 — ArchUnit gobierna solo producción (DoNotIncludeTests)
`@AnalyzeClasses` excluye las clases de test (`importOptions = DoNotIncludeTests`).
Las reglas de arquitectura aplican al código de producción, no a los tests.
**Por qué:** la regla `el_nucleo_es_inmutable` (HARNESS C) cazaba campos `@Mock`
(HARNESS D), que no pueden ser `final`. Choque C×D resuelto de raíz: las reglas no
deben policiar tests. Libera el `@Mock` en campos idiomático.