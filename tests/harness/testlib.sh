#!/usr/bin/env bash
#
# Runner compartido por las suites de tests/harness (bash plano, sin
# dependencias). Se carga con `source`; cada suite define sus tests, los lanza
# con run_test y termina con finish_suite.
#
# Extraído de state_test.sh en github-28 al aparecer la segunda suite.

TESTS_RUN=0
TESTS_PASSED=0
TESTS_FAILED=0
TESTS_SKIPPED=0

# aserciones fallidas: un test puede acumular varias, pero cuenta como uno solo
ASSERT_FAILURES=0

# Raíz única de temporales: los helpers que crean fixtures suelen correr en una
# subshell (sustitución de comandos), así que no pueden poblar un array del
# shell padre.
TEST_TMP_ROOT="$(mktemp -d)"

cleanup() {
  rm -rf "${TEST_TMP_ROOT}"
  return 0
}
trap cleanup EXIT

fail() {
  ASSERT_FAILURES=$((ASSERT_FAILURES + 1))
  echo "  FAIL: $1"
}

# Un test omitido no es un test que pasa: se cuenta aparte para que el resumen
# no prometa cobertura que no hubo.
skip() {
  TESTS_SKIPPED=$((TESTS_SKIPPED + 1))
  echo "  SKIP: $1"
}

assert_equals() {
  local expected="$1" actual="$2" message="$3"
  if [[ "${expected}" != "${actual}" ]]; then
    fail "${message} (esperado: '${expected}', obtenido: '${actual}')"
  fi
}

assert_not_equals() {
  local unexpected="$1" actual="$2" message="$3"
  if [[ "${unexpected}" == "${actual}" ]]; then
    fail "${message} (ambos: '${actual}')"
  fi
}

assert_not_empty() {
  local value="$1" message="$2"
  if [[ -z "${value}" ]]; then
    fail "${message} (vacío)"
  fi
}

assert_contains() {
  local haystack="$1" needle="$2" message="$3"
  if [[ "${haystack}" != *"${needle}"* ]]; then
    fail "${message} (no contiene '${needle}')"
  fi
}

run_test() {
  local name="$1"
  local failures_before="${ASSERT_FAILURES}"
  local skipped_before="${TESTS_SKIPPED}"

  TESTS_RUN=$((TESTS_RUN + 1))
  echo "- ${name}"
  "${name}"

  if [[ "${ASSERT_FAILURES}" -gt "${failures_before}" ]]; then
    TESTS_FAILED=$((TESTS_FAILED + 1))
  elif [[ "${TESTS_SKIPPED}" -eq "${skipped_before}" ]]; then
    TESTS_PASSED=$((TESTS_PASSED + 1))
  fi
}

# Directorio de evidencia de la única corrida del repo temporal $1 (vacío si no
# hay ninguna). El `|| true` importa: si artifacts/harness no existe, find sale
# con 1 y, en una asignación bajo pipefail, mataría el runner en vez de dejar
# un FAIL legible.
evidence_dir_of() {
  find "$1/artifacts/harness" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | head -1 || true
}

# Imprime el resumen y termina con 1 si algún test falló.
finish_suite() {
  local summary
  summary="${TESTS_PASSED} passed, ${TESTS_FAILED} failed, ${TESTS_SKIPPED} skipped"
  summary="${summary} (${TESTS_RUN} tests, ${ASSERT_FAILURES} assertion failure(s))"

  echo
  if [[ "${TESTS_FAILED}" -gt 0 ]]; then
    echo "SELF-TEST RESULT: FAILED - ${summary}"
    exit 1
  fi

  echo "SELF-TEST RESULT: PASSED - ${summary}"
}
