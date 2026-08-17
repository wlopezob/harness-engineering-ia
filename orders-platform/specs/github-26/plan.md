# github-26 — La evidencia identifica el estado exacto verificado

## Work item

* Fuente: GitHub Issue
  [#26](https://github.com/wlopezob/harness-engineering-ia/issues/26)
* Título: *Make harness evidence identify the exact verified source state*

El issue guarda el QUÉ. Este documento guarda el CÓMO. Es el primer work item
que toca el **harness CLI** en vez del backend (precedente de artefacto:
`specs/github-14/plan.md`).

## Entendimiento técnico (estado del working copy)

Punto de partida: `main` @ `1aec6f2` (incluye ya el trabajo de #24).

* `./harness` (bash) captura `commit_sha`, `short_sha` y `branch` en las líneas
  125-127, **antes** de ejecutar Maven. La captura temprana ya existe; lo que
  falta es la identidad del *working tree*.
* `write_verification_report` emite `verification.json` `schemaVersion 1.0` con
  `git: { commit, branch }`. No hay noción de limpio/sucio.
* El directorio de evidencia es `artifacts/harness/<timestamp>-<short_sha>`:
  hoy un run sucio produce un nombre que **sugiere** el commit limpio.
* `harness.cmd` replica el mismo esquema por duplicación manual. Divergencias
  **preexistentes** (fuera de alcance de este work item, se registran como
  hallazgo): no tiene el comando `mutation` y `copy_reports` no copia
  `pit-reports`.
* `/artifacts/` (raíz) y `target/` (api) están en `.gitignore`. **Verificado:**
  `git ls-files --cached --others --exclude-standard` lista 119 archivos y
  **ninguno** de `artifacts/` ni `target/`. Es la pieza que hace viable el
  criterio "dos verificaciones del mismo código dan el mismo id": la evidencia
  que produce la propia corrida no entra en el cálculo.
* `tests/` existe pero está **vacío**: hoy no hay ningún diente sobre el
  harness. D3 exige ver un rojo antes de escribir producción, así que este
  cambio tiene que traer su propio mecanismo de test (ver "Testabilidad").
* CI (`.github/workflows/backend-verify.yml`) ejecuta `./harness verify` tras
  un `actions/checkout` limpio (`dirty=false` siempre) y sube
  `artifacts/harness/**`. Nada del CI depende del **nombre** del directorio.

## Spike de viabilidad (ejecutado, solo lectura)

Antes de proponer el algoritmo se validó contra este repo:

```
manifiesto = por cada path (tracked + untracked no ignorados, orden LC_ALL=C):
             "<git-hash-object del contenido en disco> <path>"
state      = git hash-object --stdin  <  manifiesto
```

| Prueba | Resultado |
| ------ | --------- |
| Dos corridas seguidas | `ba685233…` == `ba685233…` (determinista) |
| Con un archivo untracked nuevo | `1ae843e7…` (cambia) |
| Tras borrar ese archivo | `ba685233…` (**vuelve al mismo id**) |
| Escritura en `.git/objects` | 555 objetos antes, 555 después (**no escribe**) |
| Coste con `--stdin-paths` | **15 ms** para 119 archivos (bucle por archivo: 990 ms) |

La cuarta fila es exactamente el criterio "dos verificaciones del mismo estado
del código deben producir el mismo identificador" y la quinta el criterio "no
debe modificar el repositorio".

## Decisiones propuestas (a validar con el usuario)

### 1. El identificador se calcula con `git hash-object`, no con `sha256sum`

`git hash-object` sin `-w` **calcula sin escribir**. Además:

* **Portabilidad real:** `sha256sum` no existe en macOS (allí es `shasum -a
  256`) y en Windows habría que usar `certutil`/PowerShell. `git` ya es un
  requisito duro del harness, así que el algoritmo es idéntico en las dos
  implementaciones sin detectar binarios.
* **Paridad Windows/macOS:** `git hash-object` sabe normalizar los finales de
  línea, pero hay que **fijar** la normalización con `-c core.autocrlf=input`:
  por defecto cada máquina usa su propia config y el resultado se invierte
  según cómo esté almacenado el archivo (ver "Lo que encontró el CI"). Con un
  sha256 del byte-stream crudo no habría forma de normalizar.
* Recomputable a mano por cualquiera con `git`, sin herramientas del harness.

Descartado `git stash create` (no incluye untracked) y el índice temporal
(`GIT_INDEX_FILE` + `read-tree` + `add -A` + `write-tree`): este último da un
tree SHA canónico y es más rápido, pero **escribe blobs y trees** en
`.git/objects`, que choca con "no debe modificar el repositorio".

### 2. Alcance del fingerprint: todo el árbol versionable del repo

`git ls-files --cached --others --exclude-standard` = archivos tracked +
untracked **no ignorados**, de todo el repo. Cubre el backend, el propio
`harness`, el workflow de CI y la config; no cubre nada ignorado (`artifacts/`,
`target/`).

*Alternativa:* limitar el scope a `orders-platform/apps/api` (el componente que
Maven verifica). Es más preciso frente a cambios irrelevantes — editar un `.md`
no cambiaría el id — pero deja un **falso negativo** peligroso: modificar el
propio `./harness` (por ejemplo quitar un goal del comando Maven) no alteraría
el identificador, y dos evidencias con el mismo id significarían cosas
distintas. Se prefiere no tener falsos negativos aunque haya falsos positivos
cosméticos; el scope queda **declarado en el JSON** para que sea explícito.

### 3. El `state` se emite siempre, no solo cuando hay cambios

El issue solo lo exige en el caso sucio, pero calcularlo siempre cuesta 15 ms y
permite comparar dos evidencias por igualdad de código sin ramificar por
`dirty`. Caso de uso concreto: una corrida sucia y otra ya commiteada del mismo
contenido comparten `state` aunque cambien `commit` y `dirty`.

### 4. Los borrados cuentan

Un archivo tracked borrado del working tree entra en el manifiesto con hash
`0000…0` (40 ceros). Sin esto, borrar un archivo no cambiaría el id (falso
negativo) o el comando fallaría al hashear un path inexistente.

### 5. El directorio de evidencia dice si el árbol estaba sucio

| Estado | Directorio |
| ------ | ---------- |
| limpio | `artifacts/harness/<timestamp>-<short_sha>` (igual que hoy) |
| sucio | `artifacts/harness/<timestamp>-<short_sha>-dirty-<state[0:7]>` |

Es el criterio "no debe confundirse con una verificación del commit limpio"
aplicado también al nombre del artefacto, que es lo primero que se ve. El CI
sube `artifacts/harness/**` y no parsea el nombre: no se rompe nada.

### 6. `verification.json` sube a `schemaVersion 1.1` con un bloque `source`

Solo se **añaden** campos; ningún consumidor actual se rompe (1.1, no 2.0):

```json
{
  "schemaVersion": "1.1",
  "git": { "commit": "abc123…", "branch": "feat/x" },
  "source": {
    "dirty": true,
    "state": "9f2c1d4…",
    "stateAlgorithm": "git-hash-object(manifest: '<blob> <path>' LC_ALL=C sorted, LF)",
    "scope": "repo:tracked+untracked-not-ignored",
    "changedFiles": 3,
    "manifest": "source-state.txt"
  },
  "evidence": {
    "commandLog": "command.log",
    "testReports": "test-reports",
    "sourceManifest": "source-state.txt"
  }
}
```

`git.commit` (base) + `source.dirty` (limpio/sucio) + `source.state` (estado
exacto) son los tres campos que el criterio pide poder distinguir.

**El manifiesto se guarda como evidencia** (`source-state.txt`): el id deja de
ser un número opaco y pasa a ser auditable — dos evidencias se diffean y se ve
exactamente qué archivo cambió entre ellas.

### 7. Consola: el aviso va al principio y al final

```
Commit:     abc1234 (feat/gh-26-verified-source-state)
Source:     DIRTY - 3 archivo(s) local(es) sin commit
State:      9f2c1d4  (evidencia: source-state.txt)
```

y en el bloque de resultado:

```
 HARNESS RESULT: PASSED
 Source: HEAD abc1234 + cambios locales (state 9f2c1d4)
 Evidence: …
```

Con árbol limpio la segunda línea es `Source: HEAD abc1234 (working tree
limpio)`. El aviso al final importa porque en un log largo el encabezado ya no
se ve.

### 8. Testabilidad: subcomando `./harness state` + suite en `tests/`

Sin esto no hay forma de cumplir D3: los tests no pueden esperar `mvn clean
verify` (minutos) para comprobar un hash.

* **`./harness state`** imprime el bloque `source` (JSON) y termina. No ejecuta
  Maven, no escribe evidencia, no modifica nada. Es a la vez el punto de
  enganche de los tests y una herramienta útil ("¿qué estoy a punto de
  verificar?").
* **`tests/harness/state_test.sh`**: bash plano, sin dependencias nuevas (no se
  añade bats). Cada caso crea un repo git temporal en un directorio temporal,
  copia el `harness` bajo prueba y comprueba el comportamiento.
* Se añade al CI un paso `Harness self-test` que ejecuta esa suite. No toca las
  reglas de JaCoCo/SpotBugs/PIT ni los tests del backend (fuera de alcance del
  issue).
* **Limitación declarada:** la suite corre `./harness` (bash). `harness.cmd` no
  se puede ejecutar desde macOS/Linux, así que su paridad se revisa por
  inspección y queda como deuda visible (un job Windows en CI sería el arreglo
  real; fuera de alcance aquí).

## Cambios propuestos

```
harness                                 + compute_source_state(), state en verify,
                                          subcomando `state`, consola, JSON 1.1
harness.cmd                             + la misma lógica (paridad)
tests/harness/state_test.sh             NUEVO — dientes del comportamiento
.github/workflows/backend-verify.yml    + paso "Harness self-test"
orders-platform/DECISIONS.md            + D-026
orders-platform/specs/github-26/plan.md este documento
```

Sin tocar: backend, `pom.xml`, contratos, reglas de calidad.

Pseudocódigo de la función (bash; el `.cmd` replica el resultado):

```bash
compute_source_state() {          # no escribe nada, se llama ANTES de Maven
  paths   = git -c core.quotePath=false ls-files --cached --others \
              --exclude-standard | LC_ALL=C sort
  existentes, borrados = particionar(paths)          # -e "$path"
  hashes  = git hash-object --stdin-paths < existentes      # 1 sola invocación
  manifest = paste(hashes, existentes) + "000…0 <path>" por cada borrado,
             reordenado por path (LC_ALL=C), líneas terminadas en LF
  state   = git hash-object --stdin < manifest
  dirty   = ¿git status --porcelain (incluyendo untracked) no está vacío?
}
```

`changedFiles` sale de contar las líneas de `git status --porcelain`.

## Impacto

| Componente | Impacto |
| ---------- | ------- |
| `harness` (bash) | **sí** — cálculo, consola, JSON, subcomando `state` |
| `harness.cmd` | **sí** — paridad |
| `tests/` | **sí** — primera suite del harness |
| CI | **sí** — un paso nuevo (no cambia el de Maven) |
| Backend / contratos / reglas | no |
| Repositorio en disco | **no se modifica** (criterio del issue, verificado en el spike) |

## Casos de test en orden (D3 — RED → GREEN → refactor)

Todos sobre `tests/harness/state_test.sh`, cada uno en un repo git temporal.

1. `state` sobre un árbol limpio reporta `dirty=false` y un `state` no vacío.
   ← **primer RED** (el subcomando no existe).
2. Dos invocaciones seguidas sin tocar nada devuelven **el mismo** `state`
   (determinismo; es el criterio "independiente del timestamp").
3. Modificar un archivo tracked cambia el `state` y pone `dirty=true`.
4. **Crear un archivo untracked** (no ignorado) cambia el `state`
   — el caso que un `git stash create` no cubriría.
5. Un archivo **ignorado** (`target/x`, `artifacts/y`) **no** cambia el `state`
   ni marca `dirty` — es lo que garantiza que la propia evidencia no altere la
   identidad.
6. **Borrar** un archivo tracked cambia el `state`.
7. Revertir el cambio devuelve el `state` original (ida y vuelta).
8. Con el mismo contenido en dos commits distintos, `state` coincide aunque
   `commit` difiera (el id identifica *código*, no historia).
9. `state` **no modifica el repositorio**: `git status --porcelain` y el conteo
   de `.git/objects` son idénticos antes y después.
10. `verify` escribe `source-state.txt` y un `verification.json` con
    `schemaVersion 1.1`, `source.dirty`, `source.state` y `git.commit`
    (se ejercita con un stub de Maven para no correr el build real).
11. Con árbol sucio, el directorio de evidencia termina en `-dirty-<state7>` y
    la consola imprime el aviso.

## Verificación (D4)

```bash
tests/harness/state_test.sh     # dientes nuevos
./harness verify                # el flujo actual sigue compatible
./harness state                 # inspección manual del bloque source
```

Evidencia esperada: la suite en verde, `HARNESS RESULT: PASSED` con el bloque
`source` en `verification.json`, y una demostración manual de que un cambio
local produce un `state` distinto y un directorio marcado como sucio.

`./harness mutation` no aplica: no se toca código Java.

## Resultado

### Ciclo TDD (rojos observados)

* **Primer RED:** `./harness state` no existía (`ERROR: Unknown harness command:
  state`).
* **RED de verify:** 7 fallos que son literalmente los criterios del issue
  (`el esquema sube a 1.1: esperado '1.1', obtenido '1.0'`, `la evidencia debe
  declarar el árbol sucio`, `el nombre del directorio no puede parecer una
  verificación del commit limpio`, `la consola debe avisar de los cambios
  locales`…).
* Suite final: **12/12 en verde**.

### Pruebas de mutación sobre la implementación

Los 9 primeros casos pasaron a la primera (el algoritmo venía validado por el
spike), así que se les dio dientes mutando el script. **La primera ronda dio
"todos muertos" y era mentira**: los patrones de `sed` no coincidían y las
mutaciones nunca se aplicaron. Repetida con una verificación de que el
reemplazo entra (`assert old in s`), el resultado real fue:

| Mutante | Resultado |
| ------- | --------- |
| quitar `--others` (no ver untracked) | **muerto** (2 tests) |
| quitar `--exclude-standard` (entrarían `artifacts/`, `target/`) | **muerto** |
| no filtrar los tracked borrados | **muerto** (tras cerrar un agujero, ver abajo) |
| hashear el índice en vez del disco | **muerto** |
| hash-cero para los borrados | **sobrevivía → código eliminado** |
| invertir el orden de los grupos | sobrevive (esperado: cualquier orden fijo es válido; lo que importa es que ambos scripts usen el mismo, y eso lo mide el CI) |

Dos correcciones que salieron de ahí:

1. El **hash-cero para borrados** era código que ningún test exigía: la simple
   ausencia de la línea en el manifiesto ya cambia la identidad. Se eliminó.
2. El test del borrado **pasaba por la razón equivocada**: al hacer el runner
   tolerante a fallos, un comando roto devolvía cadena vacía y
   `assert_not_equals` daba verde. Se añadió `assert_not_empty` a los tests que
   comparan un "after", de modo que exigen *cambió* **y** *sigue funcionando*.

### Verificación (D4)

* `tests/harness/state_test.sh` → **PASSED (12/12)**.
* `./harness verify` con el árbol sucio → `HARNESS RESULT: PASSED`, 65 tests
  (el flujo actual sigue compatible). Evidencia:
  `artifacts/harness/20260817T165216Z-1aec6f2-dirty-f5c05dc/`.
* `./harness state` sobre el repo real: `dirty: true`, `changedFiles: 5`,
  determinista entre corridas y con `.git/objects` intacto (555 → 555).
* **El identificador es auditable:** recomputar a mano
  `git hash-object --stdin < source-state.txt` devuelve exactamente el `state`
  publicado en `verification.json`.
* **Paridad demostrada en CI:** el job `bash and cmd agree on the state` pasa
  en verde tras los tres arreglos de abajo. `./harness` en `ubuntu-latest` y
  `harness.cmd` en `windows-latest` calculan **el mismo identificador** para el
  mismo código, con el checkout de Windows entregando CRLF. Es la primera
  ejecución real de `harness.cmd`, que no se puede correr desde macOS.

## Lo que encontró el CI (y no se podía ver desde macOS)

El job de paridad falló tres veces antes de pasar. Cada fallo fue un defecto
real, no ruido:

1. **`"state": "unknown"` en Windows.** `%~dp0` termina en barra invertida, así
   que `git -C "C:\ruta\"` deja la comilla de cierre escapada y git nunca
   recibe el directorio. Afectaba a **todas** las llamadas a git de
   `harness.cmd`: `COMMIT_SHA` ya salía `unknown` en Windows antes de esta
   rama. El job nuevo es lo que lo hizo visible.
2. **El manifiesto contaminaba su propio identificador.** El job de bash
   llamaba dos veces a `./harness state`; la primera escribía `manifest.txt`
   dentro del repo y la segunda lo veía como untracked. Era un fallo del
   workflow, no de los scripts, y se arregló volcando fuera del repo y leyendo
   el state de la misma invocación.
3. **La normalización de fin de línea no estaba fijada.** Con el diff de los
   dos manifiestos, solo **2 de 122 líneas** diferían: `mvnw.cmd` (los dos que
   hay en el repo). Están **almacenados en git con CRLF**, y `git hash-object`
   aplica la conversión según el `core.autocrlf` de cada máquina: Linux los
   hashea tal cual (CRLF) y Windows los normaliza a LF. La afirmación del plan
   ("git hash-object hace que CRLF y LF den el mismo hash") era **incompleta**:
   vale para archivos guardados con LF y se invierte para los guardados con
   CRLF. El arreglo es fijar `-c core.autocrlf=input` al hashear, de modo que
   todo se hashee como si terminara en LF en cualquier plataforma. Reproducido
   después como test local (`test_el_state_no_depende_del_fin_de_linea_en_disco`),
   que falla sin el fix.

## Desviaciones respecto al plan

1. **El orden ya no lo da `LC_ALL=C sort`, lo da git.** El plan asumía un sort
   explícito; al escribir la paridad se vio que el `sort` de Windows ordena
   según el locale (no byte-wise), lo que produciría identificadores distintos
   para el mismo código. Se comprobó que `git ls-files` emite **cada grupo** en
   orden byte-wise (mismo código en toda plataforma) y el manifiesto pasó a ser
   `--cached` seguido de `--others`, sin sort. Efecto lateral: se eliminó la
   dependencia del locale y de PowerShell.
2. **La paridad se mide, no se declara.** El plan la dejaba "por inspección"
   porque no hay Windows ni PowerShell en el entorno de desarrollo. En su lugar
   se añadió el workflow `harness-selftest.yml` con un job **`windows-latest`
   real**: cada plataforma calcula el `state` y un tercer job falla si difieren.
   Es también la prueba de que la normalización CRLF→LF de `git hash-object`
   funciona (el checkout de Windows entrega CRLF).
3. **Workflow nuevo en vez de un paso dentro de `backend-verify.yml`**, para no
   tocar el job de Maven que ya existe.
4. `harness.cmd` invoca `git hash-object` **por archivo** en vez de
   `--stdin-paths`: en batch, unir dos ficheros línea a línea era O(n²) y
   frágil con `DisableDelayedExpansion`. El manifiesto resultante es idéntico.
5. Se añadió un caso 12 (paridad declarada: mismo `stateAlgorithm`, mismo
   `scope` y mismos comandos git en ambos scripts), que caza el olvido de tocar
   uno de los dos.

## Assumptions

* "Estado verificado" = el árbol versionable del repo, no el entorno (versión
  de JDK, Maven, imagen de Postgres). Reproducibilidad del entorno es otro
  problema y el issue no lo pide.
* Los archivos ignorados por git no forman parte del código verificado.
* `git` está disponible siempre que corre el harness (ya es así hoy: el script
  actual invoca `git rev-parse` y degrada a `unknown` si falla — se conserva
  ese degradado para el nuevo cálculo).
* El formato `<hash> <path>` del manifiesto se fija con líneas LF en ambas
  plataformas para que el `state` sea idéntico entre bash y cmd.

## Open questions

Ninguna. Resueltas con el usuario antes de implementar:

1. **Scope**: todo el árbol versionable (decisión 2) — se prefiere el falso
   positivo cosmético a un falso negativo que dejaría ciego el id ante cambios
   en el propio harness.
2. **Dientes**: sí al subcomando `state` y a estrenar `tests/` (decisión 8),
   con el paso nuevo en CI.
3. **Directorio**: sí al sufijo `-dirty-<state>` (decisión 5).
