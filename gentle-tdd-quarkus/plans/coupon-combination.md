# Plan — Cupones combinables (separados por coma)

> ✅ COMPLETADO (2026-06-28). Suite: 12/12 verde. Ver desvío anotado en el caso 3.

## Comportamiento

`applyCoupon(double price, String code)` acepta varios códigos separados por coma
(ej. `"SAVE10,SAVE20"`) y los aplica EN SECUENCIA sobre el precio. Un solo código
sigue funcionando igual que hoy.

## Reglas

- `"SAVE10,SAVE20"` sobre 100 → aplica SAVE10 (100→90), luego SAVE20 (90→72) = **72.0**.
- Aplicación secuencial: el descuento N se calcula sobre el precio ya descontado.
- Un código desconocido dentro de la lista → `IllegalArgumentException` (igual que hoy).
- Un solo código / null / vacío → sin cambios respecto al comportamiento actual.

## Casos de test en orden (los rojos que voy a atacar)

1. `applyCoupon(100, "SAVE10,SAVE20") == 72.0`  ← primer rojo (obliga a partir por coma)
2. `applyCoupon(100, "SAVE10,SAVE10") == 81.0`  (triangula: secuencial real + código repetido, mata el hardcode)
3. `applyCoupon(100, "SAVE10,NOPE")` lanza `IllegalArgumentException`  (desconocido dentro de la combinación)
   - DESVÍO real: este caso NO fue rojo-driver. La generalización del caso 2
     (helper `applySingleCoupon` que lanza por segmento) ya lo cubría, así que pasó
     a la primera. Para no violar el HARNESS 2 ("si pasa sin verlo rojo, sospecha")
     se verificó con una MUTACIÓN temporal del código (cambiar el `throw` por
     `return price`): el test se puso rojo, confirmando que prueba algo. Mutación
     revertida. Queda como test de regresión.

## Regresión (NO se reescriben, deben seguir verdes)

- `applyCoupon(100, "SAVE10") == 90.0` — un solo código.
- `applyCoupon(100, null) == 100.0`, `applyCoupon(100, "") == 100.0`.

## Primer paso

Test `combinesTwoCouponsInSequence`: `assertEquals(72.0, service.applyCoupon(100, "SAVE10,SAVE20"))`.
Rojo primero, luego mínimo verde.

## Notas de diseño

- Extraer la lógica de un cupón a un helper `applySingleCoupon(price, code)` y que
  `applyCoupon` itere sobre `code.split(",")` acumulando el precio.
- Fuera de alcance (sin test que lo exija): trims de espacios (`"SAVE10, SAVE20"`),
  segmentos vacíos por comas dobles (`"SAVE10,,SAVE20"`).
