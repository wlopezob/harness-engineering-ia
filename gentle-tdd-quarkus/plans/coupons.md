# Plan — Soporte de cupones en DiscountService

> ✅ COMPLETADO (2026-06-28). Los 5 casos se ejecutaron en el orden planeado,
> sin desvíos. Suite: 9/9 verde. Spec ejecutable en `DiscountServiceTest`.

## Comportamiento

`applyCoupon(double price, String code)` devuelve el precio tras aplicar el
descuento asociado al cupón. Códigos conocidos descuentan un porcentaje;
desconocidos son error; `null`/vacío no cambian el precio.

## Reglas

- `"SAVE10"` → 10% de descuento.
- `"SAVE20"` → 20% de descuento.
- Código desconocido → `IllegalArgumentException`.
- `code` null o vacío → devuelve `price` sin cambios.

## Casos de test en orden (los rojos que voy a atacar)

1. `applyCoupon(100, "SAVE10") == 90.0`  ← primer rojo
2. `applyCoupon(100, "SAVE20") == 80.0`  (triangula: obliga a mapear código→%, no hardcodear)
3. `applyCoupon(100, "NOPE")` lanza `IllegalArgumentException`
4. `applyCoupon(100, null) == 100.0`
5. `applyCoupon(100, "") == 100.0`  (triangula el caso "sin cambios": vacío además de null)

## Primer paso

Test `save10AppliesTenPercent`: `assertEquals(90.0, service.applyCoupon(100, "SAVE10"))`.
Correrlo en rojo (no existe `applyCoupon`), luego mínimo verde.

## Notas de diseño

- Reusar `finalPrice(price, percent)` ya existente para aplicar el porcentaje
  (incluye su validación de rango). `applyCoupon` solo mapea código → porcentaje.
