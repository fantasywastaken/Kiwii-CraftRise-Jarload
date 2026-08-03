#pragma once
#include <string>
#include <windows.h>

namespace kiwii::launch {

    inline uint64_t folderSize(const std::string& path) {
        uint64_t total = 0;
        WIN32_FIND_DATAA fd;
        HANDLE h = FindFirstFileA((path + "\\*").c_str(), &fd);
        if (h == INVALID_HANDLE_VALUE) return 0;
        do {
            if (fd.cFileName[0] == '.' && (fd.cFileName[1] == 0 ||
                (fd.cFileName[1] == '.' && fd.cFileName[2] == 0))) continue;
            std::string full = path + "\\" + fd.cFileName;
            if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) total += folderSize(full);
            else total += ((uint64_t) fd.nFileSizeHigh << 32) | fd.nFileSizeLow;
        } while (FindNextFileA(h, &fd));
        FindClose(h);
        return total;
    }

    inline std::string findLargestCraftRiseUser() {
        WIN32_FIND_DATAA fd;
        HANDLE h = FindFirstFileA("C:\\Users\\*", &fd);
        if (h == INVALID_HANDLE_VALUE) return "admin";

        std::string best = "admin";
        uint64_t bestSize = 0;
        do {
            if (!(fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) continue;
            if (fd.cFileName[0] == '.') continue;
            std::string user = fd.cFileName;
            std::string cr = "C:\\Users\\" + user + "\\AppData\\Roaming\\.craftrise";
            DWORD attr = GetFileAttributesA(cr.c_str());
            if (attr == INVALID_FILE_ATTRIBUTES || !(attr & FILE_ATTRIBUTE_DIRECTORY)) continue;
            uint64_t sz = folderSize(cr);
            if (sz > bestSize) { bestSize = sz; best = user; }
        } while (FindNextFileA(h, &fd));
        FindClose(h);
        return best;
    }

    struct Paths {
        std::string user;
        std::string base;
        std::string clientFolder;
        std::string javaNatives;
        std::string java;
        std::string jvmDll;
        std::string javaw;
        std::string javaExe;
        std::string craftriseExe;
    };

    inline Paths resolvePaths() {
        Paths p;
        p.user = findLargestCraftRiseUser();
        p.base = "C:\\Users\\" + p.user + "\\AppData\\Roaming\\.craftrise";
        p.clientFolder = p.base + "\\versions\\RiseClient_1.8.9";
        p.javaNatives  = p.base + "\\libraries\\natives";
        p.java         = p.base + "\\java\\jdk-x64";
        p.jvmDll       = p.java + "\\bin\\server\\jvm.dll";
        p.javaw        = p.java + "\\bin\\javaw.exe";
        p.javaExe      = p.java + "\\bin\\java.exe";
        p.craftriseExe = p.base + "\\craftrise-x64.exe";
        return p;
    }
}
