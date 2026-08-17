#!/usr/bin/env bash
#
# Gate del Harness Self-Test (github-28).
#
# Pasa (exit 0) solo si TODOS los jobs de `needs` terminaron en success; falla
# (exit 1) si alguno terminó en failure, cancelled o skipped. GitHub reporta un
# job omitido como Success y no bloquea el merge aunque el check sea required:
# por eso el gate no acepta nada distinto de "success".
#
# Uso (en el job del workflow):
#   env:
#     NEEDS_JSON: ${{ toJSON(needs) }}
#   run: .github/scripts/needs_all_succeeded.sh
#
# En local: NEEDS_JSON='{"a":{"result":"success"}}' .github/scripts/needs_all_succeeded.sh
set -Eeuo pipefail

if [[ -z "${NEEDS_JSON:-}" ]]; then
  echo "::error::Harness self-test: NEEDS_JSON vacío; el gate no sabe de qué jobs depende"
  echo "ERROR: NEEDS_JSON vacío; el gate no sabe de qué jobs depende" >&2
  exit 1
fi

# to_entries revienta si NEEDS_JSON no es un objeto: eso también es rojo.
if ! entries="$(printf '%s' "${NEEDS_JSON}" \
      | jq -r 'to_entries[] | "\(.key)\t\(.value.result // "missing")"' 2>&1)"; then
  echo "::error::Harness self-test: NEEDS_JSON no es un objeto JSON válido"
  echo "ERROR: NEEDS_JSON no es un objeto JSON válido: ${entries}" >&2
  exit 1
fi

if [[ -z "${entries}" ]]; then
  echo "::error::Harness self-test: el gate no depende de ningún job (needs vacío)"
  echo "ERROR: el gate no depende de ningún job (needs vacío)" >&2
  exit 1
fi

failed=()
while IFS=$'\t' read -r job result; do
  [[ -n "${job}" ]] || continue
  printf '  %-16s %s\n' "${job}" "${result}"
  if [[ "${result}" != "success" ]]; then
    failed+=("${job} (${result})")
  fi
done <<< "${entries}"

if [[ "${#failed[@]}" -gt 0 ]]; then
  echo "::error::Harness self-test: ${#failed[@]} job(s) requerido(s) no terminaron en success: ${failed[*]}"
  echo "ERROR: ${failed[*]}" >&2
  exit 1
fi

echo "OK: todos los jobs requeridos terminaron en success"
