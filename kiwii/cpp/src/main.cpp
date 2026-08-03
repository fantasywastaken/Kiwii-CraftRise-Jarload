#define _CRT_SECURE_NO_WARNINGS
#define NOMINMAX

#include <Windows.h>
#include <gl/GL.h>
#include <gl/GLU.h>
#include <cstdio>
#include <iostream>
#include <thread>
#include <vector>
#include <unordered_set>
#include <chrono>
#include <fstream>
#include <DbgHelp.h>
#include "jar_loader.h"   
#include "jni_helper.h"
#include "bypass.hpp"
#include "MinHook.h"

#pragma comment(lib, "opengl32.lib")
#pragma comment(lib, "glu32.lib")
#pragma comment(lib, "DbgHelp.lib")

JavaVM* g_jvm = nullptr;
JNIEnv* g_env = nullptr;
jvmtiEnv* g_jvmti = nullptr;

static bool isClientInitialized = false;

static void WriteStackTrace(uintptr_t rsp, FILE* f) {
    uintptr_t* stack = (uintptr_t*)rsp;
    HMODULE hJvm  = GetModuleHandleA("jvm.dll");
    HMODULE hSelf = GetModuleHandleA("kiwii.dll");
    for (int i = 0; i < 20; i++) {
        __try {
            uintptr_t addr = stack[i];
            if (addr > 0x10000) {
                fprintf(f, "  [%2d] 0x%016llx", i, (unsigned long long)addr);
                if (hJvm  && addr >= (uintptr_t)hJvm  && addr < (uintptr_t)hJvm  + 0x800000)
                    fprintf(f, "  (jvm.dll+0x%llx)",  (unsigned long long)(addr - (uintptr_t)hJvm));
                else if (hSelf && addr >= (uintptr_t)hSelf && addr < (uintptr_t)hSelf + 0x100000)
                    fprintf(f, "  (kiwii.dll+0x%llx)", (unsigned long long)(addr - (uintptr_t)hSelf));
                else {

                    HMODULE hMod = nullptr;
                    if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                                           GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                                           (LPCSTR)addr, &hMod) && hMod) {
                        char modName[MAX_PATH] = {};
                        GetModuleFileNameA(hMod, modName, MAX_PATH);

                        const char* slash = strrchr(modName, '\\');
                        fprintf(f, "  (%s+0x%llx)", slash ? slash + 1 : modName,
                                (unsigned long long)(addr - (uintptr_t)hMod));
                    }
                }
                fprintf(f, "\n");
            }
        } __except(1) { break; }
    }
}

