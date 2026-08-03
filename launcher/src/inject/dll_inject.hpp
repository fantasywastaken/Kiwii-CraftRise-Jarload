#pragma once
#include <string>
#include <windows.h>
#include <tlhelp32.h>
#include <thread>
#include <chrono>
#include "../util/logger.hpp"

namespace kiwii::inject {

    inline DWORD findProcessByName(const std::wstring& name) {
        HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
        if (snap == INVALID_HANDLE_VALUE) return 0;
        PROCESSENTRY32W pe{}; pe.dwSize = sizeof(pe);
        DWORD result = 0;
        if (Process32FirstW(snap, &pe)) {
            do {
                if (_wcsicmp(pe.szExeFile, name.c_str()) == 0) { result = pe.th32ProcessID; break; }
            } while (Process32NextW(snap, &pe));
        }
        CloseHandle(snap);
        return result;
    }

    inline bool injectDll(DWORD pid, const std::string& dllPath) {
        HANDLE proc = OpenProcess(PROCESS_ALL_ACCESS, FALSE, pid);
        if (!proc) { logger::warn("OpenProcess failed: " + std::to_string(GetLastError())); return false; }

        SIZE_T bytesToWrite = dllPath.size() + 1;
        LPVOID remote = VirtualAllocEx(proc, nullptr, bytesToWrite, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
        if (!remote) { logger::warn("VirtualAllocEx failed"); CloseHandle(proc); return false; }

        if (!WriteProcessMemory(proc, remote, dllPath.c_str(), bytesToWrite, nullptr)) {
            logger::warn("WriteProcessMemory failed");
            VirtualFreeEx(proc, remote, 0, MEM_RELEASE); CloseHandle(proc); return false;
        }

        HMODULE kernel = GetModuleHandleA("kernel32.dll");
        LPTHREAD_START_ROUTINE loadLib = (LPTHREAD_START_ROUTINE) GetProcAddress(kernel, "LoadLibraryA");
        if (!loadLib) { VirtualFreeEx(proc, remote, 0, MEM_RELEASE); CloseHandle(proc); return false; }

        HANDLE thread = CreateRemoteThread(proc, nullptr, 0, loadLib, remote, 0, nullptr);
        if (!thread) {
            logger::warn("CreateRemoteThread failed");
            VirtualFreeEx(proc, remote, 0, MEM_RELEASE); CloseHandle(proc); return false;
        }

        WaitForSingleObject(thread, INFINITE);
        DWORD exitCode = 0;
        GetExitCodeThread(thread, &exitCode);
        CloseHandle(thread);
        VirtualFreeEx(proc, remote, 0, MEM_RELEASE);
        CloseHandle(proc);

        if (exitCode == 0) { logger::warn("LoadLibraryA remote thread returned 0"); return false; }
        logger::info("DLL injected into PID " + std::to_string(pid) + " (LoadLibraryA=0x" +
                     std::to_string(exitCode) + ")");
        return true;
    }

    inline DWORD findAnyOf(const std::vector<std::wstring>& names) {
        for (const auto& n : names) {
            DWORD pid = findProcessByName(n);
            if (pid != 0) return pid;
        }
        return 0;
    }

    inline bool waitAndInject(const std::wstring& processName, const std::string& dllPath,
                              int timeoutSeconds = 300) {
        std::vector<std::wstring> candidates = { processName, L"javaw.exe", L"java.exe" };
        logger::info("Waiting for craftrise-x64.exe / javaw.exe / java.exe");
        auto start = std::chrono::steady_clock::now();
        DWORD pid = 0;
        std::wstring found;
        while (pid == 0) {
            for (const auto& n : candidates) {
                DWORD p = findProcessByName(n);
                if (p != 0) { pid = p; found = n; break; }
            }
            auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(
                    std::chrono::steady_clock::now() - start).count();
            if (elapsed > timeoutSeconds) { logger::warn("Wait for process timed out"); return false; }
            if (pid == 0) std::this_thread::sleep_for(std::chrono::milliseconds(500));
        }
        std::string foundNarrow(found.begin(), found.end());
        logger::info("Process detected " + foundNarrow + " PID=" + std::to_string(pid) + ", waiting 20s for JVM boot");
        std::this_thread::sleep_for(std::chrono::seconds(20));
        DWORD pid2 = findProcessByName(L"javaw.exe");
        if (pid2 == 0) pid2 = findProcessByName(L"java.exe");
        if (pid2 != 0 && pid2 != pid) {
            logger::info("Preferring javaw/java PID=" + std::to_string(pid2) + " over " + std::to_string(pid));
            pid = pid2;
        }
        return injectDll(pid, dllPath);
    }
}
