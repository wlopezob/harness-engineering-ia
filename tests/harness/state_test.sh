#!/usr/bin/env bash
#
# Dientes del cálculo de identidad del código verificado (github-26).
# Bash plano, sin dependencias: cada caso corre sobre un repo git temporal.

set -Eeuo pipefail

TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${TESTS_DIR}/../.." && pwd)"
HARNESS_UNDER_TEST="${REPO_ROOT}/harness"

TESTS_RUN=0
TESTS_PASSED=0
TESTS_FAILED=0
TESTS_SKIPPED=0

# aserciones fallidas: un test puede acumular varias, pero cuenta como uno solo
ASSERT_FAILURES=0

# Raíz única de temporales: new_repo corre en una subshell (sustitución de
# comandos), así que no puede poblar un array del shell padre.
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

# Crea un repo git temporal con el harness bajo prueba y un commit inicial.
new_repo() {
  local dir
  dir="$(mktemp -d "${TEST_TMP_ROOT}/repo.XXXXXX")"

  mkdir -p "${dir}/orders-platform/apps/api/src"
  printf 'class Main {}\n' > "${dir}/orders-platform/apps/api/src/Main.java"
  printf '/artifacts/\ntarget/\n' > "${dir}/.gitignore"
  cp "${HARNESS_UNDER_TEST}" "${dir}/harness"
  chmod +x "${dir}/harness"

  git -C "${dir}" init --quiet
  git -C "${dir}" config user.email "harness@test.local"
  git -C "${dir}" config user.name "harness test"
  git -C "${dir}" add -A
  git -C "${dir}" commit --quiet -m "init"

  printf '%s' "${dir}"
}

assert_contains() {
  local haystack="$1" needle="$2" message="$3"
  if [[ "${haystack}" != *"${needle}"* ]]; then
    fail "${message} (no contiene '${needle}')"
  fi
}

# Igual que new_repo pero con un mvnw de mentira: verify debe poder correr en
# milisegundos, sin construir el backend de verdad.
new_repo_with_maven_stub() {
  local dir
  dir="$(mktemp -d "${TEST_TMP_ROOT}/repo.XXXXXX")"

  mkdir -p "${dir}/orders-platform/apps/api/src"
  printf 'class Main {}\n' > "${dir}/orders-platform/apps/api/src/Main.java"
  printf '#!/usr/bin/env bash\necho "stub maven"\nexit 0\n' \
    > "${dir}/orders-platform/apps/api/mvnw"
  chmod +x "${dir}/orders-platform/apps/api/mvnw"
  printf '/artifacts/\ntarget/\n' > "${dir}/.gitignore"
  cp "${HARNESS_UNDER_TEST}" "${dir}/harness"
  chmod +x "${dir}/harness"

  git -C "${dir}" init --quiet
  git -C "${dir}" config user.email "harness@test.local"
  git -C "${dir}" config user.name "harness test"
  git -C "${dir}" add -A
  git -C "${dir}" commit --quiet -m "init"

  printf '%s' "${dir}"
}

# Lee un campo de un archivo JSON ya escrito.
json_field() {
  local file="$1" field="$2"
  sed -n "s/.*\"${field}\": *\"\{0,1\}\([^\",}]*\)\"\{0,1\},\{0,1\}$/\1/p" \
    "${file}" | head -1
}

# Directorio de evidencia de la única corrida del repo temporal.
evidence_dir_of() {
  find "$1/artifacts/harness" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | head -1
}

# Lee un campo del JSON que imprime `./harness state`.
state_field() {
  local dir="$1" field="$2" output=""

  # tolerante a fallo: si el comando revienta, el campo sale vacío y el assert
  # reporta un FAIL legible en vez de matar el runner a mitad de la suite
  output="$( cd "${dir}" && ./harness state 2>&1 )" || output=""

  printf '%s\n' "${output}" \
    | sed -n "s/.*\"${field}\": *\"\{0,1\}\([^\",}]*\)\"\{0,1\},\{0,1\}$/\1/p" \
    | head -1
}

test_arbol_limpio_reporta_no_dirty_y_un_state() {
  local dir
  dir="$(new_repo)"

  assert_equals "false" "$(state_field "${dir}" dirty)" "un repo recién commiteado no está sucio"
  assert_not_empty "$(state_field "${dir}" state)" "el state debe tener valor"
}


test_dos_invocaciones_sin_cambios_dan_el_mismo_state() {
  local dir first second
  dir="$(new_repo)"

  first="$(state_field "${dir}" state)"
  second="$(state_field "${dir}" state)"

  assert_equals "${first}" "${second}" "el state no puede depender del momento de ejecución"
}

