#pragma once
#include <Windows.h>
#include <TlHelp32.h>
#include <vector>
#include <winternl.h>
#include <set>
#include <string>
#include <atomic>
#include <iostream>
#include "MinHook.h"

#pragma comment(lib, "ntdll.lib")

inline thread_local bool g_allowFullLoadedClasses = false;

class ScopedFullLoadedClasses {
public:
    ScopedFullLoadedClasses() : previous_(g_allowFullLoadedClasses) {
        g_allowFullLoadedClasses = true;
    }
    ~ScopedFullLoadedClasses() {
        g_allowFullLoadedClasses = previous_;
    }
    ScopedFullLoadedClasses(const ScopedFullLoadedClasses&) = delete;
    ScopedFullLoadedClasses& operator=(const ScopedFullLoadedClasses&) = delete;
private:
    bool previous_;
};

enum class AvamHookAction {
    SKIP_ORIGINAL,
    CONTINUE_ORIGINAL
};

using AvamHookCallback = AvamHookAction(*)(CONTEXT*);

struct HWBP_ENTRY {
    void* target;
    AvamHookCallback callback;
    int dr_index;
};

struct SHADOW_DR {
    bool used;
    void* target;
};

class AvamHook {
private:
    static inline std::vector<HWBP_ENTRY> hooks;
    static inline SHADOW_DR shadowDr[4]{};
    static inline PVOID vehHandle = nullptr;

    static inline thread_local bool inHandler = false;
    static inline thread_local bool bypassShadow = false;

    typedef NTSTATUS(NTAPI* NtQueryInformationThread_t)(
        HANDLE,
        THREADINFOCLASS,
        PVOID,
        ULONG,
        PULONG
        );

    static inline NtQueryInformationThread_t NtQueryInformationThreadFn =
        (NtQueryInformationThread_t)GetProcAddress(
            GetModuleHandleA("ntdll.dll"),
            "NtQueryInformationThread"
        );

    static void* GetThreadStartAddress(HANDLE hThread) {
        void* start = nullptr;
        if (!NtQueryInformationThreadFn) return nullptr;

        NTSTATUS st = NtQueryInformationThreadFn(
            hThread,
            (THREADINFOCLASS)9,
            &start,
            sizeof(start),
            nullptr
        );
        if (st < 0) return nullptr;
        return start;
    }

    static LONG WINAPI VehHandler(PEXCEPTION_POINTERS p) {
        if (p->ExceptionRecord->ExceptionCode != EXCEPTION_SINGLE_STEP)
            return EXCEPTION_CONTINUE_SEARCH;

        CONTEXT* ctx = p->ContextRecord;

        if (inHandler)
            return EXCEPTION_CONTINUE_SEARCH;

        inHandler = true;

        DWORD64 rip = ctx->Rip;

        for (auto& h : hooks) {
            if (rip == (DWORD64)h.target) {
                ctx->Dr6 = 0; 
                DisableCurrentThread(h.dr_index);

                AvamHookAction act = AvamHookAction::CONTINUE_ORIGINAL;
                if (h.callback) {
                    act = h.callback(ctx);
                }

                if (act == AvamHookAction::SKIP_ORIGINAL) {
                    ctx->Rip = *(DWORD64*)ctx->Rsp;
                    ctx->Rsp += 8;
                }

                EnableCurrentThread(h.dr_index, h.target);

                inHandler = false;
                return EXCEPTION_CONTINUE_EXECUTION;
            }
        }

        inHandler = false;
        return EXCEPTION_CONTINUE_SEARCH;
    }

    static void SetHWBPThread(HANDLE hThread, int index, void* addr) {
        CONTEXT ctx{};
        ctx.ContextFlags = CONTEXT_DEBUG_REGISTERS;

        bypassShadow = true;
        if (!GetThreadContext(hThread, &ctx)) {
            bypassShadow = false;
            return;
        }

        (&ctx.Dr0)[index] = (DWORD64)addr;
        ctx.Dr7 |= (1ULL << (index * 2));
        ctx.Dr7 &= ~(3ULL << (16 + index * 4));
        ctx.Dr7 &= ~(3ULL << (18 + index * 4));

        SetThreadContext(hThread, &ctx);
        bypassShadow = false;
    }

