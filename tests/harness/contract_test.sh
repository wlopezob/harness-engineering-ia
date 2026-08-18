#!/usr/bin/env bash
#
# Contrato de comportamiento del harness (github-30). La MISMA suite se ejecuta
# contra ./harness (HARNESS_IMPL=bash) y contra harness.cmd (HARNESS_IMPL=cmd,
# desde Git Bash en windows-latest): entradas equivalentes, mismas aserciones.
# Maven se sustituye por wrappers de mentira (mvnw / mvnw.cmd) que registran
# los argumentos recibidos y salen con HARNESS_TEST_MVNW_EXIT.

set -Eeuo pipefail

TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${TESTS_DIR}/../.." && pwd)"
HARNESS_IMPL="${HARNESS_IMPL:-bash}"

# Runner compartido: contadores, assert_*, run_test, finish_suite.
# shellcheck source=tests/harness/testlib.sh
source "${TESTS_DIR}/testlib.sh"

case "${HARNESS_IMPL}" in
  bash) ;;
  cmd)
    if ! command -v cmd >/dev/null 2>&1; then
      echo "ERROR: HARNESS_IMPL=cmd necesita cmd.exe (Git Bash en Windows)" >&2
      exit 2
    fi
    ;;
  *)
    echo "ERROR: HARNESS_IMPL debe ser bash o cmd (recibido: '${HARNESS_IMPL}')" >&2
    exit 2
    ;;
esac

API_REL="orders-platform/apps/api"

# cómo se llama a sí mismo cada script en su usage
case "${HARNESS_IMPL}" in
  bash) PROGRAM="./harness" ;;
  cmd)  PROGRAM="harness.cmd" ;;
esac

# Repo git temporal con los dos scripts y los dos wrappers de mentira. Los
# wrappers escriben "$*" en mvnw-args.txt, fabrican un pit-reports mínimo (como
# haría PIT, salvo con HARNESS_TEST_MVNW_NO_REPORT: entonces fallan sin generar
# nada, como un test-compile que no compila), crean HARNESS_TEST_MVNW_TOUCH
# dentro del backend si se pide (un
# archivo NO ignorado, para comprobar qué código declara la evidencia), duermen
# HARNESS_TEST_MVNW_SLEEP segundos si se pide (para que durationSeconds tenga
# algo que medir) y salen con HARNESS_TEST_MVNW_EXIT.
new_repo() {
  local dir api
  dir="$(mktemp -d "${TEST_TMP_ROOT}/repo.XXXXXX")"
  api="${dir}/${API_REL}"

  mkdir -p "${api}/src"
  printf 'class Main {}\n' > "${api}/src/Main.java"
  printf '/artifacts/\ntarget/\nmvnw-args.txt\n' > "${dir}/.gitignore"
  cp "${REPO_ROOT}/harness" "${dir}/harness"
  cp "${REPO_ROOT}/harness.cmd" "${dir}/harness.cmd"
  chmod +x "${dir}/harness"

  cat > "${api}/mvnw" <<'STUB'
#!/usr/bin/env bash
here="$(cd "$(dirname "$0")" && pwd)"
printf '%s\n' "$*" > "${here}/mvnw-args.txt"
printf 'maven-stub: %s\n' "$*"
if [[ -z "${HARNESS_TEST_MVNW_NO_REPORT:-}" ]]; then
  mkdir -p "${here}/target/pit-reports"
  printf '<html/>\n' > "${here}/target/pit-reports/index.html"
fi
[[ -n "${HARNESS_TEST_MVNW_TOUCH:-}" ]] \
  && printf 'generado durante la corrida\n' > "${here}/${HARNESS_TEST_MVNW_TOUCH}"
[[ -n "${HARNESS_TEST_MVNW_SLEEP:-}" ]] && sleep "${HARNESS_TEST_MVNW_SLEEP}"
exit "${HARNESS_TEST_MVNW_EXIT:-0}"
STUB
  chmod +x "${api}/mvnw"

  # CRLF: es un .cmd de verdad para cmd.exe
  printf '%s\r\n' \
    '@echo off' \
    'echo %* > "%~dp0mvnw-args.txt"' \
    'echo maven-stub: %*' \
    'if defined HARNESS_TEST_MVNW_NO_REPORT goto :sin_reporte' \
    'if not exist "%~dp0target\pit-reports" mkdir "%~dp0target\pit-reports"' \
    'echo ^<html/^> > "%~dp0target\pit-reports\index.html"' \
    ':sin_reporte' \
    'if defined HARNESS_TEST_MVNW_TOUCH echo generado durante la corrida > "%~dp0%HARNESS_TEST_MVNW_TOUCH%"' \
    'if defined HARNESS_TEST_MVNW_SLEEP powershell -NoProfile -Command "Start-Sleep -Seconds %HARNESS_TEST_MVNW_SLEEP%"' \
    'if not defined HARNESS_TEST_MVNW_EXIT set "HARNESS_TEST_MVNW_EXIT=0"' \
    'exit /b %HARNESS_TEST_MVNW_EXIT%' \
    > "${api}/mvnw.cmd"

  git -C "${dir}" init --quiet
  git -C "${dir}" config user.email "harness@test.local"
  git -C "${dir}" config user.name "harness test"
  git -C "${dir}" add -A
  git -C "${dir}" commit --quiet -m "init"

  printf '%s' "${dir}"
}