void WriteCrashLog(EXCEPTION_POINTERS* pExceptionInfo) {
    FILE* f = nullptr;
    fopen_s(&f, "C:\\kiwii\\crash.log", "a");
    if (!f) return;

    SYSTEMTIME st;
    GetLocalTime(&st);

    fprintf(f, "\n");
    fprintf(f, "========================================\n");
    fprintf(f, "  CRASH REPORT  %04d-%02d-%02d %02d:%02d:%02d\n",
        st.wYear, st.wMonth, st.wDay, st.wHour, st.wMinute, st.wSecond);
    fprintf(f, "========================================\n");

    if (!pExceptionInfo) {
        fprintf(f, "[No exception info]\n");
        fclose(f);
        return;
    }

    EXCEPTION_RECORD* er  = pExceptionInfo->ExceptionRecord;
    CONTEXT*          ctx = pExceptionInfo->ContextRecord;
    void* crashAddr = er->ExceptionAddress;

    fprintf(f, "\n[EXCEPTION]\n");
    fprintf(f, "  Code    : 0x%08lx\n", er->ExceptionCode);
    fprintf(f, "  Address : 0x%016llx\n", (unsigned long long)crashAddr);
    fprintf(f, "  Flags   : 0x%08lx\n", er->ExceptionFlags);
    if (er->ExceptionCode == EXCEPTION_ACCESS_VIOLATION && er->NumberParameters >= 2) {
        fprintf(f, "  AV Type : %s\n", er->ExceptionInformation[0] == 1 ? "WRITE" : "READ");
        fprintf(f, "  AV Addr : 0x%016llx\n", (unsigned long long)er->ExceptionInformation[1]);
    }

    {
        HMODULE hMod = nullptr;
        if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                               GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                               (LPCSTR)crashAddr, &hMod) && hMod) {
            char modName[MAX_PATH] = {};
            GetModuleFileNameA(hMod, modName, MAX_PATH);
            const char* slash = strrchr(modName, '\\');
            fprintf(f, "  Module  : %s+0x%llx\n",
                    slash ? slash + 1 : modName,
                    (unsigned long long)((uintptr_t)crashAddr - (uintptr_t)hMod));
        }
    }

    fprintf(f, "\n[REGISTERS]\n");
    fprintf(f, "  RAX=%016llx  RBX=%016llx  RCX=%016llx\n",
        (unsigned long long)ctx->Rax, (unsigned long long)ctx->Rbx, (unsigned long long)ctx->Rcx);
    fprintf(f, "  RDX=%016llx  RSI=%016llx  RDI=%016llx\n",
        (unsigned long long)ctx->Rdx, (unsigned long long)ctx->Rsi, (unsigned long long)ctx->Rdi);
    fprintf(f, "  R8 =%016llx  R9 =%016llx  R10=%016llx\n",
        (unsigned long long)ctx->R8,  (unsigned long long)ctx->R9,  (unsigned long long)ctx->R10);
    fprintf(f, "  R11=%016llx  R12=%016llx  R13=%016llx\n",
        (unsigned long long)ctx->R11, (unsigned long long)ctx->R12, (unsigned long long)ctx->R13);
    fprintf(f, "  R14=%016llx  R15=%016llx\n",
        (unsigned long long)ctx->R14, (unsigned long long)ctx->R15);
    fprintf(f, "  RSP=%016llx  RBP=%016llx  RIP=%016llx\n",
        (unsigned long long)ctx->Rsp, (unsigned long long)ctx->Rbp, (unsigned long long)ctx->Rip);
    fprintf(f, "  EFL=%08lx\n", ctx->EFlags);

    fprintf(f, "\n[BYTES AT RIP]\n  ");
    __try {
        uint8_t* rip = (uint8_t*)ctx->Rip;
        if (rip && (uintptr_t)rip > 0x1000) {
            for (int i = 0; i < 16; i++)
                fprintf(f, "%02x ", rip[i]);
            fprintf(f, "\n");
        }
    } __except(1) { fprintf(f, "[unreadable]\n"); }

    fprintf(f, "\n[STACK TRACE]\n");
    WriteStackTrace(ctx->Rsp, f);

    fprintf(f, "\n[CALL STACK (StackWalk64)]\n");
    __try {
        HANDLE hProcess = GetCurrentProcess();
        HANDLE hThread  = GetCurrentThread();
        SymInitialize(hProcess, NULL, TRUE);

        STACKFRAME64 sf = {};
        sf.AddrPC.Offset    = ctx->Rip;
        sf.AddrPC.Mode      = AddrModeFlat;
        sf.AddrFrame.Offset = ctx->Rbp;
        sf.AddrFrame.Mode   = AddrModeFlat;
        sf.AddrStack.Offset = ctx->Rsp;
        sf.AddrStack.Mode   = AddrModeFlat;

        CONTEXT ctxCopy = *ctx;

        for (int frame = 0; frame < 32; frame++) {
            if (!StackWalk64(IMAGE_FILE_MACHINE_AMD64, hProcess, hThread,
                             &sf, &ctxCopy, NULL,
                             SymFunctionTableAccess64, SymGetModuleBase64, NULL))
                break;
            if (sf.AddrPC.Offset == 0) break;

            uintptr_t pc = (uintptr_t)sf.AddrPC.Offset;
            fprintf(f, "  [%2d] 0x%016llx", frame, (unsigned long long)pc);

            HMODULE hMod = nullptr;
            if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                                   GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                                   (LPCSTR)pc, &hMod) && hMod) {
                char modName[MAX_PATH] = {};
                GetModuleFileNameA(hMod, modName, MAX_PATH);
                const char* slash = strrchr(modName, '\\');
                fprintf(f, "  %s+0x%llx",
                        slash ? slash + 1 : modName,
                        (unsigned long long)(pc - (uintptr_t)hMod));
            }

            char symBuf[sizeof(SYMBOL_INFO) + MAX_PATH] = {};
            SYMBOL_INFO* sym = (SYMBOL_INFO*)symBuf;
            sym->SizeOfStruct = sizeof(SYMBOL_INFO);
            sym->MaxNameLen   = MAX_PATH;
            DWORD64 symDisp = 0;
            if (SymFromAddr(hProcess, pc, &symDisp, sym))
                fprintf(f, "  %s+0x%llx", sym->Name, (unsigned long long)symDisp);

            fprintf(f, "\n");
        }

        SymCleanup(hProcess);
    } __except(1) { fprintf(f, "  [StackWalk64 failed]\n"); }

    fprintf(f, "\n[STATE]\n");
    fprintf(f, "  ClientInit : %s\n", isClientInitialized ? "Yes" : "No");
    fprintf(f, "  JVM        : %p\n", g_jvm);
    fprintf(f, "  JNIEnv     : %p\n", g_env);
    fprintf(f, "  JVMTI      : %p\n", g_jvmti);

    fprintf(f, "\n========================================\n\n");
    fclose(f);
    printf("[-] Crash log -> C:\\kiwii\\crash.log\n");
}

