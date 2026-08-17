#!/usr/bin/env bash
#
# Dientes del gate del Harness Self-Test (github-28): el job final que el
# ruleset de main exige. Bash plano, sin dependencias (jq, como el propio gate).

set -Eeuo pipefail

TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${TESTS_DIR}/../.." && pwd)"
GATE_UNDER_TEST="${REPO_ROOT}/.github/scripts/needs_all_succeeded.sh"
WORKFLOW_UNDER_TEST="${REPO_ROOT}/.github/workflows/harness-selftest.yml"

# Runner compartido: contadores, assert_*, run_test, finish_suite.
# shellcheck source=tests/harness/testlib.sh
source "${TESTS_DIR}/testlib.sh"

# Ejecuta el gate con el JSON dado como NEEDS_JSON (la forma de
# ${{ toJSON(needs) }}). Deja el código de salida en GATE_RC y la salida
# (stdout+stderr) en GATE_OUT; tolerante a fallo para que un gate roto sea un
# FAIL legible y no la muerte del runner.
GATE_RC=0
GATE_OUT=""
run_gate() {
  GATE_RC=0
  GATE_OUT="$( NEEDS_JSON="$1" "${GATE_UNDER_TEST}" 2>&1 )" || GATE_RC=$?
}

# needs con la forma real de GitHub: { "<job>": { "result": "...", "outputs": {} } }
needs_json() {
  local json="{" sep="" pair
  for pair in "$@"; do
    json="${json}${sep}\"${pair%%=*}\": {\"result\": \"${pair#*=}\", \"outputs\": {}}"
    sep=", "
  done
  printf '%s}' "${json}"
}

test_todos_los_jobs_en_success_pasa() {
  run_gate "$(needs_json self-test=success state-linux=success \
                         state-windows=success parity=success)"
  assert_equals "0" "${GATE_RC}" "con todos los jobs en success el gate debe pasar"
  assert_contains "${GATE_OUT}" "OK" "el gate debe decir explícitamente que pasó"
}

test_un_job_en_failure_falla_y_lo_nombra() {
  run_gate "$(needs_json self-test=success state-linux=failure \
                         state-windows=success parity=success)"
  assert_equals "1" "${GATE_RC}" "un job en failure debe tumbar el gate"
  # la tabla lista todos los jobs: lo que importa es que el ERROR nombre al culpable
  assert_contains "${GATE_OUT}" "ERROR: state-linux (failure)" \
    "el gate debe nombrar el job que falló y cómo terminó"
}

test_un_job_cancelado_falla() {
  run_gate "$(needs_json self-test=success state-linux=success \
                         state-windows=cancelled parity=success)"
  assert_equals "1" "${GATE_RC}" "un job cancelado no es un job que pasó"
  assert_contains "${GATE_OUT}" "ERROR: state-windows (cancelled)" \
    "el gate debe nombrar el job cancelado"
}

# GitHub reporta un job omitido como Success y NO bloquea el merge aunque sea
# required: por eso el gate tiene que tratarlo como fallo.
test_un_job_omitido_falla() {
  run_gate "$(needs_json self-test=success state-linux=success \
                         state-windows=success parity=skipped)"
  assert_equals "1" "${GATE_RC}" "un job omitido (skipped) debe tumbar el gate aunque el resto pase"
  assert_contains "${GATE_OUT}" "ERROR: parity (skipped)" \
    "el gate debe nombrar el job omitido"
}

# Un gate sin dependencias es un gate mal cableado, no un gate que pasa.
test_sin_jobs_requeridos_falla() {
  run_gate "{}"
  assert_equals "1" "${GATE_RC}" "un needs vacío debe tumbar el gate"
  assert_contains "${GATE_OUT}" "ERROR" "el gate debe explicar que no depende de ningún job"
}

# Nunca "pasar por defecto": sin entrada o con entrada rota, rojo.
test_entrada_ausente_o_invalida_falla() {
  local rc=0 out=""
  out="$( env -u NEEDS_JSON "${GATE_UNDER_TEST}" 2>&1 )" || rc=$?
  assert_equals "1" "${rc}" "sin NEEDS_JSON el gate debe fallar"
  assert_contains "${out}" "ERROR" "sin NEEDS_JSON el gate debe explicar el fallo"

  run_gate "esto no es json"
  assert_equals "1" "${GATE_RC}" "con un NEEDS_JSON inválido el gate debe fallar"

  run_gate '["success"]'
  assert_equals "1" "${GATE_RC}" "con un NEEDS_JSON que no es un objeto el gate debe fallar"
}