# Ejecuta el harness bajo prueba en el repo $1 con el resto de argumentos.
# Deja HARNESS_RC y HARNESS_OUT (stdout+stderr, sin CR); tolerante a fallo.
HARNESS_RC=0
HARNESS_OUT=""
run_harness() {
  local dir="$1"
  shift
  HARNESS_RC=0
  case "${HARNESS_IMPL}" in
    bash) HARNESS_OUT="$( cd "${dir}" && ./harness "$@" 2>&1 )" || HARNESS_RC=$? ;;
    # timeout: un cuelgue de harness.cmd tiene que ser un FAIL (rc 124), no
    # un job de CI que muere por su propio timeout sin decir en qué test
    cmd)  HARNESS_OUT="$( cd "${dir}" && timeout 120 cmd //c harness.cmd "$@" 2>&1 )" || HARNESS_RC=$? ;;
  esac
  HARNESS_OUT="${HARNESS_OUT//$'\r'/}"

  # ninguna corrida puede emitir errores internos del intérprete: un banner en
  # verde con "Unbalanced parenthesis." en medio es un falso verde
  assert_no_interpreter_errors "${HARNESS_OUT}" "${PROGRAM} $*"

  # HARNESS_TEST_VERBOSE=1 vuelca cada corrida: en CI es la única forma de ver
  # qué imprimió harness.cmd cuando una aserción falla en la otra plataforma.
  # Va a stderr a propósito: en stdout, un helper que capture run_harness se
  # lleva el volcado dentro del valor, y eso solo se ve en CI (lo pagó este
  # mismo issue: verde en local, rojo en los dos jobs).
  if [[ "${HARNESS_TEST_VERBOSE:-0}" == "1" ]]; then
    printf '    $ %s %s  (rc=%s)\n' "${PROGRAM}" "$*" "${HARNESS_RC}" >&2
    printf '%s\n' "${HARNESS_OUT}" | sed 's/^/    | /' >&2
  fi
}

# Mensajes que solo emite el propio intérprete (cmd.exe o bash) cuando el
# script está roto. Ninguno es salida legítima del harness.
INTERPRETER_ERROR_PATTERNS=(
  "Unbalanced parenthesis."
  "was unexpected at this time."
  "is not recognized as an internal or external command"
  "The syntax of the command is incorrect."
  "Missing operand."
  "Missing operator."
  "Invalid number."
  "Divide by zero error."
  "The system cannot find the batch label"
  "The system cannot find the path specified."
  "The system cannot find the file specified."
  ": command not found"
  ": syntax error"
  ": unbound variable"
  ": No such file or directory"
)

assert_no_interpreter_errors() {
  local output="$1" what="$2" pattern
  for pattern in "${INTERPRETER_ERROR_PATTERNS[@]}"; do
    if [[ "${output}" == *"${pattern}"* ]]; then
      fail "${what} emitió un error interno del intérprete: '${pattern}'"
    fi
  done
}

# JSON real: jq en los runners y en la máquina de desarrollo; python3 de reserva
json_query() {
  local file="$1" query="$2"
  # sin esto, un documento ausente hace que bash escriba su propio error de
  # redirección en el log de la suite; el FAIL ya lo cuenta el llamador
  [[ -f "${file}" ]] || return 3
  if command -v jq >/dev/null 2>&1; then
    tr -d '\r' < "${file}" | jq -e -r "${query}" 2>/dev/null
  elif command -v python3 >/dev/null 2>&1; then
    python3 - "${file}" "${query}" <<'PY' 2>/dev/null
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
query = sys.argv[2].strip()
want_type = query.endswith("| type")
if want_type:
    query = query[: -len("| type")].strip()
if query == ".":
    print("ok")
    sys.exit(0)
value = data
for part in query.lstrip(".").split("."):
    value = value[part]
if want_type:
    print("number" if isinstance(value, (int, float)) and not isinstance(value, bool) else "other")
elif isinstance(value, bool):
    print("true" if value else "false")
else:
    print(value)
PY
  else
    return 3
  fi
}