LONG WINAPI ExceptionHandler(EXCEPTION_POINTERS* pExceptionInfo) {
    printf("[-] EXCEPTION CAUGHT!\n");
    WriteCrashLog(pExceptionInfo);
    return EXCEPTION_CONTINUE_SEARCH;
}

namespace GLHooks {
    typedef void(__stdcall* wglSwapBuffers_t)(HDC);
    wglSwapBuffers_t original_wglSwapBuffers = nullptr;
}

void InitializeLogging() {

    AllocConsole();

    HWND hCon = GetConsoleWindow();
    if (hCon) ShowWindow(hCon, SW_HIDE);

    SetConsoleOutputCP(CP_UTF8);
    SetConsoleCP(CP_UTF8);

    FILE* pCout;
    freopen_s(&pCout, "CONOUT$", "w", stdout);
    freopen_s(&pCout, "CONOUT$", "w", stderr);
}

static bool g_jarLoaded = false;

static jvmtiError(JNICALL* orig_GetLoadedClasses)(
    jvmtiEnv* env,
    jint* class_count_ptr,
    jclass** classes_ptr
) = nullptr;

static std::atomic<int> g_getLoadedClassesCallCount{ 0 };

std::atomic<bool> g_bypassHook{ false };

static jclass* g_preJarClasses  = nullptr; 
static jint    g_preJarCount    = 0;
static std::atomic<bool> g_preJarReady{ false };

AvamHookAction Hook_GetLoadedClasses(CONTEXT* ctx) {
    auto jvmti_env       = (jvmtiEnv*)ctx->Rcx;
    auto class_count_ptr = (jint*)ctx->Rdx;
    auto classes_ptr     = (jclass**)ctx->R8;

    jclass* classes     = nullptr;
    jint    class_count = 0;

    jvmtiError ret = orig_GetLoadedClasses(jvmti_env, &class_count, &classes);

    int callNum = ++g_getLoadedClassesCallCount;

    if (g_bypassHook.load()) {
        if (class_count_ptr) *class_count_ptr = class_count;
        if (classes_ptr)     *classes_ptr     = classes;
        ctx->Rax = ret;
        return AvamHookAction::SKIP_ORIGINAL;
    }

    if (g_preJarReady.load() && g_preJarClasses && g_preJarCount > 0) {
        if (class_count_ptr) *class_count_ptr = g_preJarCount;
        if (classes_ptr) {
            auto Allocate_ = (jvmtiError(JNICALL*)(jvmtiEnv*, jlong, unsigned char**))unhook(jvmti_env->functions->Allocate);
            jclass* copyClasses = nullptr;

            if (Allocate_(jvmti_env, g_preJarCount * sizeof(jclass), (unsigned char**)&copyClasses) == JVMTI_ERROR_NONE && copyClasses) {

                JNIEnv* current_env = nullptr;
                extern JavaVM* g_jvm;
                if (g_jvm->GetEnv((void**)&current_env, JNI_VERSION_1_6) == JNI_OK && current_env) {
                    auto NewLocalRef_ = (jobject(JNICALL*)(JNIEnv*, jobject))unhook(current_env->functions->NewLocalRef);
                    for (int i = 0; i < g_preJarCount; i++) {
                        copyClasses[i] = (jclass)NewLocalRef_(current_env, g_preJarClasses[i]);
                    }
                } else {

                    memcpy(copyClasses, g_preJarClasses, g_preJarCount * sizeof(jclass));
                }
                *classes_ptr = copyClasses;
            } else {
                *classes_ptr = g_preJarClasses; 
            }
        }

        if (classes) {
            auto Deallocate_ = (jvmtiError(JNICALL*)(jvmtiEnv*, unsigned char*))unhook(jvmti_env->functions->Deallocate);
            Deallocate_(jvmti_env, (unsigned char*)classes);
        }

        ctx->Rax = JVMTI_ERROR_NONE;

        printf("hwbp #%d tetiklendi cache: %d (irl: %d)\n", callNum, (int)g_preJarCount, (int)class_count);

        return AvamHookAction::SKIP_ORIGINAL;
    }

    if (class_count_ptr) *class_count_ptr = class_count;
    if (classes_ptr)     *classes_ptr     = classes;
    ctx->Rax = ret;
    return AvamHookAction::SKIP_ORIGINAL;
}

