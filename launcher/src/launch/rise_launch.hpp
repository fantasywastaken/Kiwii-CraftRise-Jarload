#pragma once
#include <string>
#include <thread>
#include <vector>
#include <windows.h>
#include "craftrise_paths.hpp"
#include "java_libraries.hpp"
#include "game_arguments.hpp"
#include "../crypto/base64.hpp"
#include "../crypto/aes.hpp"
#include "../auth/launcher_api.hpp"
#include "../inject/dll_inject.hpp"
#include "../util/logger.hpp"

namespace kiwii::launch {

    inline std::atomic<bool> g_handshakeDone{false};
    inline std::atomic<int>  g_stdoutLineCount{0};
    inline std::atomic<int>  g_injectStatus{0};

    inline const std::string CLIENT_MESSAGE_KEY = "1234758145215632";
    inline const std::string HEAP_DUMP_FLAG =
            "-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump";

    inline std::string b64s(const std::string& s) { return crypto::base64Encode(s); }

    inline std::string buildArgsJson(const Paths& p, const std::string& ramMB,
                                     const std::string& gameArgs) {
        std::string json = "{";
        json += "\"clientFolderPath\":\""  + b64s(p.clientFolder) + "\",";
        json += "\"javaNativesPath\":\""   + b64s(p.javaNatives) + "\",";
        json += "\"javaPath\":\""          + b64s(p.java) + "\",";
        json += "\"jvmDllPath\":\""        + b64s(p.jvmDll) + "\",";
        json += "\"javaRam\":\""           + b64s("-Xmx" + ramMB + "M") + "\",";
        json += "\"gameArguments\":\""     + b64s(gameArgs) + "\",";
        json += "\"javaLibrariesPath\":\"" + b64s(getJavaLibrariesClasspath(p)) + "\",";
        json += "\"directory\":\""         + b64s(p.clientFolder) + "\",";
        json += "\"javaType\":\""          + b64s(p.javaw) + "\"";
        json += "}";
        return json;
    }

    struct LaunchResult {
        bool started = false;
        HANDLE processHandle = nullptr;
        DWORD  processId = 0;
    };

