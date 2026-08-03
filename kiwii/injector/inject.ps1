param(
    [Parameter(Mandatory=$true)][string]$DllPath,
    [Parameter(Mandatory=$true)][string]$ProcessName
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DllPath)) {
    Write-Host "[X] DLL not found: $DllPath" -ForegroundColor Red
    exit 2
}

$DllPath = (Resolve-Path -LiteralPath $DllPath).Path

$baseName = [System.IO.Path]::GetFileNameWithoutExtension($ProcessName)
$procs = @(Get-Process -Name $baseName -ErrorAction SilentlyContinue)
if ($procs.Count -eq 0) {
    Write-Host "[X] Process not found: $ProcessName" -ForegroundColor Red
    exit 3
}
if ($procs.Count -gt 1) {
    Write-Host "[!] $($procs.Count) instances found, using first (PID $($procs[0].Id))." -ForegroundColor Yellow
}
$target = $procs[0]
Write-Host "[+] PID: $($target.Id)  Handle: $($target.Handle)" -ForegroundColor Cyan

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

public static class KiwiiInject {
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern IntPtr OpenProcess(uint dwDesiredAccess, bool bInheritHandle, int dwProcessId);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern IntPtr VirtualAllocEx(IntPtr hProcess, IntPtr lpAddress, UIntPtr dwSize, uint flAllocationType, uint flProtect);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool VirtualFreeEx(IntPtr hProcess, IntPtr lpAddress, UIntPtr dwSize, uint dwFreeType);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool WriteProcessMemory(IntPtr hProcess, IntPtr lpBaseAddress, byte[] lpBuffer, UIntPtr nSize, out UIntPtr lpNumberOfBytesWritten);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Ansi)]
    public static extern IntPtr GetProcAddress(IntPtr hModule, string procName);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Ansi)]
    public static extern IntPtr GetModuleHandleA(string lpModuleName);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern IntPtr CreateRemoteThread(IntPtr hProcess, IntPtr lpThreadAttributes, UIntPtr dwStackSize, IntPtr lpStartAddress, IntPtr lpParameter, uint dwCreationFlags, IntPtr lpThreadId);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern uint WaitForSingleObject(IntPtr hHandle, uint dwMilliseconds);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool GetExitCodeThread(IntPtr hThread, out uint lpExitCode);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool CloseHandle(IntPtr hObject);
}
"@

$PROCESS_ALL_ACCESS = 0x001F0FFF
$MEM_COMMIT_RESERVE = 0x00003000
$PAGE_READWRITE     = 0x04
$MEM_RELEASE        = 0x8000
$INFINITE           = [uint32]::MaxValue

$hProc = [KiwiiInject]::OpenProcess($PROCESS_ALL_ACCESS, $false, $target.Id)
if ($hProc -eq [IntPtr]::Zero) {
    $err = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
    Write-Host "[X] OpenProcess FAILED (err=$err). Try running as Administrator." -ForegroundColor Red
    exit 4
}

try {
    $dllBytes = [System.Text.Encoding]::ASCII.GetBytes($DllPath + [char]0)
    $size = [UIntPtr]::new([uint32]$dllBytes.Length)

    $remoteMem = [KiwiiInject]::VirtualAllocEx($hProc, [IntPtr]::Zero, $size, $MEM_COMMIT_RESERVE, $PAGE_READWRITE)
    if ($remoteMem -eq [IntPtr]::Zero) {
        $err = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
        Write-Host "[X] VirtualAllocEx FAILED (err=$err)" -ForegroundColor Red
        exit 5
    }

    try {
        $written = [UIntPtr]::Zero
        if (-not [KiwiiInject]::WriteProcessMemory($hProc, $remoteMem, $dllBytes, $size, [ref]$written)) {
            $err = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
            Write-Host "[X] WriteProcessMemory FAILED (err=$err)" -ForegroundColor Red
            exit 6
        }

        $k32 = [KiwiiInject]::GetModuleHandleA("kernel32.dll")
        $loadLib = [KiwiiInject]::GetProcAddress($k32, "LoadLibraryA")
        if ($loadLib -eq [IntPtr]::Zero) {
            Write-Host "[X] LoadLibraryA adresi alinamadi" -ForegroundColor Red
            exit 7
        }

        $hThread = [KiwiiInject]::CreateRemoteThread($hProc, [IntPtr]::Zero, [UIntPtr]::Zero, $loadLib, $remoteMem, 0, [IntPtr]::Zero)
        if ($hThread -eq [IntPtr]::Zero) {
            $err = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
            Write-Host "[X] CreateRemoteThread FAILED (err=$err)" -ForegroundColor Red
            exit 8
        }

        try {
            [void][KiwiiInject]::WaitForSingleObject($hThread, $INFINITE)
            $exitCode = 0
            [void][KiwiiInject]::GetExitCodeThread($hThread, [ref]$exitCode)
            if ($exitCode -eq 0) {
                Write-Host "[X] LoadLibraryA remote thread donus 0 - DLL yuklenmedi (yanlis mimari? bagimlilik eksik?)" -ForegroundColor Red
                exit 9
            }
            Write-Host "[+] LoadLibraryA return: 0x$('{0:X}' -f $exitCode)" -ForegroundColor Green
            Write-Host "[+] DLL basariyla yuklendi." -ForegroundColor Green
            exit 0
        } finally {
            [void][KiwiiInject]::CloseHandle($hThread)
        }
    } finally {
        [void][KiwiiInject]::VirtualFreeEx($hProc, $remoteMem, [UIntPtr]::Zero, $MEM_RELEASE)
    }
} finally {
    [void][KiwiiInject]::CloseHandle($hProc)
}