static std::atomic<bool> g_hwbp130_pending{ false };

static void WriteHwbp130Log(JNIEnv* env) {
    if (!g_hwbp130_pending.load()) return;
    if (!JNIHelper::oCallObjectMethod || !JNIHelper::oFindClass) return;

    g_hwbp130_pending.store(false);

    if (!g_preJarClasses || g_preJarCount <= 0) return;

    jclass    classClass = unhook(env->functions->FindClass)(env, "java/lang/Class");
    jmethodID getNameMid = unhook(env->functions->GetMethodID)(env, classClass, "getName", "()Ljava/lang/String;");

    if (!classClass || !getNameMid) {
        printf("[HWBP] WriteHwbp130Log: class/method not found\n");
        return;
    }

    FILE* f = nullptr;
    fopen_s(&f, "C:\\kiwii\\logs\\hwbp_130.txt", "w");
    if (!f) return;

    fprintf(f, "Pre-JAR snapshot: %d class (Kiwii gizli)\n\n", (int)g_preJarCount);

    for (int i = 0; i < (int)g_preJarCount; i++) {
        if (!g_preJarClasses[i]) { fprintf(f, "[%d] (null)\n", i); continue; }

        jstring jname = (jstring)unhook(env->functions->CallObjectMethod)(env, g_preJarClasses[i], getNameMid);

        if (env->ExceptionCheck()) { env->ExceptionClear(); fprintf(f, "[%d] (exception)\n", i); continue; }
        if (!jname) { fprintf(f, "[%d] (null name)\n", i); continue; }

        const char* str = unhook(env->functions->GetStringUTFChars)(env, jname, nullptr);
        if (str) {
            bool isKiwii = strstr(str, "kiwii") || strstr(str, "Kiwii");
            fprintf(f, "[%d] %s%s\n", i, str, isKiwii ? " <-- KIWII (BUG!)" : "");
            if (isKiwii) printf("[HWBP] KIWII CACHE'DE GORUNUYOR (BUG!) [%d]: %s\n", i, str);
            unhook(env->functions->ReleaseStringUTFChars)(env, jname, str);
        }
        unhook(env->functions->DeleteLocalRef)(env, jname);
    }

    fclose(f);
}

static void DumpAllLoadedClasses() {

    jvmtiEnv* jvmti = JNIHelper::g_jvmti;
    if (!jvmti) {
        printf("[DUMP] JNIHelper::g_jvmti null, skipping\n");
        return;
    }

    printf("[DUMP] Using JVMTI: %p\n", jvmti);

    auto GetLoadedClasses_ = unhook((jvmtiError(JNICALL*)(jvmtiEnv*, jint*, jclass**))
        jvmti->functions->GetLoadedClasses);
    auto GetClassSignature_ = unhook((jvmtiError(JNICALL*)(jvmtiEnv*, jclass, char**, char**))
        jvmti->functions->GetClassSignature);
    auto Deallocate_ = unhook((jvmtiError(JNICALL*)(jvmtiEnv*, unsigned char*))
        jvmti->functions->Deallocate);

    jint classCount = 0;
    jclass* classes = nullptr;

    jvmtiError err = JVMTI_ERROR_NONE;
    err = GetLoadedClasses_(jvmti, &classCount, &classes);
    if (err != JVMTI_ERROR_NONE) {
        printf("[DUMP] GetLoadedClasses hata: %d\n", (int)err);
        return;
    }

    FILE* f = nullptr;
    fopen_s(&f, "C:\\kiwii\\logs\\classes_dump.txt", "a");
    if (f) {
        fprintf(f, "GetLoadedClasses count: %d\n\n", (int)classCount);
        for (jint i = 0; i < classCount; i++) {
            char* sig = nullptr;
            if (GetClassSignature_(jvmti, classes[i], &sig, nullptr) == JVMTI_ERROR_NONE && sig) {
                fprintf(f, "%s\n", sig);
                if (strstr(sig, "kiwii") || strstr(sig, "Kiwii"))
                    printf("[DUMP] KIWII GORUNUYOR: %s\n", sig);
                Deallocate_(jvmti, (unsigned char*)sig);
            }
        }
        fclose(f);
    }

    Deallocate_(jvmti, (unsigned char*)classes);
}