# Un campo ausente no puede pasar por presente: jq imprime 'null' (y con `|| true`
# eso llegaría como valor no vacío); el fallback de python3 devuelve vacío.
assert_json_value() {
  local file="$1" query="$2" message="$3" value
  value="$(json_query "${file}" "${query}" || true)"
  if [[ -z "${value}" || "${value}" == "null" ]]; then
    fail "${message} (obtenido: '${value}')"
  fi
}

# El documento $2 de la evidencia $1 debe ser JSON parseable con exitCode y
# durationSeconds numéricos (y exitCode igual al código de salida $3). Lo
# comparten verification.json y mutation.json: la garantía es la misma.
assert_run_json() {
  local evidence="$1" name="$2" expected_rc="$3" file="$1/$2" parsed
  if [[ ! -f "${file}" ]]; then
    fail "la evidencia debe incluir ${name}"
    return 0
  fi
  if ! json_query "${file}" "." >/dev/null; then
    fail "${name} debe ser JSON parseable"
    return 0
  fi
  parsed="$(json_query "${file}" ".durationSeconds | type" || true)"
  assert_equals "number" "${parsed}" "${name} debe tener durationSeconds numérico"
  parsed="$(json_query "${file}" ".durationSeconds" || true)"
  if ! [[ "${parsed}" =~ ^[0-9]+$ ]]; then
    fail "durationSeconds debe ser un entero >= 0 (obtenido: '${parsed}')"
  fi
  parsed="$(json_query "${file}" ".exitCode" || true)"
  assert_equals "${expected_rc}" "${parsed}" "${name} debe registrar el exitCode real"
}

assert_verification_json() {
  assert_run_json "$1" "verification.json" "$2"
}

# identificador que imprime `harness state` en el repo $1; lo deja en
# STATE_OF, NO en stdout: con HARNESS_TEST_VERBOSE=1 (lo que usan los dos jobs
# de CI) run_harness vuelca la corrida entera, y capturarla la metía dentro del
# valor. Sin sustitución de comandos, además, los FAIL que emite run_harness
# cuentan de verdad en vez de perderse en la subshell.
STATE_OF=""
state_of() {
  run_harness "$1" state
  STATE_OF="$(
    printf '%s\n' "${HARNESS_OUT}" \
      | sed -n 's/.*"state": "\([0-9a-f]*\)".*/\1/p' \
      | head -1
  )"
}

# argumentos que recibió el wrapper de mentira en el repo $1
mvnw_args_of() {
  local file="$1/${API_REL}/mvnw-args.txt"
  [[ -f "${file}" ]] || return 0
  tr -d '\r' < "${file}" | sed 's/[[:space:]]*$//'
}


# --- help / desconocido ------------------------------------------------------

test_help_lista_todos_los_comandos_publicos() {
  local dir cmd
  dir="$(new_repo)"
  run_harness "${dir}" help
  assert_equals "0" "${HARNESS_RC}" "help debe salir con 0"
  for cmd in verify format mutation state help; do
    assert_contains "${HARNESS_OUT}" "  ${PROGRAM} ${cmd}" "help debe listar '${cmd}'"
  done
}

test_sin_argumentos_muestra_el_help() {
  local dir
  dir="$(new_repo)"
  run_harness "${dir}"
  assert_equals "0" "${HARNESS_RC}" "sin argumentos debe salir con 0"
  assert_contains "${HARNESS_OUT}" "Usage:" "sin argumentos debe mostrar el usage"
  assert_contains "${HARNESS_OUT}" "  ${PROGRAM} mutation" "sin argumentos debe listar todos los comandos"
}

test_un_comando_desconocido_falla_con_2_y_muestra_el_usage() {
  local dir
  dir="$(new_repo)"
  run_harness "${dir}" bogus
  assert_equals "2" "${HARNESS_RC}" "un comando desconocido debe salir con 2"
  assert_contains "${HARNESS_OUT}" "ERROR: Unknown harness command: bogus" \
    "debe nombrar el comando desconocido"
  assert_contains "${HARNESS_OUT}" "  ${PROGRAM} mutation" \
    "tras el error debe mostrar el usage completo"

  # paridad estricta: el conjunto aceptado es exactamente el mismo
  run_harness "${dir}" VERIFY
  assert_equals "2" "${HARNESS_RC}" "los comandos son en minúsculas: VERIFY es desconocido"
}

# --- mutation ----------------------------------------------------------------

test_mutation_lanza_pit_con_el_wrapper_de_la_plataforma() {
  local dir
  dir="$(new_repo)"
  run_harness "${dir}" mutation
  assert_equals "0" "${HARNESS_RC}" "mutation debe salir con 0 cuando Maven termina bien"
  assert_equals \
    "--batch-mode --no-transfer-progress test-compile org.pitest:pitest-maven:mutationCoverage" \
    "$(mvnw_args_of "${dir}")" \
    "mutation debe pasar a Maven el mismo proceso lógico (test-compile + PIT)"
  assert_contains "${HARNESS_OUT}" "MUTATION RESULT: COMPLETED" "mutation debe anunciar el resultado"
  assert_contains "${HARNESS_OUT}" "pit-reports" "mutation debe indicar dónde queda el reporte"
}

