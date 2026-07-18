@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "ROOT_DIR=%~dp0"
set "API_DIR=%ROOT_DIR%orders-platform\apps\api"
set "ARTIFACTS_DIR=%ROOT_DIR%artifacts\harness"

if "%~1"=="" goto help
if /I "%~1"=="help" goto help
if /I "%~1"=="--help" goto help
if /I "%~1"=="-h" goto help
if /I "%~1"=="verify" goto verify
if /I "%~1"=="format" goto format

echo ERROR: Unknown harness command: %~1
echo.
goto help_error

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

set "EVIDENCE_DIR=%ARTIFACTS_DIR%\%RUN_TIMESTAMP%-%SHORT_SHA%"
set "COMMAND_LOG=%EVIDENCE_DIR%\command.log"
set "VERIFICATION_FILE=%EVIDENCE_DIR%\verification.json"
set "REPORTS_DIR=%EVIDENCE_DIR%\test-reports"

if not exist "%EVIDENCE_DIR%" mkdir "%EVIDENCE_DIR%"

echo ==================================================
echo  Engineering Harness: backend verification
echo ==================================================
echo Repository: %ROOT_DIR%
echo Backend:    %API_DIR%
echo Evidence:   %EVIDENCE_DIR%
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
    echo   "schemaVersion": "1.0",
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
    echo   "environment": {
    echo     "ci": "%CI_VALUE%",
    echo     "githubRunId": "%GITHUB_RUN_ID_VALUE%",
    echo     "githubRunAttempt": "%GITHUB_RUN_ATTEMPT_VALUE%"
    echo   },
    echo   "evidence": {
    echo     "commandLog": "command.log",
    echo     "testReports": "test-reports"
    echo   }
    echo }
) > "%VERIFICATION_FILE%"

echo.
echo ==================================================
echo  HARNESS RESULT: %RESULT%
echo  Evidence: %EVIDENCE_DIR%
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

exit /b 0

:get_timestamp
for /f %%A in ('powershell -NoProfile -Command "(Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')"') do set "TIMESTAMP_ISO=%%A"

for /f %%A in ('powershell -NoProfile -Command "(Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')"') do set "TIMESTAMP_FILE=%%A"

exit /b 0

:calculate_duration
set "START_TIME=%~1"
set "END_TIME=%~2"

for /f "tokens=1-4 delims=:.," %%A in ("%START_TIME%") do (
    set /a START_TOTAL=(((1%%A-100)*60+(1%%B-100))*60+(1%%C-100))
)

for /f "tokens=1-4 delims=:.," %%A in ("%END_TIME%") do (
    set /a END_TOTAL=(((1%%A-100)*60+(1%%B-100))*60+(1%%C-100))
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
    echo ERROR: Maven Wrapper not found: %API_DIR%\mvnw.cmd
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

:help
echo Engineering Harness
echo.
echo Usage:
echo   harness.cmd verify
echo   harness.cmd format
echo   harness.cmd help
echo.
echo Commands:
echo   verify   Run the complete backend verification harness.
echo   format   Apply the repository formatting rules.
echo   help     Show this help message.
exit /b 0

:help_error
echo Usage:
echo   harness.cmd verify
echo   harness.cmd help
exit /b 2