    inline LaunchResult launchGame(const std::string& username, const std::string& password,
                                   const std::string& ramMB, const std::string& sessionHash,
                                   const std::string& keyValidatorDecoded) {
        LaunchResult result;
        Paths p = resolvePaths();
        logger::info("Base path: " + p.base);
        logger::info("craftrise-x64: " + p.craftriseExe);

        std::string gameArgs = buildGameArgs(username, password, p.base, sessionHash, keyValidatorDecoded);
        std::string argsJson = buildArgsJson(p, ramMB, gameArgs);
        std::string encodedJson = crypto::base64Encode(argsJson);

        SECURITY_ATTRIBUTES sa{}; sa.nLength = sizeof(sa); sa.bInheritHandle = TRUE;
        HANDLE hStdOutRd = nullptr, hStdOutWr = nullptr;
        HANDLE hStdErrRd = nullptr, hStdErrWr = nullptr;
        HANDLE hStdInRd  = nullptr, hStdInWr  = nullptr;
        if (!CreatePipe(&hStdOutRd, &hStdOutWr, &sa, 65536)) { logger::warn("CreatePipe stdout failed"); return result; }
        if (!CreatePipe(&hStdErrRd, &hStdErrWr, &sa, 65536)) { logger::warn("CreatePipe stderr failed"); return result; }
        if (!CreatePipe(&hStdInRd,  &hStdInWr,  &sa, 65536)) { logger::warn("CreatePipe stdin failed"); return result; }
        SetHandleInformation(hStdOutRd, HANDLE_FLAG_INHERIT, 0);
        SetHandleInformation(hStdErrRd, HANDLE_FLAG_INHERIT, 0);
        SetHandleInformation(hStdInWr,  HANDLE_FLAG_INHERIT, 0);

        std::string cmdLine;
        cmdLine += "\"" + p.craftriseExe + "\" ";
        cmdLine += "\"" + p.javaExe      + "\" ";
        cmdLine += "\"" + HEAP_DUMP_FLAG + "\" ";
        cmdLine += encodedJson;

        STARTUPINFOA si{}; si.cb = sizeof(si);
        si.dwFlags    = STARTF_USESTDHANDLES;
        si.hStdOutput = hStdOutWr;
        si.hStdError  = hStdErrWr;
        si.hStdInput  = hStdInRd;

        PROCESS_INFORMATION pi{};
        std::vector<char> cmdBuf(cmdLine.begin(), cmdLine.end());
        cmdBuf.push_back(0);

        DWORD flags = 0;

        SetEnvironmentVariableA("_JAVA_OPTIONS", nullptr);
        SetEnvironmentVariableA("JAVA_TOOL_OPTIONS", nullptr);
        logger::info("stripped _JAVA_OPTIONS + JAVA_TOOL_OPTIONS from env");

        if (!CreateProcessA(nullptr, cmdBuf.data(), nullptr, nullptr, TRUE,
                            flags, nullptr, p.base.c_str(), &si, &pi)) {
            logger::warn("CreateProcess failed: " + std::to_string(GetLastError()));
            CloseHandle(hStdOutRd); CloseHandle(hStdOutWr);
            CloseHandle(hStdErrRd); CloseHandle(hStdErrWr);
            CloseHandle(hStdInRd);  CloseHandle(hStdInWr);
            return result;
        }
        CloseHandle(hStdOutWr);
        CloseHandle(hStdErrWr);
        CloseHandle(hStdInRd);
        CloseHandle(pi.hThread);

        result.started = true;
        result.processHandle = pi.hProcess;
        result.processId = pi.dwProcessId;
        logger::info("craftrise-x64.exe started, PID=" + std::to_string(pi.dwProcessId));
        logger::info("cmdLine.size=" + std::to_string(cmdLine.size()));
        for (size_t off = 0; off < cmdLine.size(); off += 500) {
            logger::info("cmdLine[" + std::to_string(off) + ".." + std::to_string(off + 500) + "]=" + cmdLine.substr(off, 500));
        }
        logger::info("workingDir=" + p.base);

        std::thread([hStdErrRd, pid = pi.dwProcessId]() {
            char chunk[4096]; DWORD br = 0;
            std::string buf;
            while (ReadFile(hStdErrRd, chunk, sizeof(chunk), &br, nullptr) && br > 0) {
                buf.append(chunk, br);
                size_t p;
                while ((p = buf.find('\n')) != std::string::npos) {
                    std::string ln = buf.substr(0, p); buf.erase(0, p + 1);
                    if (!ln.empty() && ln.back() == '\r') ln.pop_back();
                    if (ln.empty()) continue;
                    logger::info("stderr: " + ln.substr(0, 300));
                }
            }
            CloseHandle(hStdErrRd);
        }).detach();

        std::thread([hStdOutRd, hStdInWr, procHandle = pi.hProcess, pid = pi.dwProcessId]() {
            std::string buffer;
            char chunk[4096];
            DWORD bytesRead = 0;
            int  lineNum = 0;
            int  riseCount = 0;

            while (true) {
                if (!ReadFile(hStdOutRd, chunk, sizeof(chunk), &bytesRead, nullptr) || bytesRead == 0) {
                    DWORD lastErr = GetLastError();
                    logger::warn("reader: ReadFile ended (err=" + std::to_string(lastErr) + ") lines=" + std::to_string(lineNum));
                    break;
                }
                buffer.append(chunk, bytesRead);

                size_t pos;
                while ((pos = buffer.find('\n')) != std::string::npos) {
                    std::string line = buffer.substr(0, pos);
                    buffer.erase(0, pos + 1);
                    if (!line.empty() && line.back() == '\r') line.pop_back();
                    if (line.empty()) continue;
                    lineNum++;

                    if (line.rfind("AL", 0) == 0) {
                        logger::info("game[" + std::to_string(lineNum) + "] AL marker: " + line.substr(0, 200));
                        CloseHandle(hStdInWr);
                        return;
                    }

                    if (line.rfind("rise:client:", 0) == 0) {
                        riseCount++;
                        size_t p1 = line.find(':');
                        size_t p2 = line.find(':', p1 + 1);
                        if (p2 == std::string::npos) {
                            logger::warn("rise:client: bad format: " + line.substr(0, 100));
                            continue;
                        }
                        std::string encoded = line.substr(p2 + 1);

                        std::string aesDec  = crypto::aesDecryptBase64(encoded, CLIENT_MESSAGE_KEY);
                        std::string deobf   = crypto::decodeObfuscatedString(aesDec);
                        std::string decoded = crypto::base64Decode(deobf);

                        std::string reply = "rise:launcher:" + decoded;
                        DWORD written = 0;
                        BOOL wr = WriteFile(hStdInWr, reply.data(), (DWORD) reply.size(), &written, nullptr);
                        FlushFileBuffers(hStdInWr);
                        CloseHandle(hStdInWr);
                        g_handshakeDone.store(true);
                        logger::info("rise[" + std::to_string(riseCount) + "] enc=" + std::to_string(encoded.size())
                                     + "b dec=" + std::to_string(decoded.size())
                                     + "b reply_wr=" + std::to_string(written)
                                     + " ok=" + (wr ? "1" : "0") + " (pipe closed, handshake signal set)");
                        logger::info("  rise[" + std::to_string(riseCount) + "] decoded[0..120]=" + decoded.substr(0, 120));
                    } else {
                        g_stdoutLineCount.fetch_add(1);
                        logger::info("game[" + std::to_string(lineNum) + "]: " + line.substr(0, 300));
                    }
                }
            }
            CloseHandle(hStdInWr);

            DWORD wait = WaitForSingleObject(procHandle, 5000);
            DWORD exit_code = 0;
            GetExitCodeThread(procHandle, &exit_code);
            GetExitCodeProcess(procHandle, &exit_code);
            logger::info("proc PID=" + std::to_string(pid) + " wait=" + std::to_string(wait) + " exit_code=0x" + [&]{
                char b[16]; std::snprintf(b, 16, "%08X", exit_code); return std::string(b);
            }() + " riseMsgs=" + std::to_string(riseCount) + " totalLines=" + std::to_string(lineNum));
        }).detach();

        return result;
    }

