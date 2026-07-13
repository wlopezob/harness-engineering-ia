## Paso 1: proteger rama main

Cuando el workflow haya ejecutado al menos una vez:

1. Entra al repositorio en GitHub.
2. Ve a Settings.
3. Entra a Branches o Rules → Rulesets.
4. Crea una regla para main.
5. Activa Require a pull request before merging.
6. Activa Require status checks to pass.
7. Selecciona:
```
Maven Verify
```