test_mutation_deja_evidencia_con_su_propio_documento() {
  local dir evidence json
  dir="$(new_repo)"
  run_harness "${dir}" mutation
  assert_equals "0" "${HARNESS_RC}" "mutation debe salir con 0 cuando PIT termina bien"

  evidence="$(evidence_dir_of "${dir}" mutation)"
  assert_not_empty "${evidence}" "mutation debe dejar un directorio de evidencia"

  if [[ -n "${evidence}" ]]; then
    json="${evidence}/mutation.json"
    if [[ ! -f "${json}" ]]; then
      fail "la evidencia de mutation debe incluir mutation.json"
      return 0
    fi
    if ! json_query "${json}" "." >/dev/null; then
      fail "mutation.json debe ser JSON parseable"
      return 0
    fi
    assert_equals "1.0" "$(json_query "${json}" ".schemaVersion" || true)" \
      "mutation.json debe declarar el esquema de su documento"
    assert_contains "$(json_query "${json}" ".command" || true)" "mutation" \
      "mutation.json debe registrar el comando ejecutado"
    assert_equals "orders-platform/apps/api" "$(json_query "${json}" ".component" || true)" \
      "mutation.json debe registrar el componente analizado"
    assert_contains "${HARNESS_OUT}" "$(basename "${evidence}")" \
      "el banner debe decir dónde quedó la evidencia"
  fi
}

test_mutation_registra_el_resultado_y_lo_que_tardo() {
  local dir evidence json duration
  dir="$(new_repo)"
  # PIT "tarda" 2 s: durationSeconds tiene que medirlo, no ser un 0 que pasa
  # por número válido
  HARNESS_TEST_MVNW_SLEEP=2 run_harness "${dir}" mutation
  assert_equals "0" "${HARNESS_RC}" "mutation debe salir con 0 cuando PIT termina bien"

  evidence="$(evidence_dir_of "${dir}" mutation)"
  assert_not_empty "${evidence}" "mutation debe dejar un directorio de evidencia"

  if [[ -n "${evidence}" ]]; then
    assert_run_json "${evidence}" "mutation.json" 0
    json="${evidence}/mutation.json"
    assert_equals "COMPLETED" "$(json_query "${json}" ".result" || true)" \
      "mutation.json debe registrar el mismo resultado que anuncia el banner"
    assert_json_value "${json}" ".startedAt" "mutation.json debe registrar cuándo empezó"
    assert_json_value "${json}" ".finishedAt" "mutation.json debe registrar cuándo terminó"
    duration="$(json_query "${json}" ".durationSeconds" || true)"
    if ! [[ "${duration}" =~ ^[0-9]+$ ]] || [[ "${duration}" -lt 2 ]]; then
      fail "durationSeconds debe medir la corrida (PIT durmió 2 s; obtenido: '${duration}')"
    fi
    assert_json_value "${json}" ".git.commit" "mutation.json debe conservar el commit base"
    assert_json_value "${json}" ".git.branch" "mutation.json debe conservar la rama"
  fi
}

test_mutation_conserva_el_log_y_los_reportes_de_pit() {
  local dir evidence json
  dir="$(new_repo)"
  run_harness "${dir}" mutation

  evidence="$(evidence_dir_of "${dir}" mutation)"
  assert_not_empty "${evidence}" "mutation debe dejar un directorio de evidencia"

  if [[ -n "${evidence}" ]]; then
    if [[ ! -f "${evidence}/command.log" ]]; then
      fail "la evidencia debe conservar el log de Maven/PIT"
    elif ! grep -q "maven-stub" "${evidence}/command.log"; then
      fail "command.log debe contener la salida real de Maven/PIT"
    fi

    [[ -f "${evidence}/pit-reports/index.html" ]] \
      || fail "la evidencia debe copiar target/pit-reports"

    json="${evidence}/mutation.json"
    assert_equals "command.log" "$(json_query "${json}" ".evidence.commandLog" || true)" \
      "mutation.json debe apuntar al log conservado"
    assert_equals "pit-reports" "$(json_query "${json}" ".evidence.pitReports" || true)" \
      "mutation.json debe apuntar a los reportes conservados"
  fi
}

