# github-21 — Ajustes de stock de productos

## Work item

* Fuente: GitHub Issue
  [#21](https://github.com/wlopezob/harness-engineering-ia/issues/21)
* Título: *Add stock adjustments to inventory products*

El issue guarda el QUÉ (criterios de aceptación y fuera de alcance). Este
documento guarda el CÓMO. Primer work item que aplica el lifecycle desde el
inicio (ver `specs/github-14/plan.md`).

## Entendimiento técnico (estado del working copy)

Punto de partida: `main` @ `6572a4f`, 34 tests verdes.

* `Product` es inmutable (HARNESS C): factories `create`/`restore` y `update`,
  que devuelve un **nuevo** Product. Ya existe `requireQuantity` (rechaza < 0).
* `ProductRepository` ya expone `findById` → `Optional<Product>` y
  `update(Product)`. **No hace falta ampliar el puerto.**
* `quantity` ya está persistido (`V1__create_product_table.sql`) y el adapter lo
  actualiza por dirty checking. **No hace falta migración nueva.**
* Patrón de errores establecido: `IllegalArgumentException`→400,
  `ProductNotFoundException`→404, `DuplicateSkuException`→409; cada uno con su
  `@Provider ... ExceptionMapper` y body `ApiError`.
* `UpdateProductUseCase` es el molde exacto del caso de uso a escribir:
  `findById().orElseThrow(...)` → método de dominio → `repository.update(...)`.
* SpotBugs: `EI_EXPOSE_REP2` ya está excluido para `application.usecase`
  ([D-016]), así que el caso de uso nuevo no rompe HARNESS I.

## Decisiones acordadas con el usuario

### 1. Un endpoint con delta firmado

```
POST /inventory/products/{id}/stock-adjustments
{ "delta":  5 }   → entrada de stock
{ "delta": -3 }   → salida de stock
```

Entrada y salida son el mismo concepto de negocio (un ajuste); el signo decide
la dirección. Una sola ruta nueva, un solo caso de uso, una sola forma de
request. Alternativas descartadas: dos rutas `increase`/`decrease` (duplica
superficie de contrato y tests para la misma regla) y `PATCH /stock`.

Respuesta: **200** con `ProductResponse` (el producto con la cantidad ya
ajustada), para que el cliente no tenga que hacer un GET extra.

### 2. Stock insuficiente → 409 Conflict

Nueva `InsufficientStockException` (dominio) + mapper a **409**, coherente con
`DuplicateSkuException`→409: es un conflicto con el estado actual del recurso,
no un request malformado. Descartados 422 (introduce un cuarto código sin
precedente en el repo) y 400 (mezcla "pediste mal" con "no hay stock").

### 3. Concurrencia: fuera de alcance, documentada

El flujo es read-modify-write: dos ajustes simultáneos sobre el mismo producto
pueden perder uno (last-write-wins). El issue no lo pide y auditoría/reservas
están fuera de alcance. Se registra como **riesgo conocido** en `DECISIONS.md`
en vez de resolverse en silencio. Si aparece la necesidad real: bloqueo
optimista (`@Version` + migración) o `UPDATE` relativo en el adapter.

### 4. Aritmética en `long` para no desbordar

`quantity + delta` se calcula en `long`. Con `int`, un `delta` cercano a
`Integer.MAX_VALUE` desborda y produce un resultado **negativo** que se
persistiría, violando el criterio de aceptación "no puede quedar en negativo".
Resultado fuera del rango de `int` → 400 (delta inválido).

## Contrato de la API (D6 / HARNESS B)

| Código | Cuándo | Body |
| ------ | ------ | ---- |
| 200 | ajuste aplicado | `ProductResponse` |
| 400 | `delta == 0`, o resultado fuera del rango de `int` | `ApiError` |
| 404 | no existe producto con ese id | `ApiError` |
| 409 | la salida dejaría el stock negativo | `ApiError` |

`delta == 0` se rechaza: es un ajuste sin efecto, casi siempre un error del
cliente; aceptarlo obligaría a devolver 200 sin haber ajustado nada.

Cambia la superficie de la API → `contracts/openapi.yaml` se regenera y se
commitea en **este mismo cambio**. Cada código va declarado con `@APIResponse`
(si no, `OpenApiFidelityTest` falla por `schema: {}`).

## Cambios propuestos

Nuevos:

```
domain/model/InsufficientStockException.java
application/usecase/AdjustStockUseCase.java
infrastructure/rest/StockAdjustmentRequest.java        record(int delta)
infrastructure/rest/InsufficientStockExceptionMapper.java   → 409
```

Modificados:

```
domain/model/Product.java          + adjustStock(int delta) → nuevo Product
infrastructure/rest/ProductResource.java   + POST /{id}/stock-adjustments
contracts/openapi.yaml             regenerado
DECISIONS.md                       D-019 (forma del endpoint + 409),
                                   D-020 (aritmética en long) y
                                   D-021 (riesgo de concurrencia aceptado)
```

Sin tocar: `ProductRepository` (puerto), `ProductEntity`,
`ProductRepositoryAdapter`, migraciones Flyway.

## Impacto

| Capa | Impacto |
| ---- | ------- |
| Domain | **sí** — `adjustStock` + `InsufficientStockException` |
| Application | **sí** — `AdjustStockUseCase` |
| Infrastructure (REST) | **sí** — endpoint + request + mapper |
| Persistence | no — se reutiliza `update` |
| OpenAPI | **sí** — regenerar |
| Harness CLI | no |

## Casos de test en orden (D3 — RED → GREEN → triangulate → refactor)

### A) Dominio — `ProductTest` (unit puro, sin Quarkus)

1. `adjustStock` con delta positivo aumenta la cantidad y conserva id, sku y
   nombre. ← **primer RED**
2. `adjustStock` con delta negativo disminuye la cantidad.
3. `adjustStock` rechaza delta 0 → `IllegalArgumentException`.
4. `adjustStock` rechaza que el resultado quede negativo →
   `InsufficientStockException`.
5. *(triangulación del límite)* dejar la cantidad **exactamente en 0** es
   válido — mata la implementación que use `<= 0` en vez de `< 0`.
6. *(triangulación de overflow)* delta que desborda `int` → 400 vía
   `IllegalArgumentException`, no una cantidad negativa persistida.
7. *(agregado durante la implementación)* llegar **exactamente** a
   `Integer.MAX_VALUE` es válido — mata el mutante `>` → `>=` que PIT reportó
   sobreviviente en `adjustStock`. Es el mismo límite que el caso 5, del otro
   lado del rango.

### B) Aplicación — `AdjustStockUseCaseTest` (unit, Mockito local, AAA)

8. `handle` ajusta el producto encontrado y lo persiste con `repository.update`.
9. `handle` lanza `ProductNotFoundException` si no existe y **nunca** llama a
   `update` (`verify(..., never())`, como en `UpdateProductUseCaseTest`).

### C) Infra REST — `ProductResourceTest` (`@QuarkusTest`, Postgres real)

10. delta positivo → 200 con la cantidad aumentada.
11. delta negativo → 200 con la cantidad disminuida.
12. salida mayor al stock → 409 **y la cantidad queda intacta** (se verifica con
    un GET posterior: el rechazo no debe persistir nada).
13. delta 0 → 400.
14. id inexistente → 404.

### D) Contrato

15. Regenerar `contracts/openapi.yaml`; `OpenApiContractTest` y
    `OpenApiFidelityTest` en verde.

Los 34 tests actuales son la red de seguridad: ninguno debe cambiar. Si alguno
se pone rojo, el cambio rompió comportamiento existente.

## Verificación (D4)

```bash
./harness verify
```

Esperado: `HARNESS RESULT: PASSED` con 34 + 14 tests, JaCoCo ≥ 80 % líneas y
ramas, Spotless y SpotBugs limpios. Evidencia en
`artifacts/harness/<timestamp>-<sha>/`.

Al tocar lógica de dominio con ramas nuevas:

```bash
./harness mutation
```

### Resultado

* `./harness verify` → **PASSED**, 48 tests, JaCoCo OK, SpotBugs 0 hallazgos.
  Evidencia: `artifacts/harness/20260817T100841Z-c74bb89/`.
* `./harness mutation` → 91 % de mutantes eliminados, test strength 97 %.
  Sobre el código de este cambio no sobrevive ningún mutante. Los 3 restantes
  son preexistentes: el límite de `Product.requireQuantity` (`< 0` vs `<= 0`) y
  los accessors sin usar de `ProductNotFoundException` y `DuplicateSkuException`.
  Se dejan como están: son deuda previa, ajena al alcance de este work item.

## Assumptions

* El ajuste no genera historial: el issue lo pone fuera de alcance, así que solo
  cambia `quantity` del producto. No se crea tabla de movimientos.
* No hay control de acceso por rol: el repo no tiene autenticación todavía.
* El delta llega en el body, no como query param, para no ensuciar la URL con
  signos negativos.

## Open questions

Ninguna bloqueante. Resueltas arriba: forma del endpoint, código de error de
stock insuficiente y tratamiento de la concurrencia.