test_modificar_un_archivo_tracked_cambia_el_state() {
  local dir before after
  dir="$(new_repo)"
  before="$(state_field "${dir}" state)"

  printf 'class Main { int x; }\n' > "${dir}/orders-platform/apps/api/src/Main.java"
  after="$(state_field "${dir}" state)"

  assert_not_empty "${after}" "el comando debe seguir funcionando tras el cambio"
  assert_not_equals "${before}" "${after}" "modificar código debe cambiar el state"
  assert_equals "true" "$(state_field "${dir}" dirty)" "el árbol quedó sucio"
}

test_un_archivo_untracked_nuevo_cambia_el_state() {
  local dir before after
  dir="$(new_repo)"
  before="$(state_field "${dir}" state)"

  # un archivo nuevo sin git add también forma parte de la implementación
  printf 'class Nuevo {}\n' > "${dir}/orders-platform/apps/api/src/Nuevo.java"
  after="$(state_field "${dir}" state)"

  assert_not_empty "${after}" "el comando debe seguir funcionando con un untracked nuevo"
  assert_not_equals "${before}" "${after}" "un untracked nuevo debe cambiar el state"
  assert_equals "true" "$(state_field "${dir}" dirty)" "el árbol quedó sucio"
}

test_un_archivo_ignorado_no_cambia_el_state() {
  local dir before after
  dir="$(new_repo)"
  before="$(state_field "${dir}" state)"

  # esto es lo que genera la propia verificación: no puede alterar la identidad
  mkdir -p "${dir}/artifacts/harness/run-1" "${dir}/orders-platform/apps/api/target"
  printf 'evidencia\n' > "${dir}/artifacts/harness/run-1/command.log"
  printf 'clases\n' > "${dir}/orders-platform/apps/api/target/salida.txt"
  after="$(state_field "${dir}" state)"

  assert_equals "${before}" "${after}" "los archivos ignorados no forman parte del código verificado"
  assert_equals "false" "$(state_field "${dir}" dirty)" "los ignorados no ensucian el árbol"
}

test_borrar_un_archivo_tracked_cambia_el_state() {
  local dir before after
  dir="$(new_repo)"
  before="$(state_field "${dir}" state)"

  rm "${dir}/orders-platform/apps/api/src/Main.java"
  after="$(state_field "${dir}" state)"

  assert_not_empty "${after}" "el comando debe seguir funcionando tras un borrado"
  assert_not_equals "${before}" "${after}" "un borrado debe cambiar el state"
}

test_revertir_el_cambio_devuelve_el_state_original() {
  local dir before after reverted
  dir="$(new_repo)"
  before="$(state_field "${dir}" state)"

  printf 'temporal\n' > "${dir}/orders-platform/apps/api/src/Temp.java"
  after="$(state_field "${dir}" state)"
  rm "${dir}/orders-platform/apps/api/src/Temp.java"
  reverted="$(state_field "${dir}" state)"

  assert_not_empty "${after}" "el comando debe seguir funcionando con el archivo temporal"
  assert_not_equals "${before}" "${after}" "el cambio intermedio se vio"
  assert_equals "${before}" "${reverted}" "mismo código = mismo state"
}

test_mismo_contenido_en_otro_commit_da_el_mismo_state() {
  local dir before after
  dir="$(new_repo)"
  before="$(state_field "${dir}" state)"

  # un commit vacío cambia HEAD pero no el contenido del árbol
  git -C "${dir}" commit --quiet --allow-empty -m "otro commit"
  after="$(state_field "${dir}" state)"

  assert_equals "${before}" "${after}" "el state identifica código, no historia"
}

test_calcular_el_state_no_modifica_el_repositorio() {
  local dir status_before status_after objects_before objects_after
  dir="$(new_repo)"

  status_before="$(git -C "${dir}" status --porcelain --untracked-files=all)"
  objects_before="$(find "${dir}/.git/objects" -type f | wc -l | tr -d ' ')"

  ( cd "${dir}" && ./harness state > /dev/null )

  status_after="$(git -C "${dir}" status --porcelain --untracked-files=all)"
  objects_after="$(find "${dir}/.git/objects" -type f | wc -l | tr -d ' ')"

  assert_equals "${status_before}" "${status_after}" "el working tree no puede cambiar"
  assert_equals "${objects_before}" "${objects_after}" "no puede escribir objetos en .git"
}

test_verify_registra_la_identidad_del_codigo_en_la_evidencia() {
  local dir evidence json
  dir="$(new_repo_with_maven_stub)"

  ( cd "${dir}" && ./harness verify > /dev/null 2>&1 ) || true

  evidence="$(evidence_dir_of "${dir}")"
  assert_not_empty "${evidence}" "verify debe crear un directorio de evidencia"
  json="${evidence}/verification.json"

  assert_equals "1.1" "$(json_field "${json}" schemaVersion)" "el esquema sube a 1.1"
  assert_equals "false" "$(json_field "${json}" dirty)" "el árbol estaba limpio"
  assert_not_empty "$(json_field "${json}" state)" "la evidencia lleva el state"
  assert_not_empty "$(json_field "${json}" commit)" "la evidencia conserva el commit base"
  if [[ ! -f "${evidence}/source-state.txt" ]]; then
    fail "el manifiesto que respalda el state debe quedar como evidencia"
  fi
}

