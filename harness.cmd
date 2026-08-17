@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "ROOT_DIR=%~dp0"

rem %~dp0 termina en barra invertida y `git -C "C:\ruta\"` rompe el argumento:
rem la barra escapa la comilla de cierre y git no recibe la ruta. Sin ella,
rem tanto rev-parse como el calculo de identidad funcionan.
if "%ROOT_DIR:~-1%"=="\" set "ROOT_DIR=%ROOT_DIR:~0,-1%"

set "API_DIR=%ROOT_DIR%\orders-platform\apps\api"
set "ARTIFACTS_DIR=%ROOT_DIR%\artifacts\harness"

rem Identidad del codigo verificado (github-26). Debe producir EXACTAMENTE el
rem mismo valor que ./harness: el manifiesto se ordena byte a byte (comparacion
rem ordinal, no el sort del sistema) y la normalizacion de fin de linea se fija
rem con core.autocrlf=input en vez de heredar la config de la maquina.
set "SOURCE_STATE_SCOPE=repo:tracked+untracked-not-ignored"
set "SOURCE_STATE_ALGORITHM=git-hash-object -c core.autocrlf=input (manifest '<blob> <path>': ls-files --cached + --others --exclude-standard, byte-wise path order, LF)"
set "SOURCE_STATE=unknown"
set "SOURCE_DIRTY=false"
set "SOURCE_CHANGED_FILES=0"
set "SOURCE_MANIFEST_FILE="

if "%~1"=="" goto help
if "%~1"=="help" goto help
if "%~1"=="--help" goto help
if "%~1"=="-h" goto help
if "%~1"=="verify" goto verify
if "%~1"=="format" goto format
if "%~1"=="mutation" goto mutation
if "%~1"=="state" goto state

rem mismo comportamiento que ./harness: error a stderr, usage completo, exit 2
echo ERROR: Unknown harness command: %~1 1>&2
echo.
call :print_help
exit /b 2

:verify
call :get_timestamp
set "STARTED_AT=%TIMESTAMP_ISO%"
set "RUN_TIMESTAMP=%TIMESTAMP_FILE%"

for /f "delims=" %%A in ('git -C "%ROOT_DIR%" rev-parse HEAD 2^>nul') do set "COMMIT_SHA=%%A"
if not defined COMMIT_SHA set "COMMIT_SHA=unknown"

for /f "delims=" %%A in ('git -C "%ROOT_DIR%" rev-parse --short HEAD 2^>nul') do set "SHORT_SHA=%%A"
if not defined SHORT_SHA set "SHORT_SHA=unknown"

for /f "delims=" %%A in ('git -C "%ROOT_DIR%" branch --show-current 2^>nul') do set "BRANCH=%%A"
if not defined BRANCH (
    if defined GITHUB_HEAD_REF (
        set "BRANCH=%GITHUB_HEAD_REF%"
    ) else (
        set "BRANCH=detached"
    )
)

rem la identidad se captura ANTES de Maven: lo que genere el build no puede
rem cambiar el estado que se declara verificado
call :compute_source_state

if "!SOURCE_DIRTY!"=="true" (
    set "EVIDENCE_DIR=%ARTIFACTS_DIR%\%RUN_TIMESTAMP%-%SHORT_SHA%-dirty-!SOURCE_STATE:~0,7!"
) else (
    set "EVIDENCE_DIR=%ARTIFACTS_DIR%\%RUN_TIMESTAMP%-%SHORT_SHA%"
)

set "COMMAND_LOG=!EVIDENCE_DIR!\command.log"
set "VERIFICATION_FILE=!EVIDENCE_DIR!\verification.json"
set "REPORTS_DIR=!EVIDENCE_DIR!\test-reports"

if not exist "!EVIDENCE_DIR!" mkdir "!EVIDENCE_DIR!"

if defined SOURCE_MANIFEST_FILE (
    copy /Y "!SOURCE_MANIFEST_FILE!" "!EVIDENCE_DIR!\source-state.txt" >nul
)

echo ==================================================
echo  Engineering Harness: backend verification
echo ==================================================
echo Repository: %ROOT_DIR%
echo Backend:    %API_DIR%
echo Evidence:   !EVIDENCE_DIR!
echo Commit:     %SHORT_SHA% (!BRANCH!)

if "!SOURCE_DIRTY!"=="true" (
    echo Source:     DIRTY - !SOURCE_CHANGED_FILES! archivo^(s^) local^(es^) sin commit
    echo State:      !SOURCE_STATE!  ^(manifiesto: source-state.txt^)
) else (
    echo Source:     working tree limpio
    echo State:      !SOURCE_STATE!
)

echo.

set "START_SECONDS=%TIME%"
set "EXIT_CODE=0"

