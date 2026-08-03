#pragma once
#include <string>
#include <vector>
#include <windows.h>
#include <wincrypt.h>

namespace kiwii::crypto {

    inline std::string base64Encode(const void* data, size_t len) {
        DWORD outLen = 0;
        if (!CryptBinaryToStringA((const BYTE*) data, (DWORD) len,
                                  CRYPT_STRING_BASE64 | CRYPT_STRING_NOCRLF, nullptr, &outLen)) return {};
        std::string out(outLen, '\0');
        if (!CryptBinaryToStringA((const BYTE*) data, (DWORD) len,
                                  CRYPT_STRING_BASE64 | CRYPT_STRING_NOCRLF, out.data(), &outLen)) return {};
        out.resize(outLen);
        return out;
    }

    inline std::string base64Encode(const std::string& s) {
        return base64Encode(s.data(), s.size());
    }

    inline std::vector<uint8_t> base64DecodeBytes(const std::string& s) {
        DWORD outLen = 0;
        if (!CryptStringToBinaryA(s.c_str(), (DWORD) s.size(), CRYPT_STRING_BASE64,
                                  nullptr, &outLen, nullptr, nullptr)) return {};
        std::vector<uint8_t> out(outLen);
        if (!CryptStringToBinaryA(s.c_str(), (DWORD) s.size(), CRYPT_STRING_BASE64,
                                  out.data(), &outLen, nullptr, nullptr)) return {};
        out.resize(outLen);
        return out;
    }

    inline std::string base64Decode(const std::string& s) {
        auto bytes = base64DecodeBytes(s);
        return std::string((const char*) bytes.data(), bytes.size());
    }

    inline const std::string OBFUSCATE_PREFIX = "3ebi2mclmAM7Ao2";
    inline const std::string OBFUSCATE_SUFFIX = "KweGTngiZOOj9d6";

    inline std::string decodeObfuscatedString(const std::string& in) {
        if (in.empty()) return {};
        std::string s = base64Decode(in);
        if (s.empty()) return {};
        s = base64Decode(s);
        if (s.empty()) return {};
        if (s.size() > OBFUSCATE_PREFIX.size() + OBFUSCATE_SUFFIX.size()
            && s.compare(0, OBFUSCATE_PREFIX.size(), OBFUSCATE_PREFIX) == 0
            && s.compare(s.size() - OBFUSCATE_SUFFIX.size(), OBFUSCATE_SUFFIX.size(), OBFUSCATE_SUFFIX) == 0) {
            std::string middle = s.substr(OBFUSCATE_PREFIX.size(),
                                          s.size() - OBFUSCATE_PREFIX.size() - OBFUSCATE_SUFFIX.size());
            return base64Decode(middle);
        }
        return s;
    }
}
