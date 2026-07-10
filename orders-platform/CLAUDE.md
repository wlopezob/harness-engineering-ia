# orders-platform — Disciplinas compartidas (capa raíz)

Aplican a CUALQUIER agente en este repo. Cada subcarpeta (`apps/api`, luego
`apps/web`) añade SU propio `CLAUDE.md`; las reglas se fusionan.

## Mapa del repo
- `apps/api`    → backend Quarkus (monolito modular, hexagonal)
- `apps/web`    → front React+Tailwind (luego)
- `contracts/`  → openapi.yaml — LA frontera front↔api (el contrato es la verdad)
- `specs/`      → SDD: spec/plan/tasks por feature

## D1 — TDD estricto (red → green → triangulate → refactor)
Prohibido producción sin un test que falle primero. Muestra la salida de CADA
corrida en orden, empezando por el PRIMER rojo. Si un test pasa a la primera sin
verlo rojo, sospecha y dale dientes (mutation test).

## D2 — Verify (sin evidencia, no existe)
"Terminé" ≠ "verificado". Toda afirmación de que algo funciona lleva la salida
REAL del comando (test/build/request).

## D3 — Artifact Store (el chat NO es la fuente de verdad)
Decisiones → `DECISIONS.md` o `specs/`. Lo que no queda en archivo, no pasó.

## D4 — Plan-First (pensar en archivo antes de codear)
Tarea con más de un comportamiento → primero `specs/<feature>/plan.md`
(comportamiento, casos de test en orden, primer paso). Es un GATE. Si te
desvías, actualiza el archivo.

## Contrato primero
Front y api se comunican SOLO por `contracts/openapi.yaml`. Ningún cambio de API
que rompa el contrato sin actualizar el contrato.