static bool AcquireJNIEnv() {
    if (g_env && g_jvm) return true;

    HMODULE hJvm = GetModuleHandleA("jvm.dll");
    if (!hJvm) {
        printf("[-] jvm.dll not found in AcquireJNIEnv\n");
        return false;
    }

    typedef jint(JNICALL* p_GetEnv)(JavaVM*, JNIEnv**, jint);
    p_GetEnv fnGetEnv = (p_GetEnv)((uintptr_t)hJvm + 0x144080);
    fnGetEnv(nullptr, &g_env, JNI_VERSION_1_8);

    if (!g_env) {
        printf("[-] Failed to get JNIEnv via offset\n");
        return false;
    }
    printf("JNIEnv: %p\n", g_env);

    g_env->GetJavaVM(&g_jvm);
    if (!g_jvm) {
        printf("[-] Failed to get JavaVM from JNIEnv\n");
        return false;
    }
    printf("JVM: %p\n", g_jvm);

    jint res = g_jvm->GetEnv((void**)&g_jvmti, JVMTI_VERSION_1_2);

    if (res != JNI_OK) {
        printf("[-] Failed to get JVMTI: %d\n", res);
        g_jvmti = nullptr;
    } else {
        printf("JVMTI: %p\n", g_jvmti);
    }

    return true;
}

static void InitializeClient() {
    if (!isClientInitialized) {
        isClientInitialized = true; 
        __try {
            printf("instalize\n");

            if (!AcquireJNIEnv()) {
                printf("[-] Failed to acquire JNIEnv, aborting\n");
                return;
            }

            if (!g_jvmti || !g_jvmti->functions) {
                printf("[-] JVMTI null, cannot hook GetLoadedClasses\n");
                return;
            }

            orig_GetLoadedClasses = g_jvmti->functions->GetLoadedClasses;

            {
                auto GetLoadedClasses_ = (jvmtiError(JNICALL*)(jvmtiEnv*, jint*, jclass**))
                    unhook(orig_GetLoadedClasses); 

                jint    snapCount   = 0;
                jclass* snapClasses = nullptr;

                jvmtiError snapErr = GetLoadedClasses_(g_jvmti, &snapCount, &snapClasses);

                if (snapErr == JVMTI_ERROR_NONE && snapClasses && snapCount > 0) {
                    auto NewGlobalRef_ = (jobject(JNICALL*)(JNIEnv*, jobject))unhook(g_env->functions->NewGlobalRef);
                    auto Deallocate_ = (jvmtiError(JNICALL*)(jvmtiEnv*, unsigned char*))unhook(g_jvmti->functions->Deallocate);

                    jclass* globalSnapClasses = new jclass[snapCount];
                    for(int i = 0; i < snapCount; i++) {
                        globalSnapClasses[i] = (jclass)NewGlobalRef_(g_env, snapClasses[i]);
                    }

                    g_preJarClasses = globalSnapClasses;
                    g_preJarCount   = snapCount;
                    g_preJarReady.store(true);
                    printf("ilk snapshot: %d class\n", (int)snapCount);
                    g_hwbp130_pending.store(true);

                    Deallocate_(g_jvmti, (unsigned char*)snapClasses);
                } else {
                    printf("[-] Pre-JAR snapshot alinamadi: %d\n", (int)snapErr);
                }
            }

            bool hookSuccess = AvamHook::Hook((void*)orig_GetLoadedClasses, Hook_GetLoadedClasses, nullptr);
            if (hookSuccess) {
                printf("[+] getloadedclasses\n");
            } else {
                printf("[-] getloadedclasses hook atilamadi\n");
            }
            printf("[+] bypass basarili\n");

            JNIHelper::g_loadedClasses.clear();
            printf("jnihelper\n");
            if (!JNIHelper::Initialize(g_env)) {
                printf("[-] JNIHelper::Initialize failed\n");
                return;
            }

            const char* jarPath = "C:\\kiwii\\client.jar";
            printf("jarload startup: %s\n", jarPath);
            if (JarLoader::LoadJar(g_env, g_jvmti, jarPath)) {
                printf("jar basarili");
            } else {
                printf("jar basarisiz");
            }

            DumpAllLoadedClasses();

        }
        __except (WriteCrashLog(GetExceptionInformation()), EXCEPTION_EXECUTE_HANDLER) {
            printf("[-] Exception in InitializeClient\n");
        }
    }
}

