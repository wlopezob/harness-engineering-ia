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
# haría PIT) y salen con HARNESS_TEST_MVNW_EXIT (0 por defecto).
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
mkdir -p "${here}/target/pit-reports"
printf '<html/>\n' > "${here}/target/pit-reports/index.html"
exit "${HARNESS_TEST_MVNW_EXIT:-0}"
STUB
  chmod +x "${api}/mvnw"

  # CRLF: es un .cmd de verdad para cmd.exe
  printf '%s\r\n' \
    '@echo off' \
    'echo %* > "%~dp0mvnw-args.txt"' \
    'if not exist "%~dp0target\pit-reports" mkdir "%~dp0target\pit-reports"' \
    'echo ^<html/^> > "%~dp0target\pit-reports\index.html"' \
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

test_mutation_sin_wrapper_falla_con_2() {
  local dir
  dir="$(new_repo)"
  rm -f "${dir}/${API_REL}/mvnw" "${dir}/${API_REL}/mvnw.cmd"
  run_harness "${dir}" mutation
  assert_equals "2" "${HARNESS_RC}" "sin Maven Wrapper mutation debe salir con 2"
  assert_contains "${HARNESS_OUT}" "ERROR: Maven Wrapper not found" "debe explicar qué falta"
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
  local dir evidence
  dir="$(new_repo)"
  run_harness "${dir}" verify
  assert_equals "0" "${HARNESS_RC}" "verify debe salir con 0 cuando Maven termina bien"
  assert_equals "--batch-mode --no-transfer-progress clean verify" "$(mvnw_args_of "${dir}")" \
    "verify debe pasar a Maven clean verify"
  assert_contains "${HARNESS_OUT}" "HARNESS RESULT: PASSED" "verify debe anunciar el resultado"

  evidence="$(evidence_dir_of "${dir}")"
  assert_not_empty "${evidence}" "verify debe crear el directorio de evidencia"
  if [[ -n "${evidence}" ]]; then
    assert_contains "$(tr -d '\r' < "${evidence}/verification.json")" '"result": "PASSED"' \
      "verification.json debe registrar el resultado"
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
    assert_contains "$(tr -d '\r' < "${evidence}/verification.json")" '"result": "FAILED"' \
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
run_test test_mutation_falla_con_el_exit_code_de_maven
run_test test_mutation_sin_wrapper_falla_con_2
run_test test_format_aplica_spotless_con_el_wrapper_de_la_plataforma
run_test test_format_falla_con_el_exit_code_de_maven
run_test test_format_sin_wrapper_falla_con_2
run_test test_verify_pasa_y_deja_la_misma_evidencia
run_test test_verify_falla_con_el_exit_code_de_maven_y_lo_registra
run_test test_verify_sin_wrapper_falla_con_2
run_test test_state_imprime_la_identidad_del_codigo
run_test test_state_manifest_sin_ruta_falla_con_2

finish_suite