test_mutation_falla_con_el_exit_code_de_maven() {
  local dir
  dir="$(new_repo)"
  HARNESS_TEST_MVNW_EXIT=3 run_harness "${dir}" mutation
  assert_equals "3" "${HARNESS_RC}" "mutation debe propagar el exit code de Maven"
  assert_contains "${HARNESS_OUT}" "MUTATION RESULT: FAILED" "mutation debe anunciar el fallo"
  if [[ "${HARNESS_OUT}" == *"MUTATION RESULT: COMPLETED"* ]]; then
    fail "mutation no puede anunciar COMPLETED cuando Maven falló"
  fi
}

test_mutation_deja_evidencia_cuando_pit_falla() {
  local dir evidence json
  dir="$(new_repo)"
  # PIT falla igual que cuando no alcanza el threshold: deja su reporte y sale
  # con un código distinto de 0
  HARNESS_TEST_MVNW_EXIT=3 run_harness "${dir}" mutation
  assert_equals "3" "${HARNESS_RC}" "mutation debe seguir propagando el exit code de PIT"

  evidence="$(evidence_dir_of "${dir}" mutation)"
  assert_not_empty "${evidence}" "mutation debe dejar evidencia también cuando falla"

  if [[ -n "${evidence}" ]]; then
    assert_run_json "${evidence}" "mutation.json" 3
    json="${evidence}/mutation.json"
    assert_equals "FAILED" "$(json_query "${json}" ".result" || true)" \
      "mutation.json debe registrar el fallo"
    if [[ ! -f "${evidence}/command.log" ]] || ! grep -q "maven-stub" "${evidence}/command.log"; then
      fail "el log de la corrida fallida es justo el que hay que conservar"
    fi
    [[ -f "${evidence}/pit-reports/index.html" ]] \
      || fail "PIT deja reporte aunque falle: también va a la evidencia"
  fi
}

test_mutation_no_adjunta_el_reporte_de_una_corrida_anterior() {
  local dir evidence json referencia
  dir="$(new_repo)"

  # una corrida anterior dejó su reporte en target/: mutation no hace `clean`,
  # así que sigue ahí cuando empieza la siguiente
  mkdir -p "${dir}/${API_REL}/target/pit-reports"
  printf '<html>REPORTE-VIEJO</html>\n' > "${dir}/${API_REL}/target/pit-reports/index.html"

  # esta corrida falla antes de que PIT genere nada (p.ej. en test-compile)
  HARNESS_TEST_MVNW_NO_REPORT=1 HARNESS_TEST_MVNW_EXIT=1 run_harness "${dir}" mutation
  assert_equals "1" "${HARNESS_RC}" "mutation debe propagar el fallo"

  evidence="$(evidence_dir_of "${dir}" mutation)"
  assert_not_empty "${evidence}" "mutation debe dejar evidencia también cuando falla"

  if [[ -n "${evidence}" ]]; then
    # la evidencia declara el código de ESTA corrida: adjuntarle el reporte de
    # la anterior sería decir que se analizó un código que no se analizó
    if [[ -d "${evidence}/pit-reports" ]] \
      && grep -rq "REPORTE-VIEJO" "${evidence}/pit-reports" 2>/dev/null; then
      fail "la evidencia no puede adjuntar el reporte de una corrida anterior"
    fi

    json="${evidence}/mutation.json"
    referencia="$(json_query "${json}" ".evidence.pitReports" || true)"
    if [[ "${referencia}" == "pit-reports" ]]; then
      fail "mutation.json no puede prometer reportes que esta corrida no produjo"
    fi
  fi
}

test_mutation_sin_wrapper_no_adjunta_el_reporte_de_una_corrida_anterior() {
  local dir evidence json referencia
  dir="$(new_repo)"

  # reporte de una corrida anterior, todavía en target/
  mkdir -p "${dir}/${API_REL}/target/pit-reports"
  printf '<html>REPORTE-VIEJO</html>\n' > "${dir}/${API_REL}/target/pit-reports/index.html"

  # y esta corrida ni siquiera llega a Maven: falla en la validación previa
  rm -f "${dir}/${API_REL}/mvnw" "${dir}/${API_REL}/mvnw.cmd"
  run_harness "${dir}" mutation
  assert_equals "2" "${HARNESS_RC}" "sin Maven Wrapper mutation debe salir con 2"

  evidence="$(evidence_dir_of "${dir}" mutation)"
  assert_not_empty "${evidence}" "el fallo previo a Maven también deja evidencia"

  if [[ -n "${evidence}" ]]; then
    # el camino que no ejecuta PIT es el que más fácil adjunta un reporte ajeno
    if [[ -d "${evidence}/pit-reports" ]]; then
      fail "una corrida que no ejecutó PIT no puede adjuntar reportes"
    fi
    if [[ -d "${evidence}/pit-reports" ]] \
      && grep -rq "REPORTE-VIEJO" "${evidence}/pit-reports" 2>/dev/null; then
      fail "y menos el de una corrida anterior"
    fi

    json="${evidence}/mutation.json"
    referencia="$(json_query "${json}" ".evidence.pitReports" || true)"
    assert_equals "null" "${referencia}" \
      "mutation.json debe declarar que esta corrida no dejó reporte"
  fi
}