void __stdcall Hooked_wglSwapBuffers(HDC hdc) {
    InitializeClient();
    if (g_hwbp130_pending.load() && g_env)
        WriteHwbp130Log(g_env);
    if (g_env && JarLoader::IsHudReady())
        JarLoader::RenderHud(g_env);
    GLHooks::original_wglSwapBuffers(hdc);
}

static void SafeInitAvamHook() noexcept {
    __try {
        if (!AvamHook::Init()) {
            printf("[-] AvamHook init failed (continuing)\n");
        } else {
            printf("[+] AvamHook init ok\n");
        }
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        printf("[-] AvamHook::Init access violation (continuing without it)\n");
    }
}

static void SafeRefreshHooks() noexcept {
    __try {
        AvamHook::RefreshHooks();
    } __except (EXCEPTION_EXECUTE_HANDLER) {
    }
}

DWORD WINAPI MainThread(LPVOID) {

    Sleep(200);
    SafeInitAvamHook();

    printf("jvm bekliyor...\n");
    HMODULE hJvm = nullptr;
    while (!hJvm) {
        hJvm = GetModuleHandleA("jvm.dll");
        Sleep(1);
    }
    printf("[+] jvm.dll\n");

    std::thread([&]() {
        while (true) {
            Sleep(1);
            SafeRefreshHooks();
        }
    }).detach();

    printf("opengl beklenior...\n");
    HMODULE openglModule = nullptr;
    while (!openglModule) {
        openglModule = GetModuleHandleA("opengl32.dll");
        Sleep(100);
    }
    printf("[+] opengl32.dll\n");

    MH_STATUS mhStatus = MH_Initialize();
    if (mhStatus != MH_OK && mhStatus != MH_ERROR_ALREADY_INITIALIZED) {
        printf("[-] MinHook initialization failed\n");
        return 0;
    }
    printf("[+] MinHook initialized\n");

    printf("wglswapbuffer hook...\n");
    GLHooks::original_wglSwapBuffers = (GLHooks::wglSwapBuffers_t)GetProcAddress(openglModule, "wglSwapBuffers");

    if (!GLHooks::original_wglSwapBuffers) {
        printf("[-] Failed to get wglSwapBuffers\n");
        return 0;
    }

    printf("[+] found: %p\n", GLHooks::original_wglSwapBuffers);

    if (MH_CreateHook((LPVOID)GLHooks::original_wglSwapBuffers, (LPVOID)Hooked_wglSwapBuffers, (LPVOID*)&GLHooks::original_wglSwapBuffers) != MH_OK) {
        printf("[-] Failed to create wglSwapBuffers hook\n");
        return 0;
    }

    if (MH_EnableHook(MH_ALL_HOOKS) != MH_OK) {
        printf("[-] Failed to enable hooks\n");
        return 0;
    }

    return 0;
}

BOOL APIENTRY DllMain(HMODULE hModule, DWORD ul_reason_for_call, LPVOID lpReserved) {
    if (ul_reason_for_call == DLL_PROCESS_ATTACH) {
        SetUnhandledExceptionFilter(ExceptionHandler);
        InitializeLogging();
        printf("dll started\n");
        printf(" %p\n", hModule);
        DisableThreadLibraryCalls(hModule);
        CreateThread(nullptr, 0, MainThread, nullptr, 0, nullptr);
    }
    else if (ul_reason_for_call == DLL_PROCESS_DETACH) {
        AvamHook::Shutdown();
    }
    return TRUE;
}
