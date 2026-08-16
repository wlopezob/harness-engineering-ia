# github-14 — Lifecycle de work item externo + Plan-First

## Work item

* Fuente: GitHub Issue
  [#14](https://github.com/wlopezob/harness-engineering-ia/issues/14)
* Título: *Add external work-item planning lifecycle to engineering harness*

El work item externo es la fuente de verdad del requerimiento (QUÉ). Este
documento describe CÓMO se implementa (D2/D5). No duplica el issue: para el
requerimiento, leer el issue.

> **Nota de trazabilidad:** el lifecycle Plan-First se diseñó *durante* la
> ejecución de #14, por lo que este `plan.md` se escribió después de que las
> disciplinas ya estuvieran redactadas. Es el primer work item que persiste su
> plan; los siguientes aplican el proceso desde el inicio.

## Entendimiento técnico (estado del working copy)

* `orders-platform/CLAUDE.md` — disciplinas raíz. Antes de #14 contenía 4
  disciplinas (TDD, Verify, Artifact Store, Plan-First) sin ninguna noción de
  requerimiento externo.
* `orders-platform/apps/api/CLAUDE.md` — harnesses **A–I** (letras). No colisiona
  con la numeración `D<n>` de la capa raíz; las reglas se fusionan.
* `harness` / `harness.cmd` — CLI con tres comandos reales: `verify`, `format`,
  `mutation`. Cero conocimiento de proveedores de work items.
* `orders-platform/specs/` — hasta ahora solo `inventory/plan.md`, con features
  nombradas por dominio, sin ID trazable a una fuente externa.
* `DECISIONS.md` — D-001..D-016, todas de dominio/infra; ninguna sobre el proceso
  de ingeniería. Su cabecera citaba la numeración de disciplinas anterior.

## Cambios propuestos

1. **Disciplinas raíz** (`orders-platform/CLAUDE.md`) — reescribir el lifecycle
   completo:

   ```
   D1 External Work Item → D2 Plan-First → D3 TDD → D4 Verify
      → D5 Artifact Store → D6 Contract-First
   ```

   * **D1** define que el requerimiento vive fuera del harness y que leerlo es
     una capacidad del **agente** (MCP, skills, connectors), no del harness.
   * **D2** añade *context gathering* sobre el working copy actual como fuente
     primaria del estado técnico, y convierte el plan en un **gate** con
     aprobación explícita del usuario.
   * **D5** fija la separación de artefactos: work item = QUÉ, `plan.md` = CÓMO,
     `DECISIONS.md` = por qué, `contracts/` = frontera, `artifacts/harness/` =
     evidencia, chat = temporal.

2. **`specs/<work-item>/plan.md` con ID trazable** — cuando exista work item
   externo, `<feature>` usa un identificador rastreable (`github-14`, `SUP-123`,
   `ado-45821`). Este archivo es el primer ejemplo.

3. **`DECISIONS.md`** — registrar la decisión de proceso (D-017) y corregir la
   referencia stale a la numeración anterior de disciplinas.

## Fuera de alcance (explícito)

No se implementa, ni ahora ni como extensión futura del harness:

* cliente de GitHub API, Jira o Azure DevOps;
* `gh issue view` u otro comando de proveedor dentro del CLI;
* flag `--source github`;
* sincronización automática de work items;
* dependencia de un LLM o agente concreto.

Motivo: el harness impone restricciones y produce evidencia. Obtener el
requerimiento es una capacidad del agente. Mezclarlos ataría el harness a un
proveedor y a un modelo.

## Impacto

| Capa                | Impacto |
| ------------------- | ------- |
| Domain              | no      |
| Application         | no      |
| Infrastructure      | no      |
| Persistence         | no      |
| OpenAPI / contratos | no      |
| Harness CLI         | no      |
| Disciplinas agente  | **sí**  |
| Proceso ingeniería  | **sí**  |

## Estrategia de implementación

El cambio es de **disciplinas y artefactos**, no de código de producción. No hay
comportamiento ejecutable nuevo, por lo que no aplica el ciclo RED → GREEN de D3:
no existe test que pueda fallar primero sin inventar producción artificial.

D3 sigue vigente para todo cambio posterior de código; este work item no lo
suspende ni lo debilita.

La verificación aquí es doble:

1. **Revisión de las disciplinas** — D1–D6 presentes, numeradas sin huecos, sin
   contradecirse entre sí ni con los harnesses A–I del backend.
2. **Ausencia de acoplamiento a proveedores** — el harness no menciona GitHub,
   Jira, Azure DevOps ni un LLM concreto.

## Verificación

Comprobación de que no se introdujo integración específica de proveedor:

```bash
grep -rniE "gh issue|github api|jira|azure devops|--source" \
  harness harness.cmd .github/workflows README.md
```

Resultado esperado: **sin coincidencias**.

Comprobación de que el cambio no afecta al proyecto existente:

```bash
./harness verify
```

Resultado esperado: `HARNESS RESULT: PASSED`, con evidencia en
`artifacts/harness/<timestamp>-<sha>/`.

## Open questions

Ninguna abierta. Las resueltas durante #14:

* *¿El harness debe leer el work item?* → No. Es capacidad del agente (D1).
* *¿Todo cambio necesita `plan.md`?* → No. Un cambio trivial puede prescindir de
  él; lo requieren las tareas con más de un comportamiento o con decisiones
  técnicas (D2).
* *¿`plan.md` reemplaza a Jira/GitHub Issues?* → No. Describe diseño y estrategia
  técnica, no gestiona el trabajo (D5).
