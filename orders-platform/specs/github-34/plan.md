# github-34 — `harness mutation` produce evidencia estructurada

## Work item

* Fuente: GitHub Issue
  [#34](https://github.com/wlopezob/harness-engineering-ia/issues/34)
* Título: *Produce structured evidence for mutation testing*

El issue guarda el QUÉ. Este documento guarda el CÓMO. Continúa #26 (que
introdujo `source.state` y la evidencia de `verify`), #28 (el gate
`Harness self-test`) y #30 (la suite de contrato que corre contra las dos
implementaciones).

## Entendimiento técnico (estado del working copy)

Punto de partida: `main` @ `dd7ba01` (incluye #32).

### Qué hace hoy cada comando

| Aspecto | `verify` | `mutation` |
| ------- | -------- | ---------- |
| Identidad del código | `compute_source_state` **antes** de Maven | no la calcula |
| Directorio de evidencia | `artifacts/harness/<ts>-<sha>[-dirty-<state7>]` | ninguno |
| Log de Maven | `tee command.log` (bash) / redirección + `type` (cmd) | va solo a consola, no se conserva |
| Reportes | copia `surefire`, `failsafe`, `jacoco`, `quarkus.log` y `pit-reports` a `test-reports/` | quedan solo en `target/pit-reports` |
| Manifiesto | `source-state.txt` | — |
| JSON | `verification.json` (`schemaVersion 1.1`) | — |
| Exit code | propagado, registrado en el JSON | propagado, sin registrar |
| Banner | `HARNESS RESULT: PASSED/FAILED` + evidencia | `MUTATION RESULT: COMPLETED/FAILED` + ruta del reporte |

Es decir: `mutation` ya hace bien lo único que el issue no pide cambiar
(ejecutar PIT y propagar su exit code) y no hace nada de lo que el issue pide.

### Qué hay para probar

* `tests/harness/contract_test.sh` (14 casos) corre **la misma suite** contra
  `./harness` (`HARNESS_IMPL=bash`, `ubuntu-latest` y local) y contra
  `harness.cmd` (`HARNESS_IMPL=cmd`, Git Bash en `windows-latest`). Ya tiene el
  repo temporal, los wrappers de Maven de mentira (`mvnw`/`mvnw.cmd`,
  `HARNESS_TEST_MVNW_EXIT`, `HARNESS_TEST_MVNW_SLEEP`), `json_query`,
  `assert_no_interpreter_errors` y tres casos de `mutation` (goals, exit code,
  wrapper ausente). **Es el sitio natural de las nuevas garantías**: escritas
  una vez, se ejecutan contra las dos plataformas.
* `tests/harness/parity_test.sh` (5 casos) compara los dos scripts **sin
  ejecutarlos**: despacho, help, argumentos de Maven, wrapper por plataforma.
* `tests/harness/state_test.sh` (16) y `selftest_gate_test.sh` (10) no tocan
  `mutation`.
* El workflow `harness-selftest.yml` ya tiene los seis jobs y el gate:
  **no hace falta añadir ningún job**, así que el `needs` del gate y el ruleset
  de `main` no cambian.
* No hay Windows en la máquina de desarrollo: el lado `harness.cmd` solo se ve
  correr en CI (igual que en #26 y #30). Por eso las garantías van en la suite
  de contrato y no en una suite bash aparte.

## Decisiones validadas con el usuario

### 1. Mismo árbol de evidencia, sufijo `-mutation` en el nombre del directorio

```
artifacts/harness/20260818T021000Z-64824a7-mutation/
artifacts/harness/20260818T022500Z-64824a7-dirty-9ab12cd-mutation/
```

Toda la evidencia del harness queda en un solo sitio y ordenada
cronológicamente (el timestamp va delante); el sufijo dice qué comando la
produjo. El bloque `-dirty-<state7>` mantiene el significado de #26: un
directorio sin ese bloque describe un árbol limpio. Descartado un subárbol
`artifacts/harness/mutation/`: rompe el orden cronológico único y obliga a
mirar en dos sitios para reconstruir qué pasó.

### 2. `mutation.json` con `schemaVersion 1.0`

Documento propio, con su propia evolución: el tipo se reconoce por el nombre
del archivo, sin leer un campo. Descartado reutilizar `verification.json` (un
glob de evidencia mezclaría dos tipos) y descartado nacer en `1.1` (acoplaría
las dos evoluciones: subir el esquema de uno obligaría a mirar el otro).

### 3. `result`: `COMPLETED` / `FAILED`

Es exactamente lo que ya imprime el banner de `mutation` en las dos
plataformas. La evidencia dice lo mismo que vio quien lo ejecutó; `PASSED`
habría creado dos vocabularios para un mismo resultado.

### 4. Los fallos previos a Maven también dejan evidencia

Backend ausente o Maven Wrapper ausente → `exitCode 2`, `result FAILED`, el
error en `command.log` y el JSON escrito, igual que hace `verify`. Una corrida
que falló nunca queda sin rastro.

### 5. Solo se adjunta el reporte que produjo esta corrida

`target/pit-reports` se borra **antes** de lanzar Maven; después, lo que haya
es de esta corrida por construcción, sin heurísticas. Descartadas: comparar la
marca de tiempo del directorio antes y después (que algo se modifique no prueba
que lo produjera esta corrida, y comparar fechas en batch depende del locale) y
deducir del log si PIT llegó a ejecutarse (acopla el harness al texto que
imprime PIT). Coste aceptado: un reporte anterior que siguiera en `target/`
desaparece; si lo produjo el harness, su copia vive en la evidencia de aquella
corrida.

### 6. `evidence.pitReports` es `null` cuando no hubo reporte

Prometer un directorio que no existe es afirmar una evidencia que nadie
produjo. El campo se mantiene siempre presente (esquema estable, y es lo que
compara la paridad estática entre las dos implementaciones) con valor
`"pit-reports"` o `null`. Descartadas: omitir la clave (el conjunto de claves
dejaría de ser fijo) y la cadena vacía (una ruta vacía parece un bug, no una
afirmación deliberada).

## Contrato del documento

`mutation.json` (bash; en cmd cambia solo `command`, como ya ocurre con
`verification.json`):

```json
{
  "schemaVersion": "1.0",
  "command": "./harness mutation",
  "component": "orders-platform/apps/api",
  "result": "COMPLETED",
  "exitCode": 0,
  "startedAt": "2026-08-18T02:10:00Z",
  "finishedAt": "2026-08-18T02:11:34Z",
  "durationSeconds": 94,
  "git": { "commit": "<sha completo>", "branch": "<rama>" },
  "source": {
    "dirty": false,
    "state": "<id del árbol>",
    "stateAlgorithm": "<el mismo de #26>",
    "scope": "repo:tracked+untracked-not-ignored",
    "changedFiles": 0,
    "manifest": "source-state.txt"
  },
  "environment": { "ci": "false", "githubRunId": "local", "githubRunAttempt": "local" },
  "evidence": {
    "commandLog": "command.log",
    "pitReports": "pit-reports",
    "sourceManifest": "source-state.txt"
  }
}
```

`pitReports` vale `null` cuando la corrida no produjo reporte (falló antes de
que PIT escribiera); nunca apunta al reporte de una corrida anterior.

Cubre los mínimos del issue: schema, comando, componente, resultado, exit code,
inicio, fin, duración, git, identidad del source y referencias a la evidencia.
`environment` se mantiene igual que en `verify` (CI y run id) — **no** es la
"identidad completa del entorno de ejecución" que el issue deja fuera de
alcance (JDK, Maven, SO, versión de PIT): eso queda pendiente para otro work
item.

Contenido del directorio:

```
<ts>-<sha>[-dirty-<state7>]-mutation/
  command.log       salida completa de Maven/PIT (éxito y fallo)
  mutation.json     el documento de arriba
  pit-reports/      copia de target/pit-reports (HTML + XML)
  source-state.txt  manifiesto que respalda source.state
```

## Cambios propuestos

### `harness` (bash)

* `mutation_backend()` pasa a tener la misma forma que `verify_backend()`:
  captura de instante/git → `compute_source_state` → directorio de evidencia →
  cabecera → validaciones (exit 2 con log) → Maven con `tee` y `PIPESTATUS` →
  cierre (duración, resultado, copia de reportes, JSON, banner) → `return` con
  el exit code de Maven.
* `copy_pit_reports <evidencia>`: copia `target/pit-reports` a `pit-reports/`
  si existe (PIT también escribe reporte cuando no alcanza el threshold).
* `write_mutation_report <evidencia> …`: escribe `mutation.json`.
* Refactor (con la suite en verde): extraer de `verify_backend` lo que ahora
  comparten los dos comandos — instante/commit/rama, nombre del directorio de
  evidencia y volcado del manifiesto (`start_run_evidence <sufijo>`), y las
  líneas de cabecera (`print_run_header <título>`). Si el resultado no queda
  más legible que la duplicación, se deja duplicado y se anota aquí.
* El banner de `mutation` añade la línea `Evidence: <dir>` (y mantiene
  `Report: …/pit-reports/index.html`).

### `harness.cmd`

* La etiqueta `:mutation` gana el mismo flujo, idiomático de cmd:
  `call :get_timestamp` → git → `call :compute_source_state` → `EVIDENCE_DIR`
  con sufijo `-mutation` → cabecera → validaciones con `goto :mutation_finalize`
  → `call mvnw.cmd … > "%COMMAND_LOG%" 2>&1` + `type` → `:mutation_finalize`
  (`call :copy_pit_reports`, `call :write_mutation_json`, banner,
  `exit /b %EXIT_CODE%`).
* Reglas heredadas de #30 que aplican aquí: aritmética de `set /a` entre
  comillas, nada parseado de `%TIME%` (la duración sale del epoch de
  `:get_timestamp`), `exit /b` con código en nivel superior y ejecutables con
  homónimo GNU por ruta absoluta.

### `tests/harness/`

* `testlib.sh`: `evidence_dir_of <repo> [kind]` acepta `mutation` (directorios
  que terminan en `-mutation`) o `verify` (los que no); sin argumento se
  comporta como hoy.
* `contract_test.sh`: las nuevas garantías (una sola vez, dos plataformas). El
  wrapper de mentira gana `HARNESS_TEST_MVNW_TOUCH=<ruta relativa>`, que crea
  un archivo **no ignorado** durante la corrida: es lo que permite probar que
  lo generado por PIT no reescribe el `source.state` declarado.
  `assert_verification_json` se generaliza a `assert_run_json <evidencia>
  <archivo> <exit esperado>`.
* `parity_test.sh`: un caso estático que compara el **conjunto de claves** del
  JSON de mutation que escribe cada script (más `schemaVersion` y las
  referencias de `evidence`), para que añadir un campo en una implementación y
  no en la otra falle sin esperar a Windows.

### Documentación

* `DECISIONS.md`: **D-030** — qué identifica la evidencia de mutation, por qué
  el sufijo, por qué documento propio, por qué el estado se captura antes y qué
  queda fuera.
* Este `plan.md` con su sección de resultado.
* El `help` de los dos scripts describe `mutation` mencionando la evidencia
  (texto idéntico en ambos: lo exige `parity_test.sh`).

## Impacto

| Capa | Impacto |
| ---- | ------- |
| Dominio / aplicación / infraestructura / persistencia | ninguno: no se toca `apps/api` |
| Contrato (`openapi.yaml`) | ninguno: no cambia la superficie de la API |
| Harness (`harness`, `harness.cmd`) | `mutation` genera evidencia; `verify` sin cambio de comportamiento |
| Self-tests | `contract_test.sh` y `parity_test.sh` crecen; `state_test.sh` y `selftest_gate_test.sh` intactos |
| CI | ningún job nuevo; el gate y el ruleset de `main` no cambian |
| `artifacts/` | un directorio más por corrida de mutation (ignorado por git) |

## Casos de test en orden (D3 — RED → GREEN → triangulate → refactor)

Los reds 1–7 se ven en local con `HARNESS_IMPL=bash`; el mismo archivo los
ejecuta contra `harness.cmd` en `windows-latest`. El red 8 es estático y es el
que obliga a implementar el lado cmd sin esperar a CI.

| # | Suite | Caso | Rojo esperado |
| - | ----- | ---- | ------------- |
| 1 | contract | `mutation` deja un directorio de evidencia con `mutation.json` parseable (`schemaVersion 1.0`, `command`, `component`) | no existe ningún directorio |
| 2 | contract | el JSON registra `result COMPLETED`, `exitCode 0`, `startedAt`/`finishedAt` y `durationSeconds` numérico que **mide** (Maven duerme 2 s) | sin JSON / duración 0 |
| 3 | contract | conserva `command.log` con la salida de Maven y copia `target/pit-reports` a `pit-reports/` | no se conserva nada |
| 4 | contract | PIT falla (exit 3) → `result FAILED`, `exitCode 3`, evidencia igualmente escrita y exit code propagado | no hay evidencia del fallo |
| 5 | contract | la evidencia describe el código **anterior** a PIT: con `HARNESS_TEST_MVNW_TOUCH`, `source.state` del JSON == `state` de antes de correr, y `source-state.txt` lo respalda | el state se calcularía después y cambiaría |
| 6 | contract | sin Maven Wrapper → exit 2, `result FAILED`, `exitCode 2` y el error en `command.log` | hoy sale por stderr sin evidencia |
| 7 | contract | `verify` y `mutation` en el mismo repo dejan **dos** directorios distinguibles, cada uno con su JSON; con árbol sucio el de mutation es `…-dirty-<state7>-mutation` y `source.dirty` es `true` | se pisarían / no habría sufijo |
| 8 | parity | los dos scripts escriben el mismo documento: mismas claves, mismo `schemaVersion`, mismas referencias de `evidence` | `harness.cmd` no escribe ninguno |
| 9 | contract | con un reporte de una corrida anterior en `target/` y una corrida que falla antes de que PIT escriba, la evidencia no contiene ese reporte ni lo promete en el documento (añadido en la revisión del PR) | se copiaba el reporte viejo y `pitReports` decía `"pit-reports"` |

Triangulación prevista: el caso 4 elimina la implementación que solo funciona
en el camino feliz; el 5, la que calcula el estado al final; el 7, la que
reutiliza el nombre de directorio de `verify`.

Como los self-tests del harness no tienen PIT, a los casos que pasen a la
primera se les dan dientes con una **batería de mutantes a mano** sobre
`harness` y `harness.cmd` (mismo método que #30: parchear una línea, exigir que
la suite falle, restaurar en `finally`).

## Verificación (D4)

```bash
tests/harness/contract_test.sh                    # HARNESS_IMPL=bash (por defecto)
tests/harness/parity_test.sh
tests/harness/state_test.sh                       # sin regresión
tests/harness/selftest_gate_test.sh               # sin regresión
python3 <batería de mutantes>                     # dientes de los tests nuevos
./harness mutation                                # corrida real: PIT sobre apps/api
./harness verify                                  # sin regresión de la evidencia existente
```

Evidencia a mostrar: el `mutation.json` real de la corrida, el árbol del
directorio de evidencia, y en CI el job `Command contract (cmd)` de
`windows-latest`, que es la única ejecución real de `harness.cmd`.

## Assumptions

* `mutation` sigue **sin** `clean` (no se toca nada de PIT), pero el harness
  **descarta `target/pit-reports` antes de lanzar Maven**. La versión inicial
  de este plan asumía que copiar el directorio después de la corrida bastaba;
  **es falso**: si la corrida falla antes de que PIT escriba —por ejemplo en
  `test-compile`—, ahí sigue el reporte de la corrida anterior, y la evidencia
  quedaría con `source.state` del código B junto al `pit-reports/` del código
  A. Descartándolo antes, lo que quede después es de esta corrida por
  construcción.
* `verify` no corre ese riesgo: ejecuta `clean`, así que `target/` empieza
  vacío en cada corrida.
* Se copia `target/pit-reports` completo (HTML + XML). Está en `.gitignore`
  vía `artifacts/`, así que solo ocupa disco local.
* Nada de PIT cambia: goals, thresholds, paquetes analizados y operadores son
  los mismos (fuera de alcance del issue).
* `mutation` sigue sin ejecutarse en cada PR (fuera de alcance).

## Open questions

Ninguna abierta: las cuatro decisiones que podían cambiar el comportamiento
(ubicación, documento, vocabulario de `result`, evidencia en fallo previo) se
validaron antes de implementar.

## Resultado

### Ciclo TDD (rojos observados, en orden)

| # | Rojo real | Green |
| - | --------- | ----- |
| 1 | `FAIL: mutation debe dejar un directorio de evidencia (vacío)` | directorio `<ts>-<sha>-mutation` + `mutation.json` con schema/comando/componente |
| 2 | `durationSeconds numérico (esperado: 'number', obtenido: 'null')` ×9 | instantes, duración medida, git y `result` en el documento |
| 3 | `la evidencia debe conservar el log de Maven/PIT` + `debe copiar target/pit-reports` | `tee command.log`, `copy_pit_reports`, referencias en `evidence` |
| 4 | `la evidencia debe incluir mutation.json` con PIT saliendo 3 | un solo cierre para éxito y fallo (antes el fallo salía antes de escribir nada) |
| 5 | `la evidencia debe declarar el código de antes de correr PIT (esperado '66f71cf…', obtenido 'null')` | `compute_source_state` antes de Maven + manifiesto copiado |
| 6 | `el motivo del fallo debe quedar en command.log` (sin Maven Wrapper) | validaciones previas escriben log y documento con `exitCode 2` |
| 7 | `el nombre del directorio no puede parecer un análisis del commit limpio (no contiene '-dirty-c68ec17-mutation')` | nombre con `-dirty-<state7>` también en mutation |
| 8 | `harness.cmd debe escribir el documento de mutation (vacío)` | `:mutation` con evidencia, `:copy_pit_reports`, `:write_mutation_json` |

Dos rojos se endurecieron **antes** de implementar, porque tal como estaban
escritos habrían pasado con el campo ausente:

* `jq` imprime `null` para un campo que no existe y `assert_not_empty` lo daba
  por bueno → nuevo `assert_json_value`, que rechaza vacío y `null`. El rojo 2
  pasó de 5 a 9 aserciones fallidas.
* el wrapper de Maven de mentira no imprimía nada, así que "conserva el log"
  se habría cumplido con un `command.log` vacío → ahora imprime un marcador y
  el test exige que el log **contenga la salida de Maven**.

Refactor con la suite en verde: `verify` y `mutation` compartían ~40 líneas de
arranque de corrida → `start_run_evidence <sufijo>` y `print_run_header
<título>` en bash, `:resolve_environment` en cmd. Mismas 53 pruebas verdes
antes y después.

### Mutantes (batería a mano, sin PIT para los scripts)

| Mutante | Resultado |
| ------- | --------- |
| sin sufijo `-mutation` en el directorio | muerto (contract) |
| el manifiesto no se conserva | muerto (contract, state) |
| la identidad se calcula DESPUÉS de Maven | muerto (contract) |
| `mutation` siempre dice `COMPLETED` | muerto (contract) |
| el log de PIT no se conserva | muerto (contract) |
| los reportes de PIT no se copian | muerto (contract) |
| duración siempre 0 | muerto (contract) |
| el árbol sucio no se ve en el nombre | muerto (contract, state) |
| el fallo previo a Maven no deja documento | muerto (contract) |
| cmd olvida un campo del documento | muerto (parity) |
| cmd declara otro `schemaVersion` | muerto (parity) |
| **cmd escribe el documento en `verification.json`** | **VIVO en la primera pasada** |

El superviviente era un hueco real: la paridad estática comparaba el
**contenido** del documento pero no **a qué archivo** lo escribía cada script,
así que `harness.cmd` podría haber dejado la evidencia de mutation haciéndose
pasar por la de un `verify`, y solo lo habría cazado el job de Windows. Se
añadió la aserción del nombre del archivo en las dos implementaciones y el
mutante muere en ambos lados: 12/12.

### Verificación local (D4)

```
tests/harness/state_test.sh          16 passed, 0 failed
tests/harness/contract_test.sh       21 passed, 0 failed   (HARNESS_IMPL=bash)
tests/harness/parity_test.sh          6 passed, 0 failed
tests/harness/selftest_gate_test.sh  10 passed, 0 failed
```

Corrida real de `./harness mutation` sobre el backend:

```
>> Generated 47 mutations Killed 44 (94%)
>> Line Coverage (for mutated classes only): 118/120 (98%)
[INFO] BUILD SUCCESS
 MUTATION RESULT: COMPLETED
 Evidence: artifacts/harness/20260818T113755Z-dd7ba01-dirty-2753cde-mutation
```

`./harness verify` sin regresión (la evidencia existente sigue igual, con el
refactor por debajo):

```
[INFO] Tests run: 90, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
 HARNESS RESULT: PASSED
 Evidence: artifacts/harness/20260818T113858Z-dd7ba01-dirty-2753cde
verification.json → schemaVersion 1.1, result PASSED, exitCode 0, durationSeconds 18
```

La evidencia de mutation contiene `command.log` (169 líneas, la salida completa de PIT),
`pit-reports/` (con `index.html`, `mutations.xml` y las páginas por paquete),
`source-state.txt` (138 líneas) y `mutation.json`. El manifiesto conservado
**reproduce** el identificador declarado:

```
declarado:   2753cde7d80e619d58563b47b0c8c2339a0a0906
recomputado: 2753cde7d80e619d58563b47b0c8c2339a0a0906   (git hash-object --stdin < source-state.txt)
```

### Segunda vuelta: el hueco de auditabilidad (revisión del PR)

La revisión encontró que `mutation` copiaba `target/pit-reports` siempre que
existiera, sin garantizar que fuera de esta corrida. Rojo antes de tocar
producción, con reporte viejo marcado y una corrida que falla sin generar nada
(nuevo `HARNESS_TEST_MVNW_NO_REPORT` en los dos wrappers de mentira):

```
- test_mutation_no_adjunta_el_reporte_de_una_corrida_anterior
  FAIL: la evidencia no puede adjuntar el reporte de una corrida anterior
  FAIL: mutation.json no puede prometer reportes que esta corrida no produjo
```

Green: `discard_stale_pit_reports` antes de Maven en bash y `rmdir /S /Q` en
cmd; `copy_pit_reports` devuelve la referencia solo si copió algo, y el
documento escribe `null` cuando no la hay.

La paridad estática **seguía verde** con `harness.cmd` afirmando el reporte a
pelo, así que se le añadió el diente que faltaba: la referencia tiene que salir
de una variable calculada en las dos implementaciones, y las dos tienen que
descartar el reporte previo. Batería de esta vuelta: 5/5 mutantes muertos
(bash sin descarte, bash afirmando siempre, bash copiando lo que no es de la
corrida, cmd sin descarte, cmd afirmando siempre) — los dos de cmd mueren en
local, sin esperar a Windows.

### Lo que encontró el CI (y no se veía en local)

El primer push dejó **rojos los dos jobs de contrato**, bash y cmd, con la
misma aserción:

```
FAIL: la evidencia debe declarar el código de antes de correr PIT
      (esperado: '    $ harness.cmd state  (rc=0)
```

El "esperado" traía dentro el volcado de la corrida. El helper `state_of`
capturaba el stdout de `run_harness`, y los dos jobs corren con
`HARNESS_TEST_VERBOSE=1` —la variable que vuelca cada corrida para poder
depurar Windows—, así que el valor comparado era el dump entero en vez del
identificador. En local, sin la variable, la suite estaba verde: un falso
verde de manual, y esta vez del lado de la herramienta de test.

Se arregló en dos capas, porque el helper era solo la mitad: `state_of` deja el
valor en `STATE_OF` en lugar de en stdout (lo que además evita que la subshell
se trague los `fail` que emite `run_harness`), y **el volcado verbose se manda
a stderr**, de modo que ningún helper pueda volver a arrastrarlo dentro de un
valor. La clase de fallo desaparece en vez de parchearse en un punto.

Regla que queda: **una suite que solo se ejecuta en un modo no está probada en
el otro** — el contrato se corre en local con y sin `HARNESS_TEST_VERBOSE=1`.

## Desviaciones respecto al plan

* **El `help` no se tocó.** El plan preveía mencionar la evidencia en la
  descripción de `mutation`; `verify` genera evidencia desde #26 y su línea
  tampoco la menciona, así que hacerlo solo en `mutation` habría quedado
  incoherente. Cada comando ya imprime `Evidence:` al ejecutarse.
* **Herramienta de test más allá de lo planeado.** Además de generalizar
  `assert_verification_json` a `assert_run_json`, hizo falta `assert_json_value`
  (un campo ausente no puede pasar por presente), enseñar rutas anidadas
  (`.git.commit`, `.source.state`) al fallback de `python3` —no se puede dar por
  hecho `jq` en el runner de Windows— y que `json_query` no ensucie el log con
  el error de redirección cuando el documento no existe.
* **El refactor alcanzó a `harness.cmd`.** El plan solo proponía extraer en
  bash; la resolución del entorno (`CI`, `githubRunId`, `githubRunAttempt`) se
  extrajo también en cmd a `:resolve_environment`, compartida por `verify` y
  `mutation`, para que no pueda divergir entre comandos.
* **Un caso de test más de los previstos.** El mutante superviviente obligó a
  añadir la comparación del nombre del archivo del documento, que el plan no
  contemplaba.
