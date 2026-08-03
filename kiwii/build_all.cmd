@echo off
setlocal EnableExtensions
chcp 65001 >nul

set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"
set "OUT_DIR=C:\kiwii"
set "MAVEN=%ROOT%\apache-maven-3.8.6\bin\mvn.cmd"

echo ========================================
echo           KIWII FULL BUILDER
echo ========================================
echo.

echo [0/3] Detecting JDK...
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\javac.exe" (
        echo [OK] JAVA_HOME: %JAVA_HOME%
        goto :jdk_ok
    )
)
rem Try common install locations. Corretto 1.8 uses "corretto-1.8.x" pattern.
for %%R in (
    "C:\Program Files\Eclipse Adoptium"
    "C:\Program Files\Java"
    "C:\Program Files\Zulu"
    "C:\Program Files\Amazon Corretto"
    "C:\Program Files\Microsoft"
    "%USERPROFILE%\.jdks"
) do (
    if exist %%R (
        for /d %%J in ("%%~R\*") do (
            if exist "%%J\bin\javac.exe" (
                set "JAVA_HOME=%%J"
                echo [OK] JDK auto-detected: %%J
                goto :jdk_ok
            )
        )
    )
)
echo [X] JDK not found. Install Eclipse Adoptium / Oracle JDK / Zulu / Amazon Corretto.
echo     Or set JAVA_HOME environment variable manually.
pause
exit /b 1
:jdk_ok
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo.

echo [1/3] Preparing output directory...
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"
if errorlevel 1 (
    echo [X] Failed to create %OUT_DIR%
    pause
    exit /b 1
)
echo [OK] Output: %OUT_DIR%
echo.

echo [2/3] Building Java client...
if not exist "%MAVEN%" (
    echo [X] Maven not found: %MAVEN%
    pause
    exit /b 1
)
pushd "%ROOT%\java"
call "%MAVEN%" clean package
set "JAVA_RESULT=%ERRORLEVEL%"
popd
if not "%JAVA_RESULT%"=="0" (
    echo [X] Java build failed.
    pause
    exit /b 1
)
if not exist "%OUT_DIR%\client.jar" (
    echo [X] %OUT_DIR%\client.jar not found after Java build.
    pause
    exit /b 1
)
echo [OK] Java built: %OUT_DIR%\client.jar
echo.

echo [3/3] Building C++ DLL (cl.exe direct)...
powershell -ExecutionPolicy Bypass -NoProfile -File "%ROOT%\build_cpp.ps1"
if errorlevel 1 (
    echo [X] C++ build failed.
    pause
    exit /b 1
)
if not exist "%OUT_DIR%\Kiwii.dll" (
    echo [X] Kiwii.dll not found at %OUT_DIR%.
    pause
    exit /b 1
)
echo.

echo ========================================
echo           BUILD COMPLETE
echo ========================================
echo Java: %OUT_DIR%\client.jar
echo DLL : %OUT_DIR%\Kiwii.dll
echo.
pause