test_la_evidencia_de_mutation_describe_el_codigo_de_antes_de_correr_pit() {
  local dir before after evidence json declared recomputed
  dir="$(new_repo)"
  state_of "${dir}"
  before="${STATE_OF}"
  assert_not_empty "${before}" "el repo de prueba debe tener un state"

  # PIT genera un archivo que NO está ignorado: si el harness calculara la
  # identidad al final, declararía un código que no es el que analizó
  HARNESS_TEST_MVNW_TOUCH="pit-generado.txt" run_harness "${dir}" mutation
  assert_equals "0" "${HARNESS_RC}" "mutation debe salir con 0"

  state_of "${dir}"
  after="${STATE_OF}"
  assert_not_equals "${before}" "${after}" \
    "lo que generó PIT tiene que cambiar el state: si no, el caso no prueba nada"

  evidence="$(evidence_dir_of "${dir}" mutation)"
  assert_not_empty "${evidence}" "mutation debe dejar un directorio de evidencia"

  if [[ -n "${evidence}" ]]; then
    json="${evidence}/mutation.json"
    declared="$(json_query "${json}" ".source.state" || true)"
    assert_equals "${before}" "${declared}" \
      "la evidencia debe declarar el código de antes de correr PIT"
    assert_equals "false" "$(json_query "${json}" ".source.dirty" || true)" \
      "el árbol estaba limpio al empezar"
    assert_equals "0" "$(json_query "${json}" ".source.changedFiles" || true)" \
      "no había archivos sin commit al empezar"
    assert_json_value "${json}" ".source.stateAlgorithm" \
      "la evidencia debe decir cómo se calculó el state"
    assert_json_value "${json}" ".source.scope" "la evidencia debe decir qué abarca el state"
    assert_equals "source-state.txt" "$(json_query "${json}" ".source.manifest" || true)" \
      "la evidencia debe apuntar al manifiesto"

    if [[ ! -f "${evidence}/source-state.txt" ]]; then
      fail "el manifiesto que respalda el state debe quedar como evidencia"
    else
      # el id publicado tiene que ser recomputable desde el manifiesto (en cmd
      # el archivo lleva CRLF; el state se calcula normalizado a LF)
      recomputed="$(tr -d '\r' < "${evidence}/source-state.txt" \
        | git -C "${dir}" hash-object --stdin)"
      assert_equals "${declared}" "${recomputed}" \
        "el manifiesto conservado debe reproducir el state declarado"
    fi
  fi
}

test_mutation_sin_wrapper_falla_con_2_y_deja_evidencia() {
  local dir evidence json
  dir="$(new_repo)"
  rm -f "${dir}/${API_REL}/mvnw" "${dir}/${API_REL}/mvnw.cmd"
  run_harness "${dir}" mutation
  assert_equals "2" "${HARNESS_RC}" "sin Maven Wrapper mutation debe salir con 2"
  assert_contains "${HARNESS_OUT}" "ERROR: Maven Wrapper not found" "debe explicar qué falta"

  # una corrida que falló antes de Maven tampoco puede quedar sin rastro
  evidence="$(evidence_dir_of "${dir}" mutation)"
  assert_not_empty "${evidence}" "el fallo previo a Maven también deja evidencia"

  if [[ -n "${evidence}" ]]; then
    assert_run_json "${evidence}" "mutation.json" 2
    json="${evidence}/mutation.json"
    assert_equals "FAILED" "$(json_query "${json}" ".result" || true)" \
      "mutation.json debe registrar el fallo"
    if [[ ! -f "${evidence}/command.log" ]]; then
      fail "el motivo del fallo debe quedar en command.log"
    else
      assert_contains "$(tr -d '\r' < "${evidence}/command.log")" "Maven Wrapper not found" \
        "command.log debe explicar por qué no se ejecutó PIT"
    fi
  fi
}