    inline void launchAndInject(const std::string& username, const std::string& password,
                                const std::string& ramMB, const std::string& sessionHash,
                                const std::string& keyValidatorDecoded,
                                const std::string& kiwiiDllPath) {
        g_handshakeDone.store(false);
        g_stdoutLineCount.store(0);
        g_injectStatus.store(1);

        LaunchResult r = launchGame(username, password, ramMB, sessionHash, keyValidatorDecoded);
        if (!r.started) { g_injectStatus.store(4); return; }

        DWORD pid = r.processId;
        std::thread([pid, kiwiiDllPath]() {
            auto start = std::chrono::steady_clock::now();
            while (!g_handshakeDone.load()) {
                auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(
                        std::chrono::steady_clock::now() - start).count();
                if (elapsed > 120) { logger::warn("handshake wait timed out (2 min), inject anyway"); break; }
                std::this_thread::sleep_for(std::chrono::milliseconds(250));
            }
            g_injectStatus.store(2);
            logger::info("handshake done, waiting 25s for JVM to fully boot before inject");
            std::this_thread::sleep_for(std::chrono::seconds(25));
            g_injectStatus.store(3);
            logger::info("injecting kiwii.dll into PID=" + std::to_string(pid) + " (stdout lines seen: " + std::to_string(g_stdoutLineCount.load()) + ")");
            bool ok = inject::injectDll(pid, kiwiiDllPath);
            g_injectStatus.store(ok ? 5 : 4);
        }).detach();
    }
}
