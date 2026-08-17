# orders-platform — Disciplinas compartidas (capa raíz)

Aplican a CUALQUIER agente en este repo. Cada subcarpeta (`apps/api`, luego
`apps/web`) añade SU propio `CLAUDE.md`; las reglas se fusionan.

## Mapa del repo

* `apps/api`    → backend Quarkus (monolito modular, hexagonal)
* `apps/web`    → front React+Tailwind (luego)
* `contracts/`  → `openapi.yaml` — LA frontera front↔api (el contrato es la verdad)
* `specs/`      → SDD: spec/plan/tasks por feature

---

## D1 — External Work Item (el requerimiento vive fuera del harness)

El trabajo puede originarse en una fuente externa como GitHub Issues, Jira,
Azure DevOps u otra plataforma de planificación.

El work item describe QUÉ se necesita.

No se exige que tenga una estructura perfecta, criterios de aceptación
completos ni detalles técnicos suficientes para implementar directamente.

Cuando un agente recibe un work item:

1. Lee el work item usando las herramientas disponibles para el agente
   (MCP, skills, connectors u otras).

2. Conserva el work item externo como fuente de verdad del requerimiento.

3. No copia ni reescribe innecesariamente todo el requerimiento dentro del
   repositorio.

4. No inventa requisitos faltantes.

5. Si falta información relevante del requerimiento, declara assumptions u
   open questions y las valida con el usuario cuando sean necesarias para
   continuar.

El Engineering Harness NO depende de GitHub, Jira, Azure DevOps ni de un LLM
específico.

La integración con fuentes externas pertenece a las capacidades del agente.

Separación de responsabilidades:

* Work item externo → QUÉ necesita el negocio o proyecto.
* LLM/agente → interpreta el requerimiento y ayuda a construir el plan técnico.
* Engineering Harness → impone restricciones, ejecuta verificaciones y genera
  evidencia.

---

## D2 — Plan-First (entender antes de codear)

Después de entender el work item, el agente debe comprender el estado técnico
actual antes de proponer una implementación.

### Working branch

Cuando el work item requiera cambios versionados, el agente trabaja en una
rama dedicada antes de modificar los artefactos del repositorio.

Para crear y nombrar la rama utiliza el siguiente skill:

`git-branching`

La rama se crea localmente a partir de una base actualizada.

Crear la rama NO implica publicarla.

No se ejecuta `git push` durante esta etapa.

### Context gathering

Antes de proponer el plan, el agente inspecciona el working copy actual sobre
el que se realizará la implementación.

La fuente primaria para entender el estado técnico del código es el current
working tree disponible para el agente.

Debe revisar, cuando aplique:

* rama actual;
* cambios locales existentes;
* código relacionado con el requerimiento;
* tests existentes;
* `CLAUDE.md` aplicables;
* `DECISIONS.md`;
* `specs/`;
* contratos;
* configuración;
* arquitectura relevante;
* patrones existentes que deban conservarse.

### Engineering Plan

Después de entender el requerimiento y el estado técnico actual, el agente
propone el plan de implementación, lo construye junto con el usuario y espera su aprobacion.

No empieza a modificar producción inmediatamente.

Tarea con más de un comportamiento → primero `specs/<feature>/plan.md`.

El plan es un GATE antes de implementar.

Si durante el desarrollo la implementación se desvía del plan, el plan debe
actualizarse.

Cuando exista un work item externo, `<feature>` puede utilizar un identificador
trazable como:

* `github-14`
* `SUP-123`
* `ado-45821`

El `plan.md` describe CÓMO se propone implementar técnicamente el requerimiento.

No reemplaza ni duplica innecesariamente el work item externo.

El plan puede contener, según sea necesario:

* referencia al work item;
* entendimiento técnico;
* assumptions;
* open questions;
* cambios propuestos;
* componentes afectados;
* impacto en dominio;
* impacto en aplicación;
* impacto en infraestructura;
* impacto en persistencia;
* impacto en contratos;
* casos de test en orden;
* estrategia RED → GREEN → triangulate → refactor;
* comandos de verificación.

No todos los apartados son obligatorios por estructura.

El plan debe ser suficientemente detallado para que otro agente pueda
implementar el cambio sin tener que redescubrir las decisiones técnicas
principales.

Debe identificar, cuando aplique:

- archivos y componentes a crear o modificar;
- responsabilidades de cada componente;
- firmas o interfaces relevantes;
- flujo entre capas;
- reglas y algoritmos importantes;
- impacto de contrato y persistencia;
- casos de test y su orden.

Puede utilizar pseudocódigo o fragmentos breves cuando ayuden a eliminar
ambigüedad.

El plan no debe duplicar la implementación final ni contener código completo
que corresponda vivir en producción.

Si existe una duda que puede cambiar significativamente el comportamiento o la
arquitectura, el agente no debe inventar la respuesta: debe declararla y
resolverla antes de implementar esa parte.

