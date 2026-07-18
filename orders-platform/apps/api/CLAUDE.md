# apps/api — Harness del backend (Quarkus, hexagonal)

Hereda las disciplinas de la raíz (`../../CLAUDE.md`). Aquí, lo ESPECÍFICO del
backend. Regla madre: lo que se pueda, vive en CÓDIGO con dientes, no en prosa.

## HARNESS A — Arquitectura hexagonal (dientes: ArchUnit)
Paquetes obligatorios bajo `com.gentleman`:
- `domain/`         → model/ (POJOs + reglas) y port/ (interfaces). CERO framework.
- `application/`    → usecase/ (orquesta el dominio vía ports). Sin HTTP ni SQL.
- `infrastructure/` → rest/ (adapters HTTP) y persistence/ (adapters JPA).

La flecha de dependencia SIEMPRE apunta al dominio:
    infrastructure → application → domain     (nunca al revés)

NO es sugerencia: el test `ArchitectureTest` FALLA si
- una clase de `domain` importa Quarkus/Jakarta/Hibernate, o
- el dominio depende de application/infrastructure.

Mantener `ArchitectureTest` verde es obligatorio. Para romper un límite, primero
cambias la regla en el test (decisión en `DECISIONS.md`), nunca a escondidas.

## HARNESS B — Contract-first (dientes: OpenApiContractTest)
El contrato HTTP de esta API vive en `contracts/openapi.yaml` (raíz del repo) y es la
FUENTE DE VERDAD del límite con el front y otros consumidores. No es un archivo generado
y olvidado: es un artefacto versionado que se revisa en cada cambio.

Regla: NINGÚN cambio a la superficie de la API (rutas, métodos, request/response, códigos
de estado) sin **regenerar y commitear** `contracts/openapi.yaml` en el MISMO cambio. El
front depende del contrato, no de lo que el código emita hoy.

### Fidelidad (no solo anti-drift)
El contrato debe describir el comportamiento REAL, no solo el happy path:
- Cada endpoint declara TODOS sus códigos de respuesta (incluidos errores) y el
  tipo de body, vía tipo de retorno o `@APIResponse`.
- Prohibido devolver `Response` crudo sin `@APIResponse` que describa cada estado.
- Prohibido `schema: {}` en el contrato (body sin describir).

Dientes: el test `OpenApiContractTest` compara el OpenAPI que genera el código contra
`contracts/openapi.yaml` y FALLA si difieren. Si la desviación es intencional:
1. Regenera:  `curl -s localhost:8080/q/openapi -o ../../contracts/openapi.yaml`
2. Revisa el diff (es el cambio del límite con el front) y commitéalo.
Cambiar la superficie sin actualizar el contrato = build rojo.

## HARNESS C — Estilo funcional (el núcleo es puro)
Aplica a `domain` y `application` (el núcleo). `infrastructure` es el BORDE impuro
(JPA muta, el IO tiene efectos) y queda EXENTO.

- **Inmutabilidad:** los tipos del núcleo son `record` o clases con campos `final`
  sin setters. Estado nuevo = objeto nuevo, no mutación.
- **Sin null:** lo que puede faltar se modela con `Optional<T>` (puertos y retornos).
  Prohibido devolver `null`.
- **Funciones puras:** la lógica de dominio no tiene efectos secundarios (ni IO ni
  mutación externa); misma entrada → mismo resultado.
- **Transformaciones declarativas:** `map`/`filter`/`reduce` sobre Streams en vez de
  bucles que mutan acumuladores, donde aplique.
- **Efectos al borde:** toda mutación e IO vive en `infrastructure`. El núcleo no.

## HARNESS D — Dobles de test con Mockito
Para aislar colaboradores (los puertos) en tests, usa **Mockito**, no fakes a mano.
- Unit puro (sin Quarkus): `@ExtendWith(MockitoExtension.class)` + `@Mock` +
  `when(...).thenReturn(...)` / `verify(...)`.
- Dentro de `@QuarkusTest` (beans CDI): `@InjectMock`.
- Coherencia con HARNESS C: se mockea el BORDE impuro (el repositorio/puerto); la
  lógica pura del dominio se prueba directo, sin mocks.
Dependencia (test): `io.quarkus:quarkus-junit5-mockito`.
- **Estructura AAA:** cada test se ordena en Arrange / Act / Assert, con los tres
  bloques separados por una línea en blanco (opcional: comentarios `// arrange`,
  `// act`, `// assert`). Un solo Act por test.

## HARNESS E — Persistencia con Panache (repository pattern)
La persistencia vive en `infrastructure.persistence` y usa **Panache (repository
pattern)**: un `PanacheRepository<XEntity>` da el CRUD; el adapter implementa el
puerto del dominio y mapea `Entity ↔ POJO`.
- El resto del código habla con el **puerto POJO**, nunca con tipos Panache/JPA.
- La `@Entity` y `PanacheRepository` NO cruzan fuera de `infrastructure`.
- Evita `EntityManager`/JPQL a mano salvo lo que Panache no cubra (justificado en
  `DECISIONS.md`).

## HARNESS F — Formato determinista con Spotless

El formato del código no se decide manualmente ni durante la revisión.

Comandos oficiales:

```bash
./harness format
./harness verify

## HARNESS G — Cobertura verificable con JaCoCo

`./harness verify` genera y valida la cobertura de pruebas.

El reporte se genera en:

`target/jacoco-reports/index.html`

Umbrales mínimos vigentes:

- cobertura de líneas: 80 %
- cobertura de ramas: 80 %

Los umbrales son una línea base, no una meta. No deben reducirse, ni deben
agregarse exclusiones, únicamente para hacer pasar el build.

Si se necesita cambiar un umbral o excluir una clase, la justificación debe
registrarse en `DECISIONS.md`.

La cobertura no sustituye pruebas significativas. Los casos de dominio,
validaciones, errores y bifurcaciones deben probarse por comportamiento.
