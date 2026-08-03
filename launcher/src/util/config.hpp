#pragma once
#include <string>
#include <unordered_map>
#include <fstream>
#include <sstream>
#include <windows.h>

namespace kiwii::config {

    inline std::string getConfigPath() {
        CreateDirectoryA("C:\\kiwii", nullptr);
        CreateDirectoryA("C:\\kiwii\\configs", nullptr);
        return "C:\\kiwii\\configs\\config.properties";
    }

    inline std::unordered_map<std::string, std::string> load() {
        std::unordered_map<std::string, std::string> map;
        std::ifstream in(getConfigPath());
        if (!in.is_open()) return map;
        std::string line;
        while (std::getline(in, line)) {
            if (line.empty() || line[0] == '#') continue;
            auto eq = line.find('=');
            if (eq == std::string::npos) continue;
            std::string key = line.substr(0, eq);
            std::string val = line.substr(eq + 1);
            while (!val.empty() && (val.back() == '\r' || val.back() == '\n')) val.pop_back();
            map[key] = val;
        }
        return map;
    }

    inline void save(const std::unordered_map<std::string, std::string>& map) {
        std::ofstream out(getConfigPath(), std::ios::trunc);
        if (!out.is_open()) return;
        out << "# Kiwii config\n";
        for (auto& kv : map) out << kv.first << "=" << kv.second << "\n";
    }

    inline void setValue(const std::string& key, const std::string& val) {
        auto m = load();
        m[key] = val;
        save(m);
    }

    inline std::string getValue(const std::string& key, const std::string& def = "") {
        auto m = load();
        auto it = m.find(key);
        return it == m.end() ? def : it->second;
    }
}
