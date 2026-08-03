$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$launcherRoot = if (Test-Path (Join-Path $scriptRoot "src")) { $scriptRoot } else { Join-Path $scriptRoot "launcher" }

$msvcVer  = "14.50.35717"
$vsBase   = "C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools"
$sdkBase  = "C:\Program Files (x86)\Windows Kits\10"
$sdkVer   = "10.0.26100.0"

$srcDir   = Join-Path $launcherRoot "src"
$imguiDir = Join-Path $launcherRoot "vendor\imgui"
$objDir   = Join-Path $launcherRoot "obj\Release"
$outDir   = Join-Path $launcherRoot "bin\Release"
$dest     = "C:\kiwii"

$cl       = "$vsBase\VC\Tools\MSVC\$msvcVer\bin\Hostx64\x64\cl.exe"
$link     = "$vsBase\VC\Tools\MSVC\$msvcVer\bin\Hostx64\x64\link.exe"
$rc       = "$sdkBase\bin\$sdkVer\x64\rc.exe"

$msvcInc      = "$vsBase\VC\Tools\MSVC\$msvcVer\include"
$msvcLib      = "$vsBase\VC\Tools\MSVC\$msvcVer\lib\x64"
$sdkIncUm     = "$sdkBase\Include\$sdkVer\um"
$sdkIncShared = "$sdkBase\Include\$sdkVer\shared"
$sdkIncUcrt   = "$sdkBase\Include\$sdkVer\ucrt"
$sdkLibUm     = "$sdkBase\Lib\$sdkVer\um\x64"
$sdkLibUcrt   = "$sdkBase\Lib\$sdkVer\ucrt\x64"

foreach ($d in @($objDir, $outDir, $dest)) {
    if (-not (Test-Path $d)) { New-Item -ItemType Directory -Path $d -Force | Out-Null }
}

$includes = @(
    "/I$srcDir", "/I$launcherRoot\vendor", "/I$imguiDir", "/I$imguiDir\backends",
    "/I$msvcInc", "/I$sdkIncUm", "/I$sdkIncShared", "/I$sdkIncUcrt"
)

$compileCommon = @(
    "/c", "/O2", "/MT", "/std:c++17", "/EHsc",
    "/DNDEBUG", "/D_WINDOWS", "/DUNICODE", "/D_UNICODE",
    "/DWINVER=0x0A00", "/D_WIN32_WINNT=0x0A00", "/DNTDDI_VERSION=0x0A000000",
    "/DWIN32_LEAN_AND_MEAN", "/DNOMINMAX", "/D_CRT_SECURE_NO_WARNINGS",
    "/GS", "/Gy", "/Zc:inline", "/Zc:wchar_t", "/nologo"
) + $includes

Write-Host "Compiling ImGui..." -ForegroundColor Cyan
$imguiSources = @(
    "$imguiDir\imgui.cpp",
    "$imguiDir\imgui_draw.cpp",
    "$imguiDir\imgui_tables.cpp",
    "$imguiDir\imgui_widgets.cpp",
    "$imguiDir\backends\imgui_impl_win32.cpp",
    "$imguiDir\backends\imgui_impl_dx11.cpp"
)
& $cl @compileCommon "/Fo$objDir\" @imguiSources
if ($LASTEXITCODE -ne 0) { Write-Host "FAILED: ImGui compile" -ForegroundColor Red; exit 1 }

Write-Host "Compiling mgui framework..." -ForegroundColor Cyan
$mguiSources = @(
    "$srcDir\mgui\gui.cpp",
    "$srcDir\mgui\elements\button.cpp",
    "$srcDir\mgui\elements\draw.cpp",
    "$srcDir\mgui\elements\helpers.cpp",
    "$srcDir\mgui\elements\slider.cpp",
    "$srcDir\mgui\elements\textfield.cpp",
    "$srcDir\mgui\elements\window.cpp"
)
& $cl @compileCommon "/Fo$objDir\" @mguiSources
if ($LASTEXITCODE -ne 0) { Write-Host "FAILED: mgui compile" -ForegroundColor Red; exit 1 }

Write-Host "Compiling launcher main..." -ForegroundColor Cyan
& $cl @compileCommon "/Fo$objDir\" "$srcDir\main.cpp"
if ($LASTEXITCODE -ne 0) { Write-Host "FAILED: main compile" -ForegroundColor Red; exit 1 }

Write-Host "Linking launcher.exe..." -ForegroundColor Cyan
$objects = Get-ChildItem "$objDir\*.obj" | ForEach-Object { $_.FullName }
$linkArgs = @(
    "/OUT:$outDir\launcher.exe", "/MACHINE:X64", "/SUBSYSTEM:WINDOWS",
    "/DYNAMICBASE", "/NXCOMPAT", "/HIGHENTROPYVA",
    "/OPT:REF", "/OPT:ICF", "/nologo",
    "/LIBPATH:$msvcLib", "/LIBPATH:$sdkLibUm", "/LIBPATH:$sdkLibUcrt",
    "kernel32.lib", "user32.lib", "gdi32.lib", "advapi32.lib", "shell32.lib", "shlwapi.lib",
    "ole32.lib", "ws2_32.lib", "bcrypt.lib", "crypt32.lib",
    "d3d11.lib", "dxgi.lib", "d3dcompiler.lib"
) + $objects

& $link @linkArgs
if ($LASTEXITCODE -ne 0) { Write-Host "FAILED: link" -ForegroundColor Red; exit 1 }

Copy-Item -Force "$outDir\launcher.exe" "$dest\launcher.exe"
$size = (Get-Item "$dest\launcher.exe").Length
Write-Host "OK: launcher.exe built - $size bytes -> $dest" -ForegroundColor Green

$dumpbin = Join-Path (Join-Path $vsBase "VC\Tools\MSVC\$msvcVer\bin\Hostx64\x64") "dumpbin.exe"
if (Test-Path $dumpbin) {
    $dllPath = Join-Path $dest "launcher.exe"
    $depLines = & $dumpbin /DEPENDENTS $dllPath 2>&1
    $dynCrt = $depLines | Select-String -Pattern "MSVCP|VCRUNTIME|api-ms-win-crt"
    if ($dynCrt) {
        Write-Host "WARN: Dynamic CRT dependency detected - NOT fully portable" -ForegroundColor Yellow
    } else {
        Write-Host "OK: Fully static, portable across all Windows 10/11 PCs." -ForegroundColor Green
    }
}
