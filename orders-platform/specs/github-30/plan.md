# github-30 — `./harness` y `harness.cmd` con el mismo contrato público

## Work item

* Fuente: GitHub Issue
  [#30](https://github.com/wlopezob/harness-engineering-ia/issues/30)
* Título: *Keep Bash and Windows harness commands behaviorally equivalent*

El issue guarda el QUÉ. Este documento guarda el CÓMO. Continúa #26 (que
demostró que revisar `harness.cmd` "por inspección" no basta) y #28 (el gate
`Harness self-test` que este trabajo debe poder poner en rojo).

## Entendimiento técnico (estado del working copy)

Punto de partida: `main` @ `1f09e4b` (incluye #28).

### Inventario de divergencias (leídos ambos scripts completos)

| Aspecto | `./harness` (bash) | `harness.cmd` | Paridad |
| ------- | ------------------ | ------------- | ------- |
| Comandos despachados | `verify format mutation state help/--help/-h` | `verify format state help/--help/-h` | **falta `mutation`** |
| `help` | lista los 5 comandos; `format` dice *"Corrige el formato"* (español, desalineado) | lista 4; `format` dice *"Apply the repository formatting rules."* | **textos distintos, falta `mutation`** |
| Comando desconocido | `ERROR: Unknown harness command: X` a **stderr**, línea en blanco, **usage completo**, exit 2 | mismo mensaje a **stdout**, `:help_error` imprime un usage **recortado** (solo `verify` y `help`), exit 2 | exit code igual; **salida distinta** |
| Mayúsculas | `VERIFY` es desconocido | `if /I` acepta `VERIFY` | **conjuntos distintos** |
| `mutation` | header, valida dir + `mvnw`, `mvnw test-compile org.pitest:pitest-maven:mutationCoverage`, banner `MUTATION RESULT: COMPLETED` + ruta del reporte | **no existe** | — |
| Fallo de Maven en `format`/`mutation` | `set -e` aborta con el exit code de Maven, **sin banner** | `format` imprime `FORMAT RESULT: FAILED` y sale con el exit code | exit code igual; **mensaje distinto** |
| Validaciones previas | `verify`/`mutation`: dir y wrapper (exit 2); `format`: solo wrapper | `verify`: dir y wrapper; `format`: solo wrapper | igual donde existe |
| Wrapper | `./mvnw` | `mvnw.cmd` | correcto por plataforma |
| Goals de Maven | `clean verify` / `spotless:apply` / `test-compile org.pitest:…` | `clean verify` / `spotless:apply` | igual donde existe |
| Evidencia de `verify` | copia también `target/pit-reports` | **no copia `pit-reports`** (hallazgo de #26) | **falta** |
| `state` | idéntico algoritmo; paridad **medida** en CI desde #26 | idem | ✓ |
| Comentario en `harness.cmd` | — | dice *"el orden lo fija git"*, obsoleto desde el sort global de #26 | doc |

Los mensajes de error `Backend directory not found` / `Maven Wrapper not
found`, los banners `HARNESS RESULT`, `FORMAT RESULT`, `MUTATION RESULT` y los
exit codes `0`/`2`/`<maven>` ya coinciden donde el comando existe.

### Qué hay para probar

* `tests/harness/state_test.sh` (16) prueba `state`/`verify` de **bash** con un
  `mvnw` de mentira; su `test_bash_y_cmd_declaran_la_misma_identidad` es una
  comprobación **de texto** sobre ambos scripts. `selftest_gate_test.sh` (10)
  exige que el `needs` del gate liste **todos** los jobs: cualquier job nuevo
  hay que añadirlo o el self-test falla.
* El workflow ya tiene un job `windows-latest` que ejecuta `harness.cmd state`
  de verdad. **`windows-latest` trae Git Bash** (`shell: bash`), así que la
  misma suite bash puede correr allí e invocar `cmd //c harness.cmd …`.
* No hay Windows ni `pwsh` en la máquina de desarrollo: lo que toque
  `harness.cmd` solo se ve correr en CI (como en #26).

## Decisiones propuestas (a validar con el usuario)

### 1. Contrato público explícito y **una sola suite de comportamiento** que se ejecuta contra las dos implementaciones

`tests/harness/contract_test.sh`, bash plano sobre `testlib.sh`, parametrizada
por `HARNESS_IMPL=bash|cmd`. Cada caso crea un repo git temporal con `harness`,
`harness.cmd` y **dos wrappers de mentira** (`mvnw` y `mvnw.cmd`) que
registran los argumentos recibidos en `mvnw-args.txt` y salen con
`HARNESS_TEST_MVNW_EXIT` (0 por defecto). Un helper `run_harness …` ejecuta
`./harness` o `cmd //c harness.cmd` según la plataforma; **las aserciones son
las mismas**. Es la definición operativa de "paridad": el mismo test, con las
mismas entradas, tiene que pasar en los dos.

En CI: job `contract-linux` (`ubuntu-latest`, `./harness`) y job
`contract-windows` (`windows-latest`, `shell: bash`, `harness.cmd`). Nombres:
`Command contract (bash)` / `Command contract (cmd)`, como los de `state`. Ambos
entran en el `needs` del gate, así que una regresión de paridad deja rojo
`Harness self-test` (criterio del issue).

*Alternativa descartada:* suite en PowerShell (`pwsh` existe en los tres
runners). No se puede ejecutar en la máquina de desarrollo y obligaría a
mantener un segundo runner; con Git Bash la suite bash cubre los dos.

### 2. Una suite **estática** de paridad que detecta comandos añadidos o quitados en una sola implementación

`tests/harness/parity_test.sh` lee los dos scripts (sin ejecutarlos) y compara:

* el **conjunto de comandos despachados** — arms del `case` en bash frente a
  las líneas `if "%~1"=="X" goto` en cmd — y que ambos incluyan como mínimo
  `verify format mutation state help` (más los alias `--help`/`-h`);
* que el **`help` de cada script liste exactamente su conjunto despachado**
  (nada oculto ni fantasma);
* el **texto completo del `help`**, igual en los dos módulo el nombre del
  programa (`./harness` ↔ `harness.cmd`, con los escapes `^` de batch
  quitados);
* la **invocación de Maven por comando** — mismos argumentos módulo el
  wrapper (`./mvnw` ↔ `mvnw.cmd`): `--batch-mode --no-transfer-progress
  clean verify`, `… spotless:apply`, `… test-compile
  org.pitest:pitest-maven:mutationCoverage`.

Corre en el job `self-test` (ubuntu, junto a las otras dos suites) y en local.
Es la parte del criterio "detectar si en el futuro se agrega o elimina un
comando en una implementación y no en la otra" que no depende de Windows; la
suite de contrato en `windows-latest` es la parte que **se ejecuta**.

### 3. `harness.cmd mutation`: mismo proceso lógico que bash

Nueva etiqueta `:mutation`: header, valida `API_DIR` y luego `mvnw.cmd`
(`exit /b 2` con los mismos mensajes), `pushd` + `call mvnw.cmd --batch-mode
--no-transfer-progress test-compile org.pitest:pitest-maven:mutationCoverage`,
y banner `MUTATION RESULT: COMPLETED` + `Report: …\target\pit-reports\index.html`
o `MUTATION RESULT: FAILED` + exit code de Maven. Se añade al despacho y al
`help`.

### 4. Alinear la semántica de fallo de `format` y `mutation` en bash con la de cmd

Hoy bash aborta por `set -e` con el exit code de Maven pero **sin banner**;
cmd imprime `FORMAT RESULT: FAILED` y sale con el mismo código. Se iguala
hacia el lado más informativo: bash captura el código, imprime `… RESULT:
FAILED` y sale con él. Exit codes iguales antes y después; solo se añade el
banner. Lo mismo para `mutation` en ambos.

### 5. Comando desconocido y `help`: misma salida en los dos

* cmd: el error va a **stderr** (`1>&2`), después el **usage completo** (se
  extrae `:print_help` como subrutina y desaparece `:help_error`), exit 2.
* Textos de `help` unificados en inglés y alineados; el de `format` pasa a
  *"Apply the repository formatting rules (spotless:apply)."* en los dos.
* **Mayúsculas:** se quita el `/I` de cmd para que el conjunto aceptado sea
  **exactamente** el mismo (`VERIFY` falla en los dos). Alternativa: dejar cmd
  case-insensitive como concesión a Windows; rompería el criterio "un comando
  desconocido debe fallar en ambas" para `VERIFY`.

### 6. Paridad de la evidencia de `verify`: cmd copia `pit-reports`

Divergencia registrada en #26 y de tres líneas; entra porque es paridad de
`verify`, no evidencia nueva de `mutation` (fuera de alcance: `mutation` sigue
sin generar evidencia estructurada en ninguna de las dos).

## Cambios propuestos

```
harness                                  format/mutation: banner FAILED + exit code;
                                         help alineado
harness.cmd                              + :mutation, :print_help, unknown → stderr +
                                         usage completo, sin /I, pit-reports en
                                         copy_reports, comentario obsoleto corregido
tests/harness/contract_test.sh           NUEVO — contrato de comportamiento (bash|cmd)
tests/harness/parity_test.sh             NUEVO — paridad estática de los dos scripts
.github/workflows/harness-selftest.yml   self-test ejecuta parity_test; + contract-linux
                                         y contract-windows; gate.needs += ambos
orders-platform/DECISIONS.md             + D-028
orders-platform/specs/github-30/plan.md  este documento
```

Sin tocar: reglas/umbrales de PIT, `backend-verify.yml`, algoritmo de
`source.state`, backend, contratos. `mutation` sigue sin correr en cada PR.

Pseudocódigo del helper central de la suite de contrato:

```bash
run_harness() {                       # deja HARNESS_RC y HARNESS_OUT
  case "${HARNESS_IMPL}" in
    bash) ( cd "$dir" && ./harness "$@" ) ;;
    cmd)  ( cd "$dir" && cmd //c harness.cmd "$@" ) ;;   # Git Bash en windows-latest
  esac 2>&1
}
stub mvnw / mvnw.cmd:  escribe "$*" en mvnw-args.txt; exit ${HARNESS_TEST_MVNW_EXIT:-0}
```

## Impacto

| Componente | Impacto |
| ---------- | ------- |
| `harness` (bash) | **sí** — banners de fallo, help; los goals de Maven no cambian |
| `harness.cmd` | **sí** — `mutation`, help, unknown, pit-reports |
| `tests/` | **sí** — dos suites nuevas (contrato + estática) |
| CI | **sí** — dos jobs nuevos + un paso; el gate los exige vía `needs` |
| Backend / PIT / contratos | no |

## Casos de test en orden (D3 — RED → GREEN → refactor)

**Suite estática** (`parity_test.sh`, corre en local; primeros rojos):

1. Ambos scripts despachan el mismo conjunto de comandos. ← **primer RED**
   (`mutation` falta en cmd).
2. El `help` de cada script lista exactamente su conjunto despachado (RED:
   cmd no lista `mutation`).
3. El texto del `help` es idéntico módulo el nombre del programa (RED: textos
   distintos).
4. Misma invocación de Maven por comando, módulo el wrapper (RED: cmd no
   tiene `mutation`).
5. Cada script usa el wrapper de su plataforma y no el de la otra.

**Suite de contrato** (`contract_test.sh`, en local contra bash; contra cmd
solo en CI):

6. `help` y sin argumentos → exit 0 y lista `verify format mutation state help`.
7. Comando desconocido → exit 2, `Unknown harness command: bogus` y el usage
   completo. `VERIFY` también es desconocido.
8. `mutation` → exit 0, el wrapper recibe `--batch-mode --no-transfer-progress
   test-compile org.pitest:pitest-maven:mutationCoverage`, banner
   `MUTATION RESULT: COMPLETED`.
9. `mutation` con Maven fallando (stub exit 3) → exit 3, `MUTATION RESULT:
   FAILED`, sin `COMPLETED`. ← RED en bash (hoy no hay banner).
10. `format` → exit 0, `spotless:apply`, `FORMAT RESULT: APPLIED`; con stub
    exit 4 → exit 4 y `FORMAT RESULT: FAILED`. ← RED en bash.
11. `mutation`/`format` sin wrapper → exit 2, `Maven Wrapper not found`.
12. `verify` → exit 0, `clean verify`, `HARNESS RESULT: PASSED`,
    `verification.json` con `"result": "PASSED"` y `test-reports/pit-reports`
    copiado si existe; con stub exit 1 → exit 1, `FAILED` en banner y JSON.
13. `state` → exit 0, JSON con `"dirty": false` y `state` no vacío;
    `state --manifest` sin ruta → exit 2.

Los rojos de la suite de contrato contra **cmd** solo se ven en
`windows-latest`: se documentan aquí cuando el CI los muestre (como en #26).

## Verificación (D4)

```bash
tests/harness/parity_test.sh                       # estática
HARNESS_IMPL=bash tests/harness/contract_test.sh   # contrato, lado bash
tests/harness/state_test.sh                        # 16, sin cambios
tests/harness/selftest_gate_test.sh                # 10, con el needs ampliado
./harness verify                                   # el flujo actual sigue compatible
```

En GitHub: `Command contract (cmd)` en verde en `windows-latest`, los 7 jobs
del workflow en success y el gate en verde. `./harness mutation` **no se
ejecuta aquí** (no cambia código Java ni la lógica de PIT); su paridad se
prueba con el wrapper de mentira, que es exactamente lo que el issue pide.

## Assumptions

* "Mismo conjunto de comandos públicos" = los que aparecen en `help`; los alias
  `--help`/`-h` se conservan en los dos.
* Los mensajes se comparan por **líneas principales** (banners `… RESULT: …`,
  `ERROR: …`, usage), no byte a byte: las rutas (`/` vs `\`) y el nombre del
  programa difieren por diseño.
* Git Bash está disponible en `windows-latest` (`shell: bash`) y `cmd //c`
  propaga el exit code de `harness.cmd`.
* Fuera de alcance mantenido: `mutation` no genera evidencia ni corre en cada
  PR; nada de PIT cambia.

## Open questions

Ninguna. Resueltas con el usuario antes de implementar (las cuatro con la
opción recomendada):

1. **Mayúsculas:** paridad estricta, se quita el `/I` (decisión 5).
2. **Banner `FAILED`** en bash para `format`/`mutation` (decisión 4).
3. **Tests:** suite bash de contrato en ambas plataformas + suite estática
   (decisiones 1 y 2).
4. **`pit-reports`** en la evidencia de `harness.cmd verify` (decisión 6).

## Resultado

### Ciclo TDD (rojos observados)

* **Suite estática:** primer RED = *"./harness y harness.cmd deben despachar
  exactamente los mismos comandos (esperado: `--help -h format help mutation
  state verify`, obtenido: `--help -h format help state verify`)"* —
  literalmente el ejemplo del issue. RED 2–3 con el `diff` del help (falta
  `mutation`, `format` en español, la descripción de `state` partida distinto).
  Los casos 4–5 (argumentos de Maven, wrapper por plataforma) pasaron a la
  primera porque `:mutation` ya se había escrito completo; se les dio dientes
  por mutación (abajo).
* **Suite de contrato contra bash:** RED en `mutation` y `format` con Maven
  fallando (*"no contiene 'MUTATION RESULT: FAILED'"*): bash abortaba por
  `set -e` sin banner. GREEN capturando el exit code y anunciando `FAILED`.
* **Suite del gate (#28):** al añadir `contract-linux` y `contract-windows` sin
  tocar `needs`, RED: *"el needs del gate debe listar exactamente todos los
  demás jobs (esperado: `contract-linux contract-windows parity self-test
  state-linux state-windows`)"*. GREEN ampliando `needs`. Es la prueba de que
  un job nuevo no puede quedar fuera del gate.
* Un accidente instructivo: `s.index(':compute_source_state')` encontró antes
  el `call :compute_source_state` de `:verify`, el corte quedó vacío y el
  bloque de help nuevo se insertó **al principio de `harness.cmd`**. Lo cazó
  la lectura del diff (`grep -n '^:'` mostró `:help` en la línea 1); reparado
  antes de seguir.
* Un defecto de la propia suite cazado antes de Windows: `"harness ${cmd}"`
  no es substring de `harness.cmd verify`; ahora las aserciones usan el nombre
  real del programa (`./harness` / `harness.cmd`).

### Mutaciones (con `assert old in s` y restauración en `finally`)

| Suite | Mutante | Resultado |
| ----- | ------- | --------- |
| estática | cmd `mutation` sin `test-compile` | **muerto** |
| estática | bash `format` con `spotless:check` | **muerto** |
| estática | cmd sin despachar `mutation` | **muerto** (2 tests) |
| estática | bash help sin `mutation` | **muerto** (2 tests) |
| estática | cmd help sin `state` | **muerto** (2 tests) |
| estática | bash invocando `mvnw.cmd` | **muerto** (2 tests) |
| estática | cmd invocando `mvnw` de bash | **muerto** (2 tests) |
| estática | cmd help con otro texto en `format` | **muerto** |
| contrato | `mutation` sin banner `FAILED` | **muerto** |
| contrato | `mutation` devuelve 0 aunque Maven falle | **muerto** |
| contrato | comando desconocido sin usage | **muerto** |
| contrato | bash acepta `VERIFY` | **muerto** |
| contrato | `mutation` sin `test-compile` | **muerto** |
| contrato | `verify` no copia `pit-reports` | **muerto** |
| contrato | `format` sin wrapper sale con 1 | **muerto** |

Hallazgo de la primera ronda estática: el mutante "bash help sin `mutation`"
moría, pero **el runner se caía** antes del resumen — el `diff` que imprime el
test sale con 1 y `pipefail` + `set -e` mataban la suite. Arreglado con
`|| true` y repetida la ronda completa con el resumen presente en los 8.

### Verificación local (D4)

* `tests/harness/parity_test.sh` → `5 passed, 0 failed, 0 skipped`.
* `HARNESS_IMPL=bash tests/harness/contract_test.sh` → `14 passed, 0 failed,
  0 skipped`.
* `tests/harness/state_test.sh` → `16 passed`; `selftest_gate_test.sh` →
  `10 passed` (con `needs` de 6 jobs).
* `./harness verify` → `HARNESS RESULT: PASSED`, 65 tests. Evidencia:
  `artifacts/harness/20260817T232925Z-1f09e4b-dirty-7aed121/`.
* `./harness bogus` → error a stderr, usage completo, `rc=2`; `./harness
  help` lista los cinco comandos con el texto unificado.
* El YAML parsea (`ruby -ryaml`): jobs `[self-test, state-linux,
  state-windows, contract-linux, contract-windows, parity, gate]` y
  `gate.needs` con los seis.

### Verificación en GitHub (PR #31)

Run `32083553470`: **7 jobs en success** — `Source state self-test` (las tres
suites locales), `Source state (bash)`, `Source state (cmd)`, `bash and cmd
agree on the state`, `Command contract (bash)` (14/14), `Command contract
(cmd)` (**14/14 en `windows-latest`**) y el gate `Harness self-test`. Salida
real de `harness.cmd` bajo Git Bash: `harness.cmd mutation` → `MUTATION
RESULT: COMPLETED` + `Report: …\target\pit-reports\index.html` (rc 0);
`harness.cmd verify` → `HARNESS RESULT: PASSED`, `Source: HEAD d82769f
(working tree limpio)`, evidencia con `verification.json`,
`source-state.txt`, `command.log` y `test-reports\pit-reports` (rc 0); `VERIFY`
y `bogus` → rc 2 con el usage completo; `state --manifest` sin ruta → rc 2.

## Lo que encontró el CI (y no se podía ver desde macOS)

`Command contract (cmd)` falló tres veces antes de pasar. Las tres eran
defectos reales de `harness.cmd`, no ruido, y **ninguno estaba en el
inventario del plan**: `harness.cmd verify` nunca se había ejecutado en CI
y `state` solo se había ejecutado desde `pwsh`.

1. **`verify` y `state` se colgaban bajo Git Bash.** `compute_source_state`
   contaba los archivos sucios con `… | find /c /v ""`. Bajo Git Bash el
   PATH pone `usr\bin` delante de `System32`, así que `find` es el **GNU
   find**, que toma `/c` como el directorio `C:\` y recorre el disco entero.
   El primer run murió por el timeout del job (10 min) sin decir en qué test;
   por eso `run_harness` envuelve ahora `cmd //c` en `timeout 120` (un cuelgue
   es un FAIL con rc 124 que nombra el test). Y un paso de diagnóstico
   temporal descartó a `powershell` (directo y en `for /f`) como culpable.
   Arreglo: `%SystemRoot%\System32\find.exe` con ruta absoluta; el resto de
   ejecutables externos del script (`git`, `powershell`, `xcopy`) no tienen
   homónimo GNU. Afecta a cualquier usuario de Windows que ejecute
   `harness.cmd` desde Git Bash, no solo al CI.
2. **`verify` abortaba con 255 después de Maven.** `:calculate_duration`
   hacía `set /a START_TOTAL=(((1%A-100)*60+…))` dentro de un bloque `( … )`
   de `for`; cmd toma el primer `)` de la aritmética como cierre del bloque
   y aborta el batch con `*60+(1%B-100))*60+(1%C-100)) was unexpected at
   this time` — sin `HARNESS RESULT` ni `verification.json`. Es el idioma
   documentado de batch: la expresión va entre comillas, `set /a "X=(…)"`.
   Roto desde su origen.
3. **`state --manifest` sin ruta devolvía 0.** El `exit /b 2` estaba en un
   `if` anidado dentro de otro `if` y llegó como 0 al proceso `cmd /c`; el
   mismo `exit /b 2` en un bloque simple (`mutation` sin wrapper) sí
   propaga. Ahora salta con `goto` a una etiqueta de nivel superior, el
   patrón que ya usan `help` y el comando desconocido.

Y un defecto de la propia suite que Windows destapó: `evidence_dir_of`
(`find … | head -1` en una asignación) mataba el runner bajo `pipefail`
cuando `artifacts/harness` no existía — que es justo lo que pasa cuando
`verify` se cuelga antes del `mkdir`. `state_test.sh` tenía una copia
idéntica con el mismo bug latente; ahora es una sola función en `testlib.sh`
con `|| true`. También se añadió `HARNESS_TEST_VERBOSE=1` en los jobs de
contrato: sin volcar la salida real de `harness.cmd`, los mensajes de
aserción no bastaban para diagnosticar desde macOS.

## Desviaciones respecto al plan

1. Ninguna de diseño: nombre de los jobs, suites, wrappers de mentira, `/I`,
   banners y `pit-reports` son los del plan.
2. La suite de contrato tiene **14 casos** en vez de los 8 enumerados (6–13):
   se separaron por comando los casos "sin wrapper" (`mutation`, `format`,
   `verify`) y los de fallo de Maven, para que un rojo nombre el comando.
3. **Tres arreglos en `harness.cmd` que el plan no preveía** (`find.exe`
   absoluto, `set /a` entrecomillado, `exit /b` fuera del `if` anidado) y dos
   en la suite (`timeout` en `run_harness`, `evidence_dir_of` compartido y
   tolerante). Todos salieron del job de Windows; ver "Lo que encontró el
   CI". El inventario de divergencias del plan era de **contrato**; estos son
   defectos de **ejecución** que solo aparecen al ejecutar de verdad — que es
   la tesis del issue.