test_verify_con_cambios_locales_lo_deja_visible() {
  local dir evidence output state
  dir="$(new_repo_with_maven_stub)"
  printf 'class Main { int sinCommit; }\n' > "${dir}/orders-platform/apps/api/src/Main.java"

  output="$( cd "${dir}" && ./harness verify 2>&1 )" || true

  evidence="$(evidence_dir_of "${dir}")"
  assert_not_empty "${evidence}" "verify debe crear un directorio de evidencia"
  assert_equals "true" "$(json_field "${evidence}/verification.json" dirty)" \
    "la evidencia debe declarar el árbol sucio"

  state="$(json_field "${evidence}/verification.json" state)"
  assert_contains "$(basename "${evidence}")" "-dirty-${state:0:7}" \
    "el nombre del directorio no puede parecer una verificación del commit limpio"
  assert_contains "${output}" "DIRTY" "la consola debe avisar de los cambios locales"
}

test_bash_y_cmd_declaran_la_misma_identidad() {
  local bash_algo cmd_algo bash_scope cmd_scope marker
  local cmd_file="${REPO_ROOT}/harness.cmd"

  bash_algo="$(sed -n 's/^SOURCE_STATE_ALGORITHM="\(.*\)"$/\1/p' "${HARNESS_UNDER_TEST}")"
  cmd_algo="$(sed -n 's/^set "SOURCE_STATE_ALGORITHM=\(.*\)"$/\1/p' "${cmd_file}")"
  bash_scope="$(sed -n 's/^SOURCE_STATE_SCOPE="\(.*\)"$/\1/p' "${HARNESS_UNDER_TEST}")"
  cmd_scope="$(sed -n 's/^set "SOURCE_STATE_SCOPE=\(.*\)"$/\1/p' "${cmd_file}")"

  assert_not_empty "${bash_algo}" "./harness debe declarar su algoritmo"
  assert_equals "${bash_algo}" "${cmd_algo}" "ambos scripts deben declarar el mismo algoritmo"
  assert_equals "${bash_scope}" "${cmd_scope}" "ambos scripts deben declarar el mismo alcance"

  # %~dp0 termina en barra invertida y `git -C "C:\ruta\"` rompe el argumento:
  # git no recibe la ruta y todo queda en "unknown" (lo cazó el job Windows)
  if ! grep -q 'ROOT_DIR:~0,-1' "${cmd_file}"; then
    fail "harness.cmd debe quitar la barra final de ROOT_DIR antes de usarlo con git -C"
  fi

  # los comandos que fijan el resultado tienen que estar en las dos
  # implementaciones: la paridad real la mide el workflow harness-selftest
  for marker in \
    "ls-files --cached --deduplicate" \
    "ls-files --others --exclude-standard" \
    "hash-object"; do
    if ! grep -q -- "${marker}" "${HARNESS_UNDER_TEST}"; then
      fail "./harness debería usar '${marker}'"
    fi
    if ! grep -q -- "${marker}" "${cmd_file}"; then
      fail "harness.cmd debería usar '${marker}'"
    fi
  done
}

test_state_puede_volcar_el_manifiesto_que_respalda_el_id() {
  local dir manifest state recomputed
  dir="$(new_repo)"
  manifest="${dir}/manifiesto.txt"

  state="$( cd "${dir}" && ./harness state --manifest "${manifest}" \
    | sed -n 's/.*"state": "\([0-9a-f]*\)".*/\1/p' | head -1 )"

  if [[ ! -f "${manifest}" ]]; then
    fail "state --manifest debe volcar el manifiesto"
    return
  fi

  # el id publicado tiene que ser reproducible desde el manifiesto volcado
  recomputed="$(git -C "${dir}" hash-object --stdin < "${manifest}")"
  assert_equals "${state}" "${recomputed}" "el manifiesto debe reproducir el state"
}

