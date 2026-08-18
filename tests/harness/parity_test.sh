#!/usr/bin/env bash
#
# Paridad estática entre ./harness y harness.cmd (github-30): lee los dos
# scripts SIN ejecutarlos y comprueba que exponen el mismo contrato público
# (comandos, help, invocación de Maven). Detecta que se añada o quite un
# comando en una implementación y no en la otra. La paridad de comportamiento
# la ejecuta tests/harness/contract_test.sh en cada plataforma.

set -Eeuo pipefail

TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${TESTS_DIR}/../.." && pwd)"
BASH_SCRIPT="${REPO_ROOT}/harness"
CMD_SCRIPT="${REPO_ROOT}/harness.cmd"

# Runner compartido: contadores, assert_*, run_test, finish_suite.
# shellcheck source=tests/harness/testlib.sh
source "${TESTS_DIR}/testlib.sh"

# El contrato mínimo que exige el issue; el resto se compara entre sí.
MINIMUM_COMMANDS="format help mutation state verify"

# --- lectura de ./harness ----------------------------------------------------

# comandos que despacha el `case "${COMMAND}"` (los alias a|b se separan)
bash_dispatched_commands() {
  awk '
    /^case "\$\{COMMAND\}" in/ { incase = 1; next }
    incase && /^esac/          { exit }
    incase && /^  [^ )]+\)$/    { sub(/^  /, ""); sub(/\)$/, ""); gsub(/\|/, "\n"); print }
  ' "${BASH_SCRIPT}" | grep -v '^\*$' | LC_ALL=C sort -u
}

# texto del help tal cual lo imprime show_usage
bash_help_text() {
  awk '
    /^show_usage\(\)/       { infn = 1; next }
    infn && /^  cat <<.EOF.$/ { intext = 1; next }
    infn && intext && /^EOF$/ { exit }
    infn && intext            { print }
  ' "${BASH_SCRIPT}"
}

# --- lectura de harness.cmd --------------------------------------------------

# comandos que despacha la cabecera: if "%~1"=="X" goto Y
cmd_dispatched_commands() {
  sed -n 's/^if \(\/I \)\{0,1\}"%~1"=="\([^"]\{1,\}\)" goto .*/\2/p' "${CMD_SCRIPT}" \
    | LC_ALL=C sort -u
}

# texto del help: los `echo` de la subrutina :print_help, sin escapes ^
cmd_help_text() {
  awk '
    /^:print_help$/              { inhelp = 1; next }
    inhelp && /^exit \/b/        { exit }
    inhelp && /^goto /           { exit }
    inhelp && /^echo\.$/         { print ""; next }
    inhelp && /^echo /           { sub(/^echo /, ""); print }
  ' "${CMD_SCRIPT}" | sed 's/\^\(.\)/\1/g'
}

# --- helpers comunes ---------------------------------------------------------

# comandos que aparecen en las líneas "Usage:" de un texto de help ($1 = programa)
help_usage_commands() {
  local text="$1" program="$2"
  printf '%s\n' "${text}" | sed -n "s|^  ${program} \([a-z-]\{1,\}\)$|\1|p" | LC_ALL=C sort -u
}

as_line() { tr '\n' ' ' | sed 's/ $//'; }

# --- tests -------------------------------------------------------------------

test_ambos_despachan_el_mismo_conjunto_de_comandos() {
  local bash_cmds cmd_cmds cmd
  bash_cmds="$(bash_dispatched_commands | as_line)"
  cmd_cmds="$(cmd_dispatched_commands | as_line)"

  assert_not_empty "${bash_cmds}" "no se pudo leer el despacho de ./harness"
  assert_not_empty "${cmd_cmds}" "no se pudo leer el despacho de harness.cmd"
  assert_equals "${bash_cmds}" "${cmd_cmds}" \
    "./harness y harness.cmd deben despachar exactamente los mismos comandos"

  for cmd in ${MINIMUM_COMMANDS}; do
    assert_contains " ${bash_cmds} " " ${cmd} " "./harness debe despachar '${cmd}'"
    assert_contains " ${cmd_cmds} " " ${cmd} " "harness.cmd debe despachar '${cmd}'"
  done
}

# alias que se despachan pero no se anuncian en el usage
HELP_ALIASES="--help -h"

without_aliases() {
  local alias
  local list=" $(cat | as_line) "
  for alias in ${HELP_ALIASES}; do list="${list// ${alias} / }"; done
  printf '%s\n' "${list}" | sed 's/^ *//; s/ *$//'
}

