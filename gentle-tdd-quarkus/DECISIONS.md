# DECISIONS

Registro de decisiones de diseño y reglas de negocio. El chat se compacta; esto persiste.

## DiscountService — precio final con descuento porcentual

- **Fecha:** 2026-06-28
- **Clase:** `com.gentleman.DiscountService`
- **API:** `double finalPrice(double price, int discountPercent)`
- **Regla:** el precio final es `price - (price * discountPercent / 100.0)`.
  - `finalPrice(100, 10) == 90.0`
  - `finalPrice(50, 20) == 40.0`
- **Validación de `discountPercent`:** si es `< 0` o `> 100` se lanza
  `IllegalArgumentException` (los bordes `0` y `100` son válidos).
  - `finalPrice(100, -1)` → lanza
  - `finalPrice(100, 101)` → lanza
- **Especificación ejecutable:** `src/test/java/com/gentleman/DiscountServiceTest.java`.

### Cupones — `applyCoupon(double price, String code)`

- **Fecha:** 2026-06-28
- **Plan:** `plans/coupons.md`
- **Reglas:**
  - `"SAVE10"` → 10% de descuento (`applyCoupon(100, "SAVE10") == 90.0`).
  - `"SAVE20"` → 20% de descuento (`applyCoupon(100, "SAVE20") == 80.0`).
  - Código desconocido → `IllegalArgumentException` (`applyCoupon(100, "NOPE")`).
  - `code` null o vacío → devuelve `price` sin cambios.
- **Diseño:** `applyCoupon` mapea código → porcentaje y delega en
  `finalPrice(price, percent)` (reusa su cálculo y validación de rango).
- **Sensibilidad a mayúsculas:** los códigos se comparan exactos; `"save10"`
  hoy es desconocido (no hay test que pida case-insensitive).

### Cupones combinables (separados por coma)

- **Fecha:** 2026-06-28
- **Plan:** `plans/coupon-combination.md`
- **Regla:** `code` puede traer varios códigos separados por coma; se aplican
  **en secuencia** (cada descuento sobre el precio ya descontado).
  - `applyCoupon(100, "SAVE10,SAVE20") == 72.0` (100→90→72).
  - `applyCoupon(100, "SAVE10,SAVE10") == 81.0` (100→90→81).
  - Un código desconocido en cualquier posición → `IllegalArgumentException`
    (`applyCoupon(100, "SAVE10,NOPE")`).
  - Un solo código / null / vacío: igual que antes.
- **Diseño:** `applyCoupon` parte por `,`, itera y delega cada segmento en el
  helper privado `applySingleCoupon(price, code)`.
- **Importante (efecto secuencial):** combinar descuentos NO suma porcentajes.
  `"SAVE10,SAVE20"` da 72, no 70 — se aplican uno tras otro, no 30% de golpe.
- **Fuera de alcance (sin test):** trims de espacios (`"SAVE10, SAVE20"`) y
  segmentos vacíos por comas dobles (`"SAVE10,,SAVE20"`).

### Alcance deliberado (lo NO implementado, por disciplina TDD)

Aún no hay test que lo exija, así que NO se implementó (no se adelanta lógica):

- Validación de `price` negativo.
- Redondeo / manejo de centavos (hoy se devuelve `double` crudo).

Cuando se necesite alguno de estos comportamientos: primero un test rojo, luego el código.