---

## D3 — TDD estricto (red → green → triangulate → refactor)

Prohibido producción sin un test que falle primero.

Muestra la salida de CADA corrida en orden, empezando por el PRIMER rojo.

Si un test pasa a la primera sin verlo rojo, sospecha y dale dientes
(mutation test).

El ciclo esperado es:

1. **RED**
   Escribir el siguiente test de comportamiento y verlo fallar por la razón
   esperada.

2. **GREEN**
   Implementar únicamente lo necesario para hacerlo pasar.

3. **TRIANGULATE**
   Agregar casos que eliminen implementaciones accidentales o demasiado
   específicas cuando corresponda.

4. **REFACTOR**
   Mejorar el diseño sin cambiar comportamiento y manteniendo los tests verdes.

El plan técnico define el orden esperado de comportamientos.

TDD gobierna cómo se implementa cada comportamiento.

No escribir múltiples comportamientos de producción de una sola vez si pueden
desarrollarse incrementalmente mediante ciclos RED → GREEN.

---

## D4 — Verify (sin evidencia, no existe)

"Terminé" ≠ "verificado".

Toda afirmación de que algo funciona lleva la salida REAL del comando
(test/build/request).

El agente debe ejecutar las verificaciones reales aplicables al cambio.

Comando principal:

```bash id="e9k4cp"
./harness verify
```

Cuando cambie lógica cubierta por mutation testing o el plan lo requiera:

```bash id="h4x2pn"
./harness mutation
```

Una afirmación de éxito sin ejecutar la verificación correspondiente no cuenta
como evidencia.

Si una verificación falla, el trabajo continúa.

El agente debe analizar el fallo, corregir la causa y volver a ejecutar la
verificación.

No se deben:

* reducir umbrales;
* eliminar tests;
* debilitar reglas;
* desactivar análisis;
* agregar exclusiones injustificadas;

únicamente para obtener un resultado verde.

Las excepciones o falsos positivos deben tener una justificación técnica
explícita y quedar registrados cuando corresponda.

### Delivery approval gate

Después de que las verificaciones aplicables estén en verde, el agente realiza
un self-review del trabajo antes de publicarlo.

Debe revisar, cuando aplique:

- cambios realizados;
- archivos modificados;
- cumplimiento del plan aprobado;
- resultados de las verificaciones;
- desviaciones del plan;
- riesgos o pendientes conocidos.

Luego presenta el resultado al usuario y espera confirmación explícita.

Hasta recibir esa confirmación está prohibido ejecutar:

- `git push`;
- creación de Pull Request;
- cualquier otra operación que publique el trabajo remoto.

Después de la aprobación:

1. Crear los commits necesarios utilizando el skill:
   `git-commit`

2. Publicar la rama.

3. Crear el Pull Request utilizando el skill:
   `git-pr`

El Pull Request debe mantener trazabilidad con el work item cuando exista.

---

## D5 — Artifact Store (el chat NO es la fuente de verdad)

Decisiones → `DECISIONS.md` o `specs/`.

Lo que no queda en archivo, no pasó.

Separación de responsabilidades:

* Work item externo → QUÉ necesita el negocio o proyecto.
* `specs/<feature>/plan.md` → CÓMO se propone implementar el cambio.
* `DECISIONS.md` → decisiones técnicas o arquitectónicas relevantes y su
  justificación, contiene al "<feature>" que genero el registro de una decision.
* `contracts/` → contratos entre componentes.
* `artifacts/harness/` → evidencia generada por las verificaciones.
* Chat → medio temporal de análisis y colaboración, nunca fuente definitiva.

Si durante la implementación cambia una decisión que afecta el plan, actualiza
el artefacto correspondiente.

Si aparece una decisión arquitectónica relevante que debe sobrevivir a la tarea
actual, regístrala en `DECISIONS.md`.

El contexto necesario para continuar el trabajo debe sobrevivir a la sesión
actual.

El estado del trabajo debe poder entenderse leyendo los artefactos del
repositorio, sin depender del historial de conversaciones previas.

---

## D6 — Contract-First (el contrato es la frontera)

Front y api se comunican SOLO por `contracts/openapi.yaml`.

El contrato es la fuente de verdad de la frontera entre componentes.

Ningún cambio de API que rompa el contrato puede realizarse sin actualizar el
contrato correspondiente.

Antes de implementar un cambio que pueda afectar la superficie de la API, el
agente debe determinar explícitamente si existe impacto de contrato.

Si cambia la superficie pública de la API:

* código y contrato cambian juntos;
* ambos forman parte de la misma implementación;
* ambos deben verificarse antes de considerar terminado el cambio.

No se debe adaptar silenciosamente un consumidor a un comportamiento que no
esté representado en el contrato.

Las reglas técnicas específicas de fidelidad, generación y validación OpenAPI
viven en el `CLAUDE.md` del backend y se fusionan con estas disciplinas raíz.