test_el_help_de_cada_script_lista_exactamente_lo_que_despacha() {
  local bash_dispatch cmd_dispatch bash_help cmd_help
  bash_dispatch="$(bash_dispatched_commands | without_aliases)"
  cmd_dispatch="$(cmd_dispatched_commands | without_aliases)"
  bash_help="$(help_usage_commands "$(bash_help_text)" "./harness" | as_line)"
  cmd_help="$(help_usage_commands "$(cmd_help_text)" "harness.cmd" | as_line)"

  assert_not_empty "${bash_help}" "no se pudo leer el usage de ./harness"
  assert_not_empty "${cmd_help}" "no se pudo leer el usage de harness.cmd"
  assert_equals "${bash_dispatch}" "${bash_help}" \
    "el help de ./harness debe listar exactamente los comandos que despacha"
  assert_equals "${cmd_dispatch}" "${cmd_help}" \
    "el help de harness.cmd debe listar exactamente los comandos que despacha"
}

test_el_texto_del_help_es_el_mismo_modulo_el_nombre_del_programa() {
  local bash_help cmd_help
  bash_help="$(bash_help_text)"
  cmd_help="$(cmd_help_text | sed 's|harness\.cmd|./harness|g')"

  assert_not_empty "${bash_help}" "no se pudo leer el help de ./harness"
  if [[ "${bash_help}" != "${cmd_help}" ]]; then
    fail "el help debe ser idéntico en los dos scripts (módulo ./harness vs harness.cmd)"
    # diff sale con 1 cuando difieren: sin el || true, pipefail tumbaría el runner
    diff <(printf '%s\n' "${bash_help}") <(printf '%s\n' "${cmd_help}") \
      | sed 's/^/    /' | head -20 || true
  fi
}

# argumentos con los que ./harness invoca ./mvnw dentro de la función que
# atiende al comando $1 (líneas continuadas con \ unidas)
bash_maven_args() {
  local fn
  fn="$(awk -v cmd="$1" '
    /^case "\$\{COMMAND\}" in/ { incase = 1; next }
    incase && $0 == "  " cmd ")" { getline; sub(/^ +/, ""); print; exit }
  ' "${BASH_SCRIPT}")"
  [[ -n "${fn}" ]] || return 0
  awk -v fn="${fn}" '
    $0 == fn "() {"                  { infn = 1; next }
    infn && /^}/                     { exit }
    infn && /^ *\.\/mvnw( |$)/     { collecting = 1; sub(/^ *\.\/mvnw */, "") }
    collecting {
      line = $0; cont = (line ~ /\\$/); sub(/ *\\$/, "", line)
      n = split(line, t, /[ \t]+/); for (i = 1; i <= n; i++) if (t[i] != "") printf "%s ", t[i]
      if (!cont) { print ""; exit }
    }
  ' "${BASH_SCRIPT}" | sed 's/ $//'
}

# argumentos con los que harness.cmd invoca mvnw.cmd en la etiqueta del comando $1
cmd_maven_args() {
  awk -v cmd="$1" '
    $0 == ":" cmd                    { inlabel = 1; next }
    inlabel && /^:[A-Za-z_]+$/       { exit }
    inlabel && /^ *call mvnw\.cmd /  { sub(/^ *call mvnw\.cmd */, ""); sub(/ *[0-9]?> .*$/, ""); print; exit }
  ' "${CMD_SCRIPT}" | sed 's/[[:space:]]*$//'
}

test_cada_comando_invoca_maven_con_los_mismos_argumentos() {
  local cmd bash_args cmd_args
  for cmd in verify format mutation; do
    bash_args="$(bash_maven_args "${cmd}")"
    cmd_args="$(cmd_maven_args "${cmd}")"
    assert_not_empty "${bash_args}" "./harness ${cmd} debe invocar ./mvnw"
    assert_not_empty "${cmd_args}" "harness.cmd ${cmd} debe invocar mvnw.cmd"
    assert_equals "${bash_args}" "${cmd_args}" \
      "'${cmd}' debe pasar a Maven los mismos argumentos en las dos plataformas"
  done
}

test_cada_script_usa_el_wrapper_de_su_plataforma() {
  if grep -q 'mvnw\.cmd' "${BASH_SCRIPT}"; then
    fail "./harness no debe referirse a mvnw.cmd"
  fi
  if grep -qE '(^|[^.a-z])mvnw([^.]|$)' "${CMD_SCRIPT}"; then
    fail "harness.cmd no debe invocar el mvnw de bash (solo mvnw.cmd)"
  fi
}

echo "=================================================="
echo " Harness self-test: bash/cmd static parity"
echo "=================================================="

run_test test_ambos_despachan_el_mismo_conjunto_de_comandos
run_test test_el_help_de_cada_script_lista_exactamente_lo_que_despacha
run_test test_el_texto_del_help_es_el_mismo_modulo_el_nombre_del_programa
run_test test_cada_comando_invoca_maven_con_los_mismos_argumentos
run_test test_cada_script_usa_el_wrapper_de_su_plataforma

finish_suite
