$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

$msvcVer  = "14.50.35717"
$vsBase   = "C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools"
$sdkBase  = "C:\Program Files (x86)\Windows Kits\10"
$sdkVer   = "10.0.26100.0"

$srcDir   = Join-Path $scriptRoot "cpp\src"
$incDir   = Join-Path $scriptRoot "cpp\include"
$libDir   = Join-Path $scriptRoot "cpp\lib"
$objDir   = Join-Path $scriptRoot "cpp\obj\Release"
$outDir   = Join-Path $scriptRoot "cpp\bin\Release"
$dest     = "C:\kiwii"

$cl       = "$vsBase\VC\Tools\MSVC\$msvcVer\bin\Hostx64\x64\cl.exe"
$link     = "$vsBase\VC\Tools\MSVC\$msvcVer\bin\Hostx64\x64\link.exe"

$msvcInc      = "$vsBase\VC\Tools\MSVC\$msvcVer\include"
$msvcLib      = "$vsBase\VC\Tools\MSVC\$msvcVer\lib\x64"
$sdkIncUm     = "$sdkBase\Include\$sdkVer\um"
$sdkIncShared = "$sdkBase\Include\$sdkVer\shared"
$sdkIncUcrt   = "$sdkBase\Include\$sdkVer\ucrt"
$sdkIncWinrt  = "$sdkBase\Include\$sdkVer\cppwinrt"
$sdkLibUm     = "$sdkBase\Lib\$sdkVer\um\x64"
$sdkLibUcrt   = "$sdkBase\Lib\$sdkVer\ucrt\x64"

foreach ($d in @($objDir, $outDir, $dest)) {
    if (-not (Test-Path $d)) { New-Item -ItemType Directory -Path $d | Out-Null }
}

Write-Host "[C++] Compiling (portable /MT static CRT)..."
$compileArgs = @(
    "/c", "/O2", "/MT", "/std:c++17", "/EHsc", "/await",
    "/DNDEBUG", "/D_WINDOWS", "/D_USRDLL",
    "/DWINVER=0x0A00", "/D_WIN32_WINNT=0x0A00", "/DNTDDI_VERSION=0x0A000000",
    "/DWIN32_LEAN_AND_MEAN", "/DNOMINMAX", "/D_CRT_SECURE_NO_WARNINGS",
    "/GS", "/Gy", "/Zc:inline", "/Zc:wchar_t",
    "/I$incDir", "/I$msvcInc", "/I$sdkIncUm", "/I$sdkIncShared", "/I$sdkIncUcrt", "/I$sdkIncWinrt",
    "/Fo$objDir\",
    "$srcDir\main.cpp", "$srcDir\jni_helper.cpp", "$srcDir\jar_loader.cpp"
)
& $cl @compileArgs
if ($LASTEXITCODE -ne 0) { Write-Host "[FAILED] Compile"; exit 1 }

Write-Host "[C++] Linking (static CRT, ASLR + DEP + high-entropy)..."
$linkArgs = @(
    "/DLL", "/OUT:$outDir\kiwii.dll", "/MACHINE:X64", "/SUBSYSTEM:WINDOWS",
    "/DYNAMICBASE", "/NXCOMPAT", "/HIGHENTROPYVA",
    "/OPT:REF", "/OPT:ICF",
    "/LIBPATH:$msvcLib", "/LIBPATH:$sdkLibUm", "/LIBPATH:$sdkLibUcrt", "/LIBPATH:$libDir",
    "$objDir\main.obj", "$objDir\jni_helper.obj", "$objDir\jar_loader.obj",
    "libMinHook-x64.lib", "kernel32.lib", "user32.lib", "ole32.lib",
    "advapi32.lib", "opengl32.lib", "DbgHelp.lib", "ws2_32.lib"
)
& $link @linkArgs
if ($LASTEXITCODE -ne 0) { Write-Host "[FAILED] Link"; exit 1 }

Copy-Item -Force "$outDir\kiwii.dll" "$dest\kiwii.dll"

Write-Host "Verifying static CRT..."
$dumpbin = Join-Path (Join-Path $vsBase "VC\Tools\MSVC\$msvcVer\bin\Hostx64\x64") "dumpbin.exe"
if (Test-Path $dumpbin) {
    $dllPath = Join-Path $dest "kiwii.dll"
    $depLines = & $dumpbin /DEPENDENTS $dllPath 2>&1
    $dynCrt = $depLines | Select-String -Pattern "MSVCP|VCRUNTIME|api-ms-win-crt"
    if ($dynCrt) {
        Write-Host "WARN: Dynamic CRT dependency detected - NOT fully portable:" -ForegroundColor Yellow
        $dynCrt | ForEach-Object { Write-Host $_ -ForegroundColor Yellow }
    } else {
        Write-Host "OK: Fully static, no VC++ Redistributable required." -ForegroundColor Green
    }
}

Write-Host "Done: kiwii.dll -> $dest"