    static void ApplyAllThreads(void* addr, int dr) {
        THREADENTRY32 te{ sizeof(te) };
        HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0);
        if (snap == INVALID_HANDLE_VALUE) return;

        if (!Thread32First(snap, &te)) {
            CloseHandle(snap);
            return;
        }

        do {
            if (te.th32OwnerProcessID != GetCurrentProcessId())
                continue;

            HANDLE th = OpenThread(
                THREAD_GET_CONTEXT | THREAD_SET_CONTEXT | THREAD_QUERY_INFORMATION,
                FALSE,
                te.th32ThreadID
            );

            if (!th)
                continue;

            SetHWBPThread(th, dr, addr);

            CloseHandle(th);

        } while (Thread32Next(snap, &te));

        CloseHandle(snap);
    }

    typedef NTSTATUS(NTAPI* NtGetContextThread_t)(HANDLE, PCONTEXT);
    typedef NTSTATUS(NTAPI* NtSetContextThread_t)(HANDLE, PCONTEXT);

    static inline NtGetContextThread_t oNtGetContextThread = nullptr;
    static inline NtSetContextThread_t oNtSetContextThread = nullptr;

    static NTSTATUS NTAPI hkNtGetContextThread(HANDLE hThread, PCONTEXT ctx) {
        NTSTATUS st = oNtGetContextThread(hThread, ctx);

        if (!bypassShadow && (ctx->ContextFlags & CONTEXT_DEBUG_REGISTERS)) {
            for (int i = 0; i < 4; i++) {
                if (shadowDr[i].used) {
                    (&ctx->Dr0)[i] = 0;
                    ctx->Dr7 &= ~(3ULL << (16 + i * 4));
                    ctx->Dr7 &= ~(3ULL << (18 + i * 4));
                    ctx->Dr7 &= ~(1ULL << (i * 2));
                    ctx->Dr7 &= ~(1ULL << (i * 2 + 1));
                }
            }
        }
        return st;
    }

    static NTSTATUS NTAPI hkNtSetContextThread(HANDLE hThread, PCONTEXT ctx) {
        if (!bypassShadow && (ctx->ContextFlags & CONTEXT_DEBUG_REGISTERS)) {
            for (int i = 0; i < 4; i++) {
                if (shadowDr[i].used) {
                    (&ctx->Dr0)[i] = (DWORD64)shadowDr[i].target;
                    ctx->Dr7 |= (1ULL << (i * 2));
                    ctx->Dr7 &= ~(3ULL << (16 + i * 4));
                    ctx->Dr7 &= ~(3ULL << (18 + i * 4));
                }
            }
        }
        return oNtSetContextThread(hThread, ctx);
    }

    static void InitShadowDR() {
        MH_STATUS status = MH_Initialize();
        if (status != MH_OK && status != MH_ERROR_ALREADY_INITIALIZED) {
            return;
        }

        void* ntdll = GetModuleHandleA("ntdll.dll");
        if (!ntdll) {
            return;
        }

        void* getNtGetContextThread = GetProcAddress((HMODULE)ntdll, "NtGetContextThread");
        void* getNtSetContextThread = GetProcAddress((HMODULE)ntdll, "NtSetContextThread");

        if (!getNtGetContextThread || !getNtSetContextThread) {
            return;
        }

        status = MH_CreateHook(
            getNtGetContextThread,
            hkNtGetContextThread,
            (LPVOID*)&oNtGetContextThread
        );

        if (status != MH_OK) {
        } else {
        }

        status = MH_CreateHook(
            getNtSetContextThread,
            hkNtSetContextThread,
            (LPVOID*)&oNtSetContextThread
        );

        if (status != MH_OK) {
        } else {
        }

        status = MH_EnableHook(MH_ALL_HOOKS);
        if (status != MH_OK) {
        } else {
        }
    }