test_el_state_no_depende_del_fin_de_linea_en_disco() {
  local dir con_crlf con_lf
  dir="$(new_repo)"

  # mismo contenido logico, distinto fin de linea: es lo que ocurre entre un
  # checkout de Windows y uno de Linux, y tambien con archivos que git guarda
  # con CRLF (mvnw.cmd). El identificador no puede depender de eso.
  printf 'linea uno\r\nlinea dos\r\n' > "${dir}/orders-platform/apps/api/src/Eol.java"
  con_crlf="$(state_field "${dir}" state)"

  printf 'linea uno\nlinea dos\n' > "${dir}/orders-platform/apps/api/src/Eol.java"
  con_lf="$(state_field "${dir}" state)"

  assert_not_empty "${con_crlf}" "el comando debe funcionar con CRLF"
  assert_equals "${con_crlf}" "${con_lf}" \
    "CRLF y LF del mismo contenido deben dar el mismo state"
}

test_el_state_no_cambia_al_indexar_un_archivo_sin_tocarlo() {
  local dir before after nuevo
  dir="$(new_repo)"

  # path que ordena ENTRE dos tracked (.../src/Alpha.java va despues de
  # "harness" y antes de ".../src/Main.java"): si el manifiesto agrupa por
  # estado del indice en vez de ordenar globalmente, indexarlo lo mueve de sitio
  nuevo="orders-platform/apps/api/src/Alpha.java"
  printf 'class Alpha {}\n' > "${dir}/${nuevo}"
  before="$(state_field "${dir}" state)"

  git -C "${dir}" add "${nuevo}"
  after="$(state_field "${dir}" state)"

  assert_not_empty "${before}" "el comando debe funcionar con el archivo untracked"
  assert_not_empty "${after}" "el comando debe funcionar con el archivo indexado"
  assert_equals "${before}" "${after}" \
    "git add no cambia el contenido verificable: el state no puede cambiar"
}

test_el_state_no_depende_del_locale_de_quien_lo_ejecuta() {
  local dir con_c con_otro otro_locale="en_US.UTF-8"

  dir="$(new_repo)"
  # nombres que exponen la collation: en C, "Zeta" va antes que "alpha";
  # en un locale case-insensitive, al reves
  printf 'class Zeta {}\n' > "${dir}/orders-platform/apps/api/src/Zeta.java"
  printf 'class alpha {}\n' > "${dir}/orders-platform/apps/api/src/alpha.java"

  con_c="$( cd "${dir}" && LC_ALL=C ./harness state 2>&1 \
    | sed -n 's/.*"state": "\([0-9a-f]*\)".*/\1/p' | head -1 )"
  assert_not_empty "${con_c}" "el comando debe funcionar bajo LC_ALL=C"

  # sin `grep -q`: con pipefail, grep cerraria el pipe, `locale` moriria de
  # SIGPIPE y el pipeline se daria por fallido, omitiendo el test en silencio
  if ! locale -a 2>/dev/null | grep -ix "${otro_locale}" > /dev/null; then
    skip "${otro_locale} no esta disponible en esta maquina"
    return
  fi

  con_otro="$( cd "${dir}" && LC_ALL="${otro_locale}" ./harness state 2>&1 \
    | sed -n 's/.*"state": "\([0-9a-f]*\)".*/\1/p' | head -1 )"

  assert_equals "${con_c}" "${con_otro}" \
    "el orden del manifiesto no puede depender del locale del entorno"
}

echo "=================================================="
echo " Harness self-test: source state"
echo "=================================================="

run_test test_arbol_limpio_reporta_no_dirty_y_un_state
run_test test_dos_invocaciones_sin_cambios_dan_el_mismo_state
run_test test_modificar_un_archivo_tracked_cambia_el_state
run_test test_un_archivo_untracked_nuevo_cambia_el_state
run_test test_un_archivo_ignorado_no_cambia_el_state
run_test test_borrar_un_archivo_tracked_cambia_el_state
run_test test_revertir_el_cambio_devuelve_el_state_original
run_test test_mismo_contenido_en_otro_commit_da_el_mismo_state
run_test test_calcular_el_state_no_modifica_el_repositorio
run_test test_verify_registra_la_identidad_del_codigo_en_la_evidencia
run_test test_verify_con_cambios_locales_lo_deja_visible
run_test test_bash_y_cmd_declaran_la_misma_identidad
run_test test_state_puede_volcar_el_manifiesto_que_respalda_el_id
run_test test_el_state_no_depende_del_fin_de_linea_en_disco
run_test test_el_state_no_cambia_al_indexar_un_archivo_sin_tocarlo
run_test test_el_state_no_depende_del_locale_de_quien_lo_ejecuta

echo
SUMMARY="${TESTS_PASSED} passed, ${TESTS_FAILED} failed, ${TESTS_SKIPPED} skipped"
SUMMARY="${SUMMARY} (${TESTS_RUN} tests, ${ASSERT_FAILURES} assertion failure(s))"

if [[ "${TESTS_FAILED}" -gt 0 ]]; then
  echo "SELF-TEST RESULT: FAILED - ${SUMMARY}"
  exit 1
fi

echo "SELF-TEST RESULT: PASSED - ${SUMMARY}"
