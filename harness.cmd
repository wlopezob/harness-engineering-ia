@echo off
setlocal

set "ROOT_DIR=%~dp0"
set "API_DIR=%ROOT_DIR%orders-platform\apps\api"

if "%~1"=="" goto help
if /I "%~1"=="help" goto help
if /I "%~1"=="--help" goto help
if /I "%~1"=="-h" goto help
if /I "%~1"=="verify" goto verify

echo ERROR: Unknown harness command: %~1
echo.
goto help_error

:verify
echo ==================================================
echo  Engineering Harness: backend verification
echo ==================================================
echo Repository: %ROOT_DIR%
echo Backend:    %API_DIR%
echo.

if not exist "%API_DIR%" (
    echo ERROR: Backend directory not found: %API_DIR%
    exit /b 2
)

if not exist "%API_DIR%\mvnw.cmd" (
    echo ERROR: Maven Wrapper not found: %API_DIR%\mvnw.cmd
    exit /b 2
)

pushd "%API_DIR%"

call mvnw.cmd --batch-mode --no-transfer-progress clean verify
set "VERIFY_EXIT_CODE=%ERRORLEVEL%"

popd

if not "%VERIFY_EXIT_CODE%"=="0" (
    echo.
    echo ==================================================
    echo  HARNESS RESULT: FAILED
    echo ==================================================
    exit /b %VERIFY_EXIT_CODE%
)

echo.
echo ==================================================
echo  HARNESS RESULT: PASSED
echo ==================================================
exit /b 0

:help
echo Engineering Harness
echo.
echo Usage:
echo   harness.cmd verify
echo   harness.cmd help
echo.
echo Commands:
echo   verify   Run the complete backend verification harness.
echo   help     Show this help message.
exit /b 0

:help_error
echo Usage:
echo   harness.cmd verify
echo   harness.cmd help
exit /b 2