# --- lectura del workflow (formato fijo, escrito por nosotros: jobs a 2
# espacios, claves del job a 4, `needs` en una sola línea con corchetes) -----

GATE_JOB_NAME="Harness self-test"

# ids de todos los jobs del workflow, uno por línea
workflow_job_ids() {
  awk '
    /^jobs:[[:space:]]*$/ { injobs = 1; next }
    injobs && /^[^ #]/    { injobs = 0 }
    injobs && /^  [A-Za-z0-9_-]+:[[:space:]]*$/ { sub(/^  /, ""); sub(/:.*/, ""); print }
  ' "${WORKFLOW_UNDER_TEST}"
}

# id del job cuyo `name:` es exactamente $1 (vacío si no existe)
job_id_named() {
  awk -v wanted="$1" '
    /^  [A-Za-z0-9_-]+:[[:space:]]*$/ { job = $0; sub(/^  /, "", job); sub(/:.*/, "", job) }
    /^    name:[[:space:]]*/ {
      value = $0; sub(/^    name:[[:space:]]*/, "", value); sub(/[[:space:]]+$/, "", value)
      if (value == wanted) print job
    }
  ' "${WORKFLOW_UNDER_TEST}"
}

# bloque YAML del job $1 (desde su id hasta el siguiente job)
job_block() {
  awk -v id="$1" '
    $0 == "  " id ":"                              { inblock = 1; print; next }
    inblock && /^  [A-Za-z0-9_-]+:[[:space:]]*$/  { exit }
    inblock                                       { print }
  ' "${WORKFLOW_UNDER_TEST}"
}

# elementos de `needs: [a, b, c]` del job $1, uno por línea
job_needs() {
  job_block "$1" \
    | sed -n 's/^    needs:[[:space:]]*\[\(.*\)\][[:space:]]*$/\1/p' \
    | tr ',' '\n' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//' | sed '/^$/d'
}

test_el_workflow_tiene_un_job_final_con_nombre_estable() {
  local id
  id="$(job_id_named "${GATE_JOB_NAME}")"
  assert_not_empty "${id}" \
    "el workflow debe tener un job cuyo name sea exactamente '${GATE_JOB_NAME}' (es el contexto que exige el ruleset)"
}

test_el_gate_corre_siempre_aunque_algo_falle_o_se_cancele() {
  local id block
  id="$(job_id_named "${GATE_JOB_NAME}")"
  block="$(job_block "${id}")"
  assert_contains "${block}" "if: always()" \
    "el gate debe declarar if: always(): con !cancelled() quedaría omitido tras una cancelación y GitHub lo contaría como Success"
}

test_el_gate_depende_de_todos_los_demas_jobs() {
  local id expected actual
  id="$(job_id_named "${GATE_JOB_NAME}")"
  expected="$(workflow_job_ids | grep -vx "${id}" | LC_ALL=C sort | tr '\n' ' ')"
  actual="$(job_needs "${id}" | LC_ALL=C sort | tr '\n' ' ')"
  assert_not_empty "${actual}" "el gate debe declarar needs"
  assert_equals "${expected}" "${actual}" \
    "el needs del gate debe listar exactamente todos los demás jobs del workflow"
}

test_el_gate_ejecuta_el_script_versionado() {
  local id block
  id="$(job_id_named "${GATE_JOB_NAME}")"
  block="$(job_block "${id}")"
  assert_contains "${block}" ".github/scripts/needs_all_succeeded.sh" \
    "el gate debe ejecutar el script versionado, que es lo que esta suite prueba"
  assert_contains "${block}" 'NEEDS_JSON: ${{ toJSON(needs) }}' \
    "el gate debe pasar toJSON(needs) al script vía NEEDS_JSON"
}

echo "=================================================="
echo " Harness self-test: merge gate"
echo "=================================================="

run_test test_todos_los_jobs_en_success_pasa
run_test test_un_job_en_failure_falla_y_lo_nombra
run_test test_un_job_cancelado_falla
run_test test_un_job_omitido_falla
run_test test_sin_jobs_requeridos_falla
run_test test_entrada_ausente_o_invalida_falla
run_test test_el_workflow_tiene_un_job_final_con_nombre_estable
run_test test_el_gate_corre_siempre_aunque_algo_falle_o_se_cancele
run_test test_el_gate_depende_de_todos_los_demas_jobs
run_test test_el_gate_ejecuta_el_script_versionado

finish_suite
