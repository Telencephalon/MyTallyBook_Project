@echo off
setlocal DisableDelayedExpansion
if not "%~2"=="" goto usage
set "mtbUpdate="
if "%~1"=="" goto run
if /I "%~1"=="-UpdateBackend" (
    set "mtbUpdate=-UpdateBackend"
    goto run
)
goto usage

:run
echo MyTallyBook development startup
echo Keep the SSH and backend windows open. Enter the server password only if SSH asks for it.
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy\scripts\Start-Dev.ps1" %mtbUpdate%
set "mtbExit=%ERRORLEVEL%"
echo.
echo Startup exit code: %mtbExit%
echo This launcher can close after you have read the result. Service windows must stay open.
pause
exit /b %mtbExit%

:usage
echo Usage: Start-Dev.cmd [-UpdateBackend]
exit /b 2