if not exist "%API_DIR%" (
    echo ERROR: Backend directory not found: %API_DIR% > "%COMMAND_LOG%"
    type "%COMMAND_LOG%"
    set "EXIT_CODE=2"
    goto finalize
)

if not exist "%API_DIR%\mvnw.cmd" (
    echo ERROR: Maven Wrapper not found: %API_DIR%\mvnw.cmd > "%COMMAND_LOG%"
    type "%COMMAND_LOG%"
    set "EXIT_CODE=2"
    goto finalize
)

pushd "%API_DIR%"

call mvnw.cmd --batch-mode --no-transfer-progress clean verify > "%COMMAND_LOG%" 2>&1
set "EXIT_CODE=%ERRORLEVEL%"

popd

type "%COMMAND_LOG%"

:finalize
call :copy_reports

call :get_timestamp
set "FINISHED_AT=%TIMESTAMP_ISO%"

call :calculate_duration "%START_SECONDS%" "%TIME%"
set "DURATION_SECONDS=%DURATION_RESULT%"

if "%EXIT_CODE%"=="0" (
    set "RESULT=PASSED"
) else (
    set "RESULT=FAILED"
)

if defined CI (
    set "CI_VALUE=%CI%"
) else (
    set "CI_VALUE=false"
)

if defined GITHUB_RUN_ID (
    set "GITHUB_RUN_ID_VALUE=%GITHUB_RUN_ID%"
) else (
    set "GITHUB_RUN_ID_VALUE=local"
)

if defined GITHUB_RUN_ATTEMPT (
    set "GITHUB_RUN_ATTEMPT_VALUE=%GITHUB_RUN_ATTEMPT%"
) else (
    set "GITHUB_RUN_ATTEMPT_VALUE=local"
)

(
    echo {
    echo   "schemaVersion": "1.1",
    echo   "command": "harness.cmd verify",
    echo   "component": "orders-platform/apps/api",
    echo   "result": "%RESULT%",
    echo   "exitCode": %EXIT_CODE%,
    echo   "startedAt": "%STARTED_AT%",
    echo   "finishedAt": "%FINISHED_AT%",
    echo   "durationSeconds": %DURATION_SECONDS%,
    echo   "git": {
    echo     "commit": "%COMMIT_SHA%",
    echo     "branch": "%BRANCH%"
    echo   },
    echo   "source": {
    echo     "dirty": !SOURCE_DIRTY!,
    echo     "state": "!SOURCE_STATE!",
    echo     "stateAlgorithm": "!SOURCE_STATE_ALGORITHM!",
    echo     "scope": "!SOURCE_STATE_SCOPE!",
    echo     "changedFiles": !SOURCE_CHANGED_FILES!,
    echo     "manifest": "source-state.txt"
    echo   },
    echo   "environment": {
    echo     "ci": "%CI_VALUE%",
    echo     "githubRunId": "%GITHUB_RUN_ID_VALUE%",
    echo     "githubRunAttempt": "%GITHUB_RUN_ATTEMPT_VALUE%"
    echo   },
    echo   "evidence": {
    echo     "commandLog": "command.log",
    echo     "testReports": "test-reports",
    echo     "sourceManifest": "source-state.txt"
    echo   }
    echo }
) > "%VERIFICATION_FILE%"

echo.
echo ==================================================
echo  HARNESS RESULT: !RESULT!

if "!SOURCE_DIRTY!"=="true" (
    echo  Source: DIRTY - HEAD %SHORT_SHA% + cambios locales ^(state !SOURCE_STATE:~0,7!^)
) else (
    echo  Source: HEAD %SHORT_SHA% ^(working tree limpio^)
)

echo  Evidence: !EVIDENCE_DIR!
echo ==================================================

exit /b %EXIT_CODE%

:copy_reports
if not exist "%REPORTS_DIR%" mkdir "%REPORTS_DIR%"

if exist "%API_DIR%\target\surefire-reports" (
    xcopy "%API_DIR%\target\surefire-reports" ^
          "%REPORTS_DIR%\surefire-reports\" ^
          /E /I /Y >nul
)

if exist "%API_DIR%\target\failsafe-reports" (
    xcopy "%API_DIR%\target\failsafe-reports" ^
          "%REPORTS_DIR%\failsafe-reports\" ^
          /E /I /Y >nul
)

if exist "%API_DIR%\target\quarkus.log" (
    copy /Y ^
         "%API_DIR%\target\quarkus.log" ^
         "%REPORTS_DIR%\quarkus.log" >nul
)

if exist "%API_DIR%\target\jacoco-reports" (
    xcopy "%API_DIR%\target\jacoco-reports" ^
          "%REPORTS_DIR%\jacoco-reports\" ^
          /E /I /Y >nul
)