public:
    static bool Init() {

        InitShadowDR();

        if (!vehHandle) {
            vehHandle = AddVectoredExceptionHandler(1, VehHandler);
            if (!vehHandle) {
                return false;
            }
        }

        return vehHandle != nullptr;
    }

    static bool Hook(void* target, AvamHookCallback cb = nullptr, void* threadStartAddr = nullptr) {
        if (!target) {
            return false;
        }

        int freeDr = -1;
        for (int i = 0; i < 4; i++) {
            if (!shadowDr[i].used) {
                freeDr = i;
                break;
            }
        }

        if (freeDr == -1) {
            return false;
        }

        shadowDr[freeDr].used = true;
        shadowDr[freeDr].target = target;

        HWBP_ENTRY h{};
        h.target = target;
        h.callback = cb;
        h.dr_index = freeDr;

        hooks.push_back(h);

        ApplyAllThreads(target, freeDr);

        int appliedCount = VerifyHookApplied(target, freeDr);

        return appliedCount > 0;
    }

    static int VerifyHookApplied(void* addr, int dr) {
        int count = 0;
        THREADENTRY32 te{ sizeof(te) };
        HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0);
        if (snap == INVALID_HANDLE_VALUE) return 0;

        if (!Thread32First(snap, &te)) {
            CloseHandle(snap);
            return 0;
        }

        do {
            if (te.th32OwnerProcessID != GetCurrentProcessId())
                continue;

            HANDLE th = OpenThread(
                THREAD_GET_CONTEXT | THREAD_QUERY_INFORMATION,
                FALSE,
                te.th32ThreadID
            );

            if (!th)
                continue;

            CONTEXT ctx{};
            ctx.ContextFlags = CONTEXT_DEBUG_REGISTERS;

            bypassShadow = true;
            if (GetThreadContext(th, &ctx)) {
                DWORD64 drValue = (&ctx.Dr0)[dr];
                if (drValue == (DWORD64)addr) {
                    count++;
                }
            }
            bypassShadow = false;

            CloseHandle(th);

        } while (Thread32Next(snap, &te));

        CloseHandle(snap);
        return count;
    }

    static void RefreshHooks() {
        int totalApplied = 0;

        for (const auto& h : hooks) {
            ApplyAllThreads(h.target, h.dr_index);
            int count = VerifyHookApplied(h.target, h.dr_index);
            totalApplied += count;
        }

    }

    static bool RetryHook(void* target) {
        for (auto& h : hooks) {
            if (h.target == target) {
                ApplyAllThreads(h.target, h.dr_index);
                int count = VerifyHookApplied(h.target, h.dr_index);
                return count > 0;
            }
        }
        return false;
    }

    static void DisableCurrentThread(int dr) {
        CONTEXT ctx{};
        ctx.ContextFlags = CONTEXT_DEBUG_REGISTERS;

        bypassShadow = true;
        GetThreadContext(GetCurrentThread(), &ctx);

        switch (dr) {
        case 0: ctx.Dr0 = 0; break;
        case 1: ctx.Dr1 = 0; break;
        case 2: ctx.Dr2 = 0; break;
        case 3: ctx.Dr3 = 0; break;
        default: break;
        }

        ctx.Dr7 &= ~(1ULL << (dr * 2));

        SetThreadContext(GetCurrentThread(), &ctx);
        bypassShadow = false;
    }

    static void EnableCurrentThread(int dr, void* addr) {
        CONTEXT ctx{};
        ctx.ContextFlags = CONTEXT_DEBUG_REGISTERS;

        bypassShadow = true;
        GetThreadContext(GetCurrentThread(), &ctx);

        switch (dr) {
        case 0: ctx.Dr0 = (DWORD64)addr; break;
        case 1: ctx.Dr1 = (DWORD64)addr; break;
        case 2: ctx.Dr2 = (DWORD64)addr; break;
        case 3: ctx.Dr3 = (DWORD64)addr; break;
        default: break;
        }

        ctx.Dr7 |= (1ULL << (dr * 2));

        SetThreadContext(GetCurrentThread(), &ctx);
        bypassShadow = false;
    }

    static void Shutdown() {
        if (vehHandle) {
            RemoveVectoredExceptionHandler(vehHandle);
            vehHandle = nullptr;
        }

        MH_DisableHook(MH_ALL_HOOKS);
        MH_Uninitialize();

        hooks.clear();
        for (auto& s : shadowDr)
            s = {};
    }
};
