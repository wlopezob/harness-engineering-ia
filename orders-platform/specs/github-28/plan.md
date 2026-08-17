# github-28 — El Harness Self-Test bloquea el merge a `main`

## Work item

* Fuente: GitHub Issue
  [#28](https://github.com/wlopezob/harness-engineering-ia/issues/28)
* Título: *Make Harness Self-Test a required merge gate*

El issue guarda el QUÉ. Este documento guarda el CÓMO. Continúa el trabajo de
`specs/github-26/plan.md`, que estrenó el workflow `Harness Self-Test`; aquí
no se añade ninguna verificación nueva al harness (fuera de alcance del issue):
se convierte lo que ya existe en un gate exigible.

## Entendimiento técnico (estado del working copy)

Punto de partida: `main` @ `547bcb4` (incluye ya el trabajo de #26).

* `.github/workflows/harness-selftest.yml` tiene **cuatro jobs** con nombres
  visibles distintos del id:

  | id | `name:` (= contexto del check en GitHub) | depende de |
  | -- | ---------------------------------------- | ---------- |
  | `self-test` | `Source state self-test` | — |
  | `state-linux` | `Source state (bash)` | — |
  | `state-windows` | `Source state (cmd)` | — |
  | `parity` | `bash and cmd agree on the state` | `state-linux`, `state-windows` |

  Última corrida en `main` (run `32077221802`): los cuatro en verde; el más
  lento es el de Windows (~20 s), el resto ~5 s.
* El **ruleset `main`** (id `18865557`, `enforcement: active`,
  `bypass_actors: []`) exige hoy dos checks, ambos de GitHub Actions
  (`integration_id 15368`): `Maven verify` y `Dependency review`, con
  `strict_required_status_checks_policy: true` (la rama debe estar al día) y
  `do_not_enforce_on_create: true`. **Ninguno de los cuatro jobs del self-test
  es obligatorio**: un PR que rompa `./harness state` o la paridad bash/cmd
  hoy se puede mergear.
* Los required checks de GitHub se identifican **por nombre de check** (el
  `name:` del job). Exigir los cuatro uno a uno funciona, pero acopla el
  ruleset a nombres descriptivos que cambian, y **añadir un quinto job al
  workflow no lo haría obligatorio** hasta que alguien toque el ruleset.
* Dos hechos de GitHub Actions que condicionan el diseño (documentación
  oficial, verificada):
  1. *"A job that is skipped will report its status as Success. It will not
     prevent a pull request from merging, even if it is a required check."*
     Es decir: si `state-windows` falla, `parity` **se omite**, y un check
     omitido **no bloquea**. El gate tiene que tratar `skipped` como fallo.
  2. `always()` *"returns true, even when canceled"*; `!cancelled()` es la
     alternativa recomendada para jobs que puedan colgarse. Con
     `!cancelled()` el gate no correría cuando la corrida se cancela y su
     check quedaría **sin resultado o `skipped`** — precisamente lo que el
     issue pide que cuente como fallo.
* El workflow tiene `concurrency: cancel-in-progress: true`: cada push a un PR
  cancela la corrida anterior. Es normal y no afecta al gate: el check de la
  corrida cancelada queda en rojo **en el commit viejo**, y el nuevo commit
  trae su propia corrida.
* `tests/harness/state_test.sh` (16 casos) tiene su propio runner
  (`run_test`, `assert_*`, resumen `SELF-TEST RESULT`) embebido en el archivo.
  No hay una librería compartida entre suites porque hasta hoy solo había una.
* `jq` está disponible en `ubuntu-latest` y en la máquina de desarrollo
  (`jq-1.7.1`).

## Decisiones propuestas (a validar con el usuario)

### 1. Un job agregador `gate` que depende de **todos** los jobs y siempre corre

```yaml
gate:
  name: Harness self-test            # el contexto estable que exige el ruleset
  needs: [self-test, state-linux, state-windows, parity]
  if: always()                       # ver decisión 2
  runs-on: ubuntu-latest
  timeout-minutes: 2
  steps: … falla salvo que TODOS los needs.*.result sean "success"
```

`needs.<job>.result` toma los valores `success | failure | cancelled |
skipped`. El gate exige `success` en **cada** entrada de `needs` y falla ante
cualquier otro valor: eso cubre literalmente los tres casos del issue
(*falla, se cancela o se omite*). Que la lista de `needs` sea todos los jobs
es lo que hace que "todas las verificaciones necesarias" no dependa de la
memoria de nadie (ver decisión 4).

**Nombre del check:** `Harness self-test`. Sigue el patrón de los dos checks
ya obligatorios — workflow *Backend Verify* → check `Maven verify`, workflow
*Dependency Review* → check `Dependency review` — y no colisiona con ningún
job existente (`Source state self-test` es otro).

*Alternativa descartada:* exigir los cuatro jobs uno a uno en el ruleset. No
resiste renombrar un job ni añadir uno nuevo, y multiplica por cuatro las
entradas del ruleset (y las de Windows son las más lentas de reportar).

### 2. `if: always()` a propósito, con `timeout-minutes: 2` como red

La documentación recomienda `!cancelled()` para evitar que un job "siempre"
se ejecute tras una cancelación y se cuelgue. Aquí el gate **debe** correr
tras una cancelación para dejar el check en rojo (criterio: *si cualquiera se
cancela… el gate final debe fallar*), y no puede colgarse: no hace checkout
de nada pesado ni llama a la red — evalúa un JSON que ya está en el runner.
El `timeout-minutes: 2` es la red por si algún día alguien le añade pasos.

### 3. La lógica del gate vive en un script versionado, no inline en el YAML

`.github/scripts/needs_all_succeeded.sh`: lee `NEEDS_JSON` (el
`${{ toJSON(needs) }}` del job), imprime una tabla `job → result` y termina
con `exit 1` si el objeto está vacío o si algún `result != "success"`. ~30
líneas de bash + `jq`.

**Por qué no inline:** un `run:` dentro del YAML **no se puede ejecutar en
local**, y D3 exige ver el rojo antes de escribir producción. Con el script
fuera, la suite le da de comer JSON fabricado (`failure`, `cancelled`,
`skipped`, vacío) y comprueba el código de salida sin necesitar GitHub. El
coste es un `actions/checkout` en el gate (~3 s, con `sparse-checkout` para
traer solo el script).

*Alternativa:* `run:` inline con `jq -e 'all(.[]; .result == "success")'`
y solo tests estructurales sobre el YAML. Más corto, pero el comportamiento
que más importa (skipped/cancelled ⇒ rojo) quedaría sin diente local.

### 4. Dientes en `tests/harness/selftest_gate_test.sh` (suite nueva)

Dos familias de casos:

* **Comportamiento** del script con JSON fabricado (ver "Casos de test").
* **Estructura** del workflow, leyendo `harness-selftest.yml` con `awk`/`sed`
  (sin parser YAML: el archivo lo escribimos nosotros con formato fijo):
  existe el job `gate` con `name: Harness self-test`, tiene `if: always()`,
  y su `needs` contiene **exactamente** todos los demás ids de job del
  workflow. Este último es el que impide que un job nuevo se quede fuera del
  gate sin que nadie lo note.

El job `self-test` del workflow ejecuta la suite nueva además de la actual.
Para no duplicar el runner, las funciones compartidas de `state_test.sh`
(`fail`, `skip`, `assert_*`, `run_test`, resumen) se extraen a
`tests/harness/testlib.sh` y ambas suites lo cargan con `source`. Es un
REFACTOR sin cambio de comportamiento: la suite de state debe seguir dando
`16 passed, 0 failed, 0 skipped` antes de escribir la primera línea nueva.

### 5. El ruleset se actualiza con `gh api` **después** de que el gate haya
corrido en verde en el PR, y **antes** del merge

Un check solo puede exigirse con sentido cuando existe. Como el workflow de
un `pull_request` se toma de la rama del PR, el propio PR de este cambio ya
produce el check `Harness self-test`. Secuencia:

1. push + PR → el gate corre y queda verde (o rojo, y se arregla);
2. `PUT /repos/{owner}/{repo}/rulesets/18865557` con el JSON actual **más**
   `{ "context": "Harness self-test", "integration_id": 15368 }` en
   `required_status_checks` — todo lo demás (deletion, non_fast_forward,
   pull_request, `strict`, `do_not_enforce_on_create`, bypass vacío) se
   conserva byte a byte;
3. `gh pr checks` en el propio PR muestra el nuevo check como *required*;
4. merge — el primero que pasa por el gate.

Es una operación sobre el remoto: **solo tras la aprobación de entrega (D4)**.
No hay otros PRs abiertos que puedan quedar bloqueados por el cambio. El
ruleset resultante se registra en `DECISIONS.md` (lista de checks
obligatorios) para que sobreviva a la sesión (D5): la configuración de
GitHub no está versionada en el repo, y la decisión sí debe estarlo.

## Cambios propuestos

```
.github/workflows/harness-selftest.yml   + job `gate` (Harness self-test); el job
                                           self-test ejecuta la suite nueva
.github/scripts/needs_all_succeeded.sh   NUEVO — lógica del gate, ejecutable en local
tests/harness/testlib.sh                 NUEVO — runner extraído de state_test.sh
tests/harness/state_test.sh              REFACTOR — usa testlib.sh (16 casos intactos)
tests/harness/selftest_gate_test.sh      NUEVO — dientes del gate (script + estructura)
orders-platform/DECISIONS.md             + D-027
orders-platform/specs/github-28/plan.md  este documento
Ruleset `main` (GitHub, no versionado)   + required check "Harness self-test"
```

Sin tocar: `harness`, `harness.cmd`, `backend-verify.yml`,
`dependency-review.yml`, reglas de JaCoCo/SpotBugs/PIT, backend, contratos.

Pseudocódigo del script:

```bash
needs_all_succeeded() {              # NEEDS_JSON = ${{ toJSON(needs) }}
  jobs = jq 'to_entries'  NEEDS_JSON        # [] si el objeto está vacío
  imprimir "job  result" por cada entrada
  si jobs está vacío            → "ERROR: el gate no depende de ningún job" ; exit 1
  si alguna .result != success  → "ERROR: <job> terminó en <result>"     ; exit 1
  "OK: N job(s) en success"     ; exit 0
}
```

## Impacto

| Componente | Impacto |
| ---------- | ------- |
| CI (`harness-selftest.yml`) | **sí** — un job nuevo; los cuatro existentes no cambian |
| `tests/` | **sí** — segunda suite + runner compartido |
| Ruleset de `main` | **sí** — un required check más; nada se quita |
| `harness` / `harness.cmd` | no |
| Backend / contratos / JaCoCo / SpotBugs / PIT / Dependency Review | no |

## Casos de test en orden (D3 — RED → GREEN → refactor)

Paso 0 (REFACTOR previo, sin rojo): extraer `testlib.sh` y comprobar que
`state_test.sh` sigue en `16 passed, 0 failed, 0 skipped`.

Sobre `tests/harness/selftest_gate_test.sh`, contra el script:

1. Todos los jobs en `success` → exit 0. ← **primer RED** (el script no existe).
2. Un job en `failure` → exit 1 y el mensaje nombra el job.
3. Un job en `cancelled` → exit 1.
4. Un job en `skipped` → exit 1 (el caso que GitHub trataría como éxito).
5. `NEEDS_JSON` vacío (`{}`) → exit 1 (un gate sin dependencias es un gate
   mal cableado, no un gate que pasa).
6. `NEEDS_JSON` ausente o no es JSON → exit 1 (nunca "pasar por defecto").

Contra el workflow:

7. Existe un job cuyo `name:` es exactamente `Harness self-test`.
   ← RED hasta escribir el job.
8. Ese job tiene `if: always()`.
9. Su `needs` contiene **todos** los demás ids de job del workflow, sin
   faltar ninguno (se compara la lista extraída del YAML con la de `needs`).
10. Ese job ejecuta `.github/scripts/needs_all_succeeded.sh`.

## Verificación (D4)

```bash
tests/harness/state_test.sh          # 16 passed, sin cambios
tests/harness/selftest_gate_test.sh  # la suite nueva
./harness verify                     # el flujo actual sigue compatible
```

Y la verificación que solo GitHub puede dar, tras el push (con aprobación):

```bash
gh run view <id> --json jobs         # los 5 jobs, gate en success
gh pr checks <n>                     # "Harness self-test" listado
gh api repos/{owner}/{repo}/rulesets/18865557 --jq \
  '.rules[] | select(.type=="required_status_checks") | .parameters.required_status_checks[].context'
# → Maven verify, Dependency review, Harness self-test
```

`./harness mutation` no aplica: no se toca código Java.

## Assumptions

* Los "checks actualmente obligatorios" del issue son los dos del ruleset
  activo (`Maven verify`, `Dependency review`); los checks de CodeQL
  (`Analyze (…)`) no son obligatorios hoy y el issue no pide cambiarlo.
* `integration_id 15368` es GitHub Actions (es el que ya usan los dos checks
  obligatorios).
* El gate no necesita permisos: hereda `permissions: contents: read` del
  workflow, suficiente para el checkout del script.

## Open questions

Ninguna. Resueltas con el usuario antes de implementar (las tres con la opción
recomendada):

1. **Nombre del check:** `Harness self-test` (decisión 1).
2. **Script versionado + suite nueva** con runner compartido (decisiones 3 y 4).
3. **Ruleset:** en el propio PR, tras ver el gate en verde y antes del merge,
   siempre después de la aprobación de entrega (decisión 5).

## Resultado

### Ciclo TDD (rojos observados)

* **Paso 0 (refactor):** `testlib.sh` extraído; `state_test.sh` siguió en
  `16 passed, 0 failed, 0 skipped` antes de escribir nada nuevo.
* **Primer RED:** el script no existía (`rc 127`). GREEN con un `exit 0` de
  mentira, a propósito.
* **RED 2–4:** con ese `exit 0`, `failure`, `cancelled` y `skipped` pasaban.
  GREEN con la comparación `result != "success"`.
* **RED 5–6:** `{}` y `NEEDS_JSON` sin definir daban `rc 0` — **pasaban por
  defecto** — y un JSON roto salía con el `rc 5` de `jq` sin explicación.
  GREEN con las tres guardas explícitas (vacío, no-objeto, sin entradas).
* **RED 7–10:** el workflow no tenía el job. El extractor de ids ya veía los
  cuatro existentes (`parity self-test state-linux state-windows`), así que
  el `needs` esperado salió del propio YAML, no de una lista escrita a mano.
* Suite final: **10 passed, 0 failed, 0 skipped**.

### Mutaciones (con `assert old in s`, para que la mutación entre de verdad)

| Mutante | Resultado |
| ------- | --------- |
| quitar `parity` de `needs` | **muerto** |
| `!cancelled()` en vez de `always()` | **muerto** |
| renombrar el check (`Harness Self-Test`) | **muerto** (4 tests) |
| el gate no ejecuta el script | **muerto** |
| solo `failure` cuenta como rojo | **muerto** (2 tests: cancelled y skipped) |
| `failure` y `cancelled`, pero no `skipped` | **muerto** (el test de skipped, solo) |
| aceptar `needs` vacío | **muerto** |

Un hallazgo de la primera ronda: "solo `failure` cuenta" moría con **una sola**
aserción, cuando debía tumbar dos tests. El test de skipped llevaba también un
`failure` (pasaba por la razón equivocada) y `assert_contains "<job>"` era
débil porque la tabla imprime todos los jobs. Arreglado: el caso de skipped es
"todo verde salvo `parity=skipped`", y los tres tests exigen la línea
`ERROR: <job> (<result>)`. Repetida la ronda, cada mutante muere por el test
que le corresponde.

### Verificación local (D4)

* `tests/harness/state_test.sh` → `16 passed, 0 failed, 0 skipped` (sin cambios
  de comportamiento tras el refactor).
* `tests/harness/selftest_gate_test.sh` → `10 passed, 0 failed, 0 skipped`.
* `./harness verify` → `HARNESS RESULT: PASSED`, 65 tests. Evidencia:
  `artifacts/harness/20260817T225802Z-547bcb4-dirty-62dff1f/`.
* El YAML del workflow parsea (`ruby -ryaml`): jobs
  `[self-test, state-linux, state-windows, parity, gate]`, y
  `gate.needs = [self-test, state-linux, state-windows, parity]`.

### Verificación en GitHub (pendiente del push, tras aprobación)

Se completa en este mismo documento cuando el PR exista: corrida con los 5
jobs, `gh pr checks` con `Harness self-test` como required, y el ruleset con
los tres contextos.

## Desviaciones respecto al plan

1. El checkout del gate usa el modo cono por defecto de `sparse-checkout`
   (basta para traer un directorio); el plan no lo detallaba.
2. Ninguna otra hasta aquí: el nombre, el script, la suite, el runner
   compartido y el orden de los casos son los del plan.