test_verify_y_mutation_no_se_pisan_la_evidencia() {
  local dir verify_dir mutation_dir
  dir="$(new_repo)"
  run_harness "${dir}" verify
  run_harness "${dir}" mutation

  verify_dir="$(evidence_dir_of "${dir}" verify)"
  mutation_dir="$(evidence_dir_of "${dir}" mutation)"

  assert_not_empty "${verify_dir}" "verify debe dejar su evidencia"
  assert_not_empty "${mutation_dir}" "mutation debe dejar la suya"
  assert_not_equals "${verify_dir}" "${mutation_dir}" \
    "dos comandos distintos no pueden compartir directorio de evidencia"

  if [[ -n "${verify_dir}" && -n "${mutation_dir}" ]]; then
    assert_contains "$(basename "${mutation_dir}")" "-mutation" \
      "el nombre del directorio debe decir qué comando lo produjo"
    [[ -f "${verify_dir}/verification.json" ]] \
      || fail "la evidencia de verify debe conservar su documento"
    [[ -f "${mutation_dir}/mutation.json" ]] \
      || fail "la evidencia de mutation debe conservar el suyo"
    [[ ! -f "${mutation_dir}/verification.json" ]] \
      || fail "la evidencia de mutation no puede hacerse pasar por un verify"
  fi
}

test_mutation_con_cambios_locales_lo_deja_visible() {
  local dir evidence json state
  dir="$(new_repo)"
  printf 'class Main { int sinCommit; }\n' > "${dir}/${API_REL}/src/Main.java"

  run_harness "${dir}" mutation
  assert_contains "${HARNESS_OUT}" "DIRTY" "la consola debe avisar de los cambios locales"

  evidence="$(evidence_dir_of "${dir}" mutation)"
  assert_not_empty "${evidence}" "mutation debe dejar un directorio de evidencia"

  if [[ -n "${evidence}" ]]; then
    json="${evidence}/mutation.json"
    assert_equals "true" "$(json_query "${json}" ".source.dirty" || true)" \
      "la evidencia debe declarar el árbol sucio"
    assert_equals "1" "$(json_query "${json}" ".source.changedFiles" || true)" \
      "la evidencia debe contar los archivos sin commit"
    state="$(json_query "${json}" ".source.state" || true)"
    assert_contains "$(basename "${evidence}")" "-dirty-${state:0:7}-mutation" \
      "el nombre del directorio no puede parecer un análisis del commit limpio"
  fi
}

# --- format ------------------------------------------------------------------

test_format_aplica_spotless_con_el_wrapper_de_la_plataforma() {
  local dir
  dir="$(new_repo)"
  run_harness "${dir}" format
  assert_equals "0" "${HARNESS_RC}" "format debe salir con 0 cuando Maven termina bien"
  assert_equals "--batch-mode --no-transfer-progress spotless:apply" "$(mvnw_args_of "${dir}")" \
    "format debe pasar a Maven spotless:apply"
  assert_contains "${HARNESS_OUT}" "FORMAT RESULT: APPLIED" "format debe anunciar el resultado"
}

test_format_falla_con_el_exit_code_de_maven() {
  local dir
  dir="$(new_repo)"
  HARNESS_TEST_MVNW_EXIT=4 run_harness "${dir}" format
  assert_equals "4" "${HARNESS_RC}" "format debe propagar el exit code de Maven"
  assert_contains "${HARNESS_OUT}" "FORMAT RESULT: FAILED" "format debe anunciar el fallo"
}

test_format_sin_wrapper_falla_con_2() {
  local dir
  dir="$(new_repo)"
  rm -f "${dir}/${API_REL}/mvnw" "${dir}/${API_REL}/mvnw.cmd"
  run_harness "${dir}" format
  assert_equals "2" "${HARNESS_RC}" "sin Maven Wrapper format debe salir con 2"
  assert_contains "${HARNESS_OUT}" "ERROR: Maven Wrapper not found" "debe explicar qué falta"
}

# --- verify ------------------------------------------------------------------

test_verify_pasa_y_deja_la_misma_evidencia() {
  local dir evidence duration
  dir="$(new_repo)"
  # Maven "tarda" 2 s: durationSeconds tiene que medirlo de verdad, no ser un
  # 0 que pasa por número válido (harness.cmd lo dejaba en 0 de 00:00 a 09:59)
  HARNESS_TEST_MVNW_SLEEP=2 run_harness "${dir}" verify
  assert_equals "0" "${HARNESS_RC}" "verify debe salir con 0 cuando Maven termina bien"
  assert_equals "--batch-mode --no-transfer-progress clean verify" "$(mvnw_args_of "${dir}")" \
    "verify debe pasar a Maven clean verify"
  assert_contains "${HARNESS_OUT}" "HARNESS RESULT: PASSED" "verify debe anunciar el resultado"

  evidence="$(evidence_dir_of "${dir}")"
  assert_not_empty "${evidence}" "verify debe crear el directorio de evidencia"
  if [[ -n "${evidence}" ]]; then
    assert_verification_json "${evidence}" 0
    assert_equals "PASSED" "$(json_query "${evidence}/verification.json" ".result" || true)" \
      "verification.json debe registrar el resultado"
    duration="$(json_query "${evidence}/verification.json" ".durationSeconds" || true)"
    if ! [[ "${duration}" =~ ^[0-9]+$ ]] || [[ "${duration}" -lt 2 ]]; then
      fail "durationSeconds debe medir la corrida (Maven durmió 2 s; obtenido: '${duration}')"
    fi
    [[ -f "${evidence}/source-state.txt" ]] || fail "la evidencia debe incluir source-state.txt"
    [[ -f "${evidence}/command.log" ]] || fail "la evidencia debe incluir command.log"
    [[ -f "${evidence}/test-reports/pit-reports/index.html" ]] \
      || fail "la evidencia debe copiar target/pit-reports cuando existe"
  fi
}

