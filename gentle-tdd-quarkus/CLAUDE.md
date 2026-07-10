# Harness del proyecto — gentle-tdd-quarkus

> Esto NO es documentación: es el **harness**. Reglas de runtime que el agente
> (Claude Code) DEBE obedecer en CADA tarea de este proyecto.
> "Runtime discipline, no un prompt." Si una regla y mi impulso chocan, gana la regla.

## Stack y comandos (la verdad operativa)

- Java 21 · Quarkus 3.37 · Maven Wrapper (`./mvnw`). NO uses `mvn` global.
- Correr TODOS los tests:   `./mvnw test`
- Correr UN test puntual:   `./mvnw test -Dtest='ClaseTest#metodo'`
- Paquete base: `com.gentleman`. Código en `src/main/java`, tests en `src/test/java`.

---

## HARNESS 1 — Strict TDD  (red → green → triangulate → refactor)

**Regla de oro: PROHIBIDO escribir código de producción sin un test que falle primero.**

1. **RED** — Escribe el test más pequeño posible para el siguiente comportamiento.
   Córrelo. DEBE fallar, y por la razón correcta (el comportamiento que falta, no un typo).
2. **GREEN** — Escribe el MÍNIMO código para que pase. Nada de más. Córrelo: verde.
3. **TRIANGULATE** — Si pasó con un valor "hardcodeado", agrega otro caso que lo
   obligue a generalizar. Repite hasta que la lógica sea real.
4. **REFACTOR** — Con los tests en verde, limpia. Vuelve a correr: sigue verde.

Disciplina:
- Un comportamiento a la vez. No escribas 3 tests de golpe ni adelantes lógica.
- Si te descubres escribiendo producción sin test rojo: PÁRATE, borra, empieza por el test.
- EVIDENCIA DEL CICLO: muestra la salida de CADA corrida de test en orden, empezando por el PRIMER rojo (el del primer test, antes de que exista código de producción). Prohibido resumir "hice TDD": el primer rojo y su orden respecto al código de producción DEBEN verse en la salida.

---

## HARNESS 2 — Verify  ("terminé NO significa verificado")

- NO declares "listo", "funciona" ni "el test pasa" sin haber corrido `./mvnw test`
  en ESTA sesión y mostrado la salida real.
- Si afirmas un resultado, va con el output que lo prueba. Sin evidencia, no existe.
- Si un test pasa "a la primera" sin verlo rojo antes, sospecha: quizá no prueba nada.

---

## HARNESS 3 — Artifact Store  ("el chat NO es la fuente de verdad")

- Las decisiones de diseño y reglas de negocio van a archivos (`DECISIONS.md`, el
  propio test como especificación ejecutable), no solo al chat.
- El chat se compacta y se borra; el repo persiste. Lo que importa, va al repo.

## HARNESS 4 — Plan-First  ("pensar en archivo antes de tocar código")

Antes de escribir CUALQUIER código (test o producción) para una tarea con más de
un comportamiento:

1. Escribe un plan en `plans/<feature>.md` ANTES de codear, con:
   - **Comportamiento**: qué debe hacer, en una o dos frases.
   - **Casos de test en orden** (el primero, luego las triangulaciones): la lista
     de rojos que vas a atacar, uno por línea.
   - **Primer paso**: el test más pequeño con el que arrancas.
2. El plan es un GATE, no un adorno: no se escribe una línea de código hasta que el
   archivo de plan exista.
3. Si te desvías del plan durante la ejecución (caso nuevo, orden distinto),
   ACTUALIZA el archivo antes de seguir. El plan y lo ejecutado NO pueden divergir
   en silencio.