if exist "%API_DIR%\target\jacoco-quarkus.exec" (
    copy /Y ^
         "%API_DIR%\target\jacoco-quarkus.exec" ^
         "%REPORTS_DIR%\jacoco-quarkus.exec" >nul
)

if exist "%API_DIR%\target\pit-reports" (
    xcopy "%API_DIR%\target\pit-reports" ^
          "%REPORTS_DIR%\pit-reports\" ^
          /E /I /Y >nul
)

exit /b 0

:get_timestamp
for /f %%A in ('powershell -NoProfile -Command "(Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')"') do set "TIMESTAMP_ISO=%%A"

for /f %%A in ('powershell -NoProfile -Command "(Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')"') do set "TIMESTAMP_FILE=%%A"

exit /b 0

:calculate_duration
set "START_TIME=%~1"
set "END_TIME=%~2"

rem la expresion va entre comillas: dentro de un bloque ( ... ), cmd toma el
rem primer ) de la aritmetica como cierre del bloque y aborta el batch con
rem "was unexpected at this time" (lo cazo la suite de contrato en windows-latest;
rem verify nunca habia llegado hasta aqui en CI)
for /f "tokens=1-4 delims=:.," %%A in ("%START_TIME%") do (
    set /a "START_TOTAL=(((1%%A-100)*60+(1%%B-100))*60+(1%%C-100))"
)

for /f "tokens=1-4 delims=:.," %%A in ("%END_TIME%") do (
    set /a "END_TOTAL=(((1%%A-100)*60+(1%%B-100))*60+(1%%C-100))"
)

if !END_TOTAL! LSS !START_TOTAL! (
    set /a END_TOTAL+=86400
)

set /a DURATION_RESULT=END_TOTAL-START_TOTAL

exit /b 0

:format
echo ==================================================
echo  Engineering Harness: apply formatting
echo ==================================================
echo Repository: %ROOT_DIR%
echo Backend:    %API_DIR%
echo.

if not exist "%API_DIR%\mvnw.cmd" (
    echo ERROR: Maven Wrapper not found: %API_DIR%\mvnw.cmd 1>&2
    exit /b 2
)

pushd "%API_DIR%"

call mvnw.cmd --batch-mode --no-transfer-progress spotless:apply
set "FORMAT_EXIT_CODE=%ERRORLEVEL%"

popd

if not "%FORMAT_EXIT_CODE%"=="0" (
    echo.
    echo ==================================================
    echo  FORMAT RESULT: FAILED
    echo ==================================================
    exit /b %FORMAT_EXIT_CODE%
)

echo.
echo ==================================================
echo  FORMAT RESULT: APPLIED
echo ==================================================
exit /b 0

:mutation
echo ==================================================
echo  Engineering Harness: mutation testing
echo ==================================================
echo Repository: %ROOT_DIR%
echo Backend:    %API_DIR%
echo.

if not exist "%API_DIR%" (
    echo ERROR: Backend directory not found: %API_DIR% 1>&2
    exit /b 2
)

if not exist "%API_DIR%\mvnw.cmd" (
    echo ERROR: Maven Wrapper not found: %API_DIR%\mvnw.cmd 1>&2
    exit /b 2
)

pushd "%API_DIR%"

rem mismo proceso logico que ./harness mutation: compilar tests y lanzar PIT
call mvnw.cmd --batch-mode --no-transfer-progress test-compile org.pitest:pitest-maven:mutationCoverage
set "MUTATION_EXIT_CODE=%ERRORLEVEL%"

popd

if not "%MUTATION_EXIT_CODE%"=="0" (
    echo.
    echo ==================================================
    echo  MUTATION RESULT: FAILED
    echo ==================================================
    exit /b %MUTATION_EXIT_CODE%
)

echo.
echo ==================================================
echo  MUTATION RESULT: COMPLETED
echo  Report: %API_DIR%\target\pit-reports\index.html
echo ==================================================
exit /b 0

:help
call :print_help
exit /b 0

rem Mismo texto que show_usage en ./harness (modulo el nombre del programa):
rem tests/harness/parity_test.sh compara los dos.
:print_help
echo Engineering Harness
echo.
echo Usage:
echo   harness.cmd verify
echo   harness.cmd format
echo   harness.cmd mutation
echo   harness.cmd state
echo   harness.cmd help
echo.
echo Commands:
echo   verify   Run the complete backend verification harness.
echo   format   Apply the repository formatting rules ^(spotless:apply^).
echo   mutation Run mutation testing for domain and application.
echo   state    Print the identity of the source state that would be verified.
echo            Use `state --manifest ^<path^>` to also dump the manifest behind it
echo            ^(write it outside the repo: inside, it becomes a new untracked
echo            file and changes the next state^).
echo   help     Show this help message.
exit /b 0