test_verify_falla_con_el_exit_code_de_maven_y_lo_registra() {
  local dir evidence
  dir="$(new_repo)"
  HARNESS_TEST_MVNW_EXIT=1 run_harness "${dir}" verify
  assert_equals "1" "${HARNESS_RC}" "verify debe propagar el exit code de Maven"
  assert_contains "${HARNESS_OUT}" "HARNESS RESULT: FAILED" "verify debe anunciar el fallo"
  evidence="$(evidence_dir_of "${dir}")"
  assert_not_empty "${evidence}" "verify debe dejar evidencia también cuando falla"
  if [[ -n "${evidence}" ]]; then
    assert_verification_json "${evidence}" 1
    assert_equals "FAILED" "$(json_query "${evidence}/verification.json" ".result" || true)" \
      "verification.json debe registrar el fallo"
  fi
}

test_verify_sin_wrapper_falla_con_2() {
  local dir
  dir="$(new_repo)"
  rm -f "${dir}/${API_REL}/mvnw" "${dir}/${API_REL}/mvnw.cmd"
  run_harness "${dir}" verify
  assert_equals "2" "${HARNESS_RC}" "sin Maven Wrapper verify debe salir con 2"
  assert_contains "${HARNESS_OUT}" "ERROR: Maven Wrapper not found" "debe explicar qué falta"
}

# --- state -------------------------------------------------------------------

test_state_imprime_la_identidad_del_codigo() {
  local dir state
  dir="$(new_repo)"
  run_harness "${dir}" state
  assert_equals "0" "${HARNESS_RC}" "state debe salir con 0"
  assert_contains "${HARNESS_OUT}" '"dirty": false' "state debe reportar el árbol limpio"
  state="$(printf '%s\n' "${HARNESS_OUT}" | sed -n 's/.*"state": "\([0-9a-f]*\)".*/\1/p' | head -1)"
  assert_not_empty "${state}" "state debe imprimir un identificador"
  assert_not_equals "unknown" "${state}" "state no puede quedarse en unknown dentro de un repo git"
}

test_state_manifest_sin_ruta_falla_con_2() {
  local dir
  dir="$(new_repo)"
  run_harness "${dir}" state --manifest
  assert_equals "2" "${HARNESS_RC}" "state --manifest sin ruta debe salir con 2"
  assert_contains "${HARNESS_OUT}" "ERROR: --manifest" "debe explicar que falta la ruta"
}

echo "=================================================="
echo " Harness self-test: command contract (${HARNESS_IMPL})"
echo "=================================================="

run_test test_help_lista_todos_los_comandos_publicos
run_test test_sin_argumentos_muestra_el_help
run_test test_un_comando_desconocido_falla_con_2_y_muestra_el_usage
run_test test_mutation_lanza_pit_con_el_wrapper_de_la_plataforma
run_test test_mutation_deja_evidencia_con_su_propio_documento
run_test test_mutation_registra_el_resultado_y_lo_que_tardo
run_test test_mutation_conserva_el_log_y_los_reportes_de_pit
run_test test_mutation_falla_con_el_exit_code_de_maven
run_test test_mutation_deja_evidencia_cuando_pit_falla
run_test test_mutation_no_adjunta_el_reporte_de_una_corrida_anterior
run_test test_mutation_sin_wrapper_no_adjunta_el_reporte_de_una_corrida_anterior
run_test test_la_evidencia_de_mutation_describe_el_codigo_de_antes_de_correr_pit
run_test test_mutation_sin_wrapper_falla_con_2_y_deja_evidencia
run_test test_verify_y_mutation_no_se_pisan_la_evidencia
run_test test_mutation_con_cambios_locales_lo_deja_visible
run_test test_format_aplica_spotless_con_el_wrapper_de_la_plataforma
run_test test_format_falla_con_el_exit_code_de_maven
run_test test_format_sin_wrapper_falla_con_2
run_test test_verify_pasa_y_deja_la_misma_evidencia
run_test test_verify_falla_con_el_exit_code_de_maven_y_lo_registra
run_test test_verify_sin_wrapper_falla_con_2
run_test test_state_imprime_la_identidad_del_codigo
run_test test_state_manifest_sin_ruta_falla_con_2

finish_suite
