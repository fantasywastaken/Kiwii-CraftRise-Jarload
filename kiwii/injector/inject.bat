@echo off
setlocal EnableExtensions
title Kiwii Injector

set ROOT=%~dp0
if "%ROOT:~-1%"=="\" set ROOT=%ROOT:~0,-1%
set DLL=%ROOT%\Kiwii.dll
set TARGET=craftrise-x64.exe

echo ========================================
echo           KIWII INJECTOR
echo ========================================
echo.

if not exist "%DLL%" (
    echo [X] Kiwii.dll not found: %DLL%
    echo     Place Kiwii.dll next to inject.bat.
    echo.
    pause
    exit /b 1
)
echo [+] DLL: %DLL%

tasklist /fi "imagename eq %TARGET%" /fo csv /nh 2>nul | find /i "%TARGET%" >nul
if errorlevel 1 (
    echo [X] %TARGET% is not running.
    echo     Launch the CraftRise client and enter the game first.
    echo.
    pause
    exit /b 1
)
echo [+] Target: %TARGET% running

echo.
echo [*] Injecting...
powershell -ExecutionPolicy Bypass -NoProfile -File "%ROOT%\inject.ps1" -DllPath "%DLL%" -ProcessName "%TARGET%"
set PSRESULT=%ERRORLEVEL%

echo.
if "%PSRESULT%"=="0" (
    echo ========================================
    echo           INJECT OK
    echo ========================================
) else (
    echo ========================================
    echo           INJECT FAILED ^(%PSRESULT%^)
    echo ========================================
)
echo.
pause
