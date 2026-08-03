#pragma once
#include <string>
#include <mutex>
#include <fstream>
#include <ctime>
#include <cstdio>
#include <windows.h>

namespace kiwii::logger {

    inline std::mutex& mutex() { static std::mutex m; return m; }

    inline std::string logPath() {
        CreateDirectoryA("C:\\kiwii", nullptr);
        CreateDirectoryA("C:\\kiwii\\logs", nullptr);
        return "C:\\kiwii\\logs\\launcher.log";
    }

    inline void write(const char* level, const std::string& msg) {
        std::lock_guard<std::mutex> lock(mutex());
        std::time_t t = std::time(nullptr);
        char ts[32];
        std::strftime(ts, sizeof(ts), "%H:%M:%S", std::localtime(&t));
        std::string line = std::string("[") + ts + "] [" + level + "] " + msg;
        std::printf("%s\n", line.c_str());
        static std::ofstream file(logPath(), std::ios::app);
        if (file.is_open()) { file << line << "\n"; file.flush(); }
    }

    inline void info(const std::string& msg) { write("INFO", msg); }
    inline void warn(const std::string& msg) { write("WARN", msg); }
    inline void err (const std::string& msg) { write("ERROR", msg); }
}