:compute_source_state
set "SOURCE_STATE=unknown"
set "SOURCE_DIRTY=false"
set "SOURCE_CHANGED_FILES=0"
set "SOURCE_MANIFEST_FILE="

git -C "%ROOT_DIR%" rev-parse --git-dir >nul 2>&1
if errorlevel 1 exit /b 0

set "STATE_TMP=%TEMP%\harness-state-%RANDOM%%RANDOM%"
if not exist "%STATE_TMP%" mkdir "%STATE_TMP%"

set "PATHS_RAW=%STATE_TMP%\paths-raw"
set "PATHS_FILE=%STATE_TMP%\paths"
set "MANIFEST_FILE=%STATE_TMP%\manifest"

pushd "%ROOT_DIR%"

rem mismo orden que ./harness: global byte-wise por path, NO por grupos. Git
rem emite primero los tracked y luego los untracked, asi que agrupar haria que
rem un `git add` (que no cambia el contenido) alterara el estado.
(
    git -c core.quotePath=false ls-files --cached --deduplicate
    git -c core.quotePath=false ls-files --others --exclude-standard
) > "%PATHS_RAW%"

rem comparacion ordinal de .NET: equivale a LC_ALL=C sort. El sort.exe de
rem Windows ordena segun el locale y romperia la paridad con ./harness.
powershell -NoProfile -Command "$lines = [IO.File]::ReadAllLines('%PATHS_RAW%'); [Array]::Sort($lines, [StringComparer]::Ordinal); [IO.File]::WriteAllLines('%PATHS_FILE%', $lines)"

break > "%MANIFEST_FILE%"

rem mismo manifiesto que ./harness ("<blob> <path>" por linea, mismo orden).
rem Aqui git se invoca por archivo en vez de con --stdin-paths: el resultado es
rem identico y evita tener que unir dos ficheros linea a linea en batch.
for /f "usebackq delims=" %%P in ("%PATHS_FILE%") do (
    rem un tracked borrado del working tree se queda fuera: su ausencia ya
    rem cambia la identidad, y hashearlo fallaria al no existir
    if exist "%%P" (
        for /f "delims=" %%H in ('git -c core.autocrlf^=input hash-object -- "%%P"') do (
            >> "%MANIFEST_FILE%" echo %%H %%P
        )
    )
)

rem core.autocrlf=input normaliza el manifiesto a LF antes de hashear, para que
rem Windows y macOS produzcan el mismo state con el mismo codigo
for /f "delims=" %%H in ('git -c core.autocrlf^=input hash-object -- "%MANIFEST_FILE%"') do set "SOURCE_STATE=%%H"

rem find.exe con ruta absoluta: bajo Git Bash el PATH pone usr\bin delante y
rem `find` es el GNU find, que toma /c como el directorio C:\ y recorre el disco
rem entero (lo cazo la suite de contrato en windows-latest: verify colgado).
for /f %%C in ('git status --porcelain --untracked-files^=all ^| "%SystemRoot%\System32\find.exe" /c /v ""') do set "SOURCE_CHANGED_FILES=%%C"

popd

if not "%SOURCE_CHANGED_FILES%"=="0" set "SOURCE_DIRTY=true"

set "SOURCE_MANIFEST_FILE=%MANIFEST_FILE%"

exit /b 0

:state
set "STATE_MANIFEST_TARGET="

rem `state --manifest <ruta>` vuelca el manifiesto que respalda el id, para
rem poder auditarlo (o diffear dos plataformas) sin correr un verify completo
rem el error sale por goto a una etiqueta de nivel superior: un `exit /b 2`
rem dentro de un if anidado en otro if llego como 0 al proceso cmd /c en
rem windows-latest (la suite de contrato lo vio; el mismo exit /b en un bloque
rem simple si propaga)
if /I "%~2"=="--manifest" (
    if "%~3"=="" goto state_manifest_missing
    set "STATE_MANIFEST_TARGET=%~3"
)

call :compute_source_state

if defined STATE_MANIFEST_TARGET (
    if defined SOURCE_MANIFEST_FILE (
        copy /Y "!SOURCE_MANIFEST_FILE!" "!STATE_MANIFEST_TARGET!" >nul
    )
)

echo {
echo   "dirty": !SOURCE_DIRTY!,
echo   "state": "!SOURCE_STATE!",
echo   "stateAlgorithm": "!SOURCE_STATE_ALGORITHM!",
echo   "scope": "!SOURCE_STATE_SCOPE!",
echo   "changedFiles": !SOURCE_CHANGED_FILES!
echo }

if defined STATE_TMP if exist "%STATE_TMP%" rmdir /S /Q "%STATE_TMP%"

exit /b 0

:state_manifest_missing
echo ERROR: --manifest necesita una ruta 1>&2
exit /b 2
