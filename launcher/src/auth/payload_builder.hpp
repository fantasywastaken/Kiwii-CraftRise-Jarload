#pragma once
#include <string>
#include <unordered_map>
#include <chrono>
#include <cstdio>
#include <windows.h>
#include <objbase.h>
#include "../crypto/md5.hpp"
#include "../crypto/aes.hpp"
#include "../crypto/base64.hpp"

#pragma comment(lib, "ole32.lib")

namespace kiwii::auth {

    inline const std::string DEFAULT_AES_KEY = "2650053489059452";

    inline std::string nowMillisStr() {
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::system_clock::now().time_since_epoch()).count();
        return std::to_string(ms);
    }

    inline std::string generateKey(const std::string& username, const std::string& password) {
        std::string passHash = crypto::md5Hex(password);
        std::string original = username + "###" + passHash + "###" + nowMillisStr();
        std::string b64 = crypto::base64Encode(original);
        std::string aes1 = crypto::aesEncryptBase64(b64, DEFAULT_AES_KEY);
        std::string aes2 = crypto::aesEncryptBase64(aes1, DEFAULT_AES_KEY);
        return crypto::base64Encode(aes2);
    }

    struct Payload {
        std::string key;
        std::string sum;
        std::string sumBig;
        std::string sumBigX;
        std::string sumBigY;
        std::string staticSessionKey;
    };

    inline std::string generateUuid() {
        GUID g; CoCreateGuid(&g);
        char buf[40];
        std::snprintf(buf, sizeof(buf),
            "%08lx-%04x-%04x-%02x%02x-%02x%02x%02x%02x%02x%02x",
            g.Data1, g.Data2, g.Data3,
            g.Data4[0], g.Data4[1], g.Data4[2], g.Data4[3],
            g.Data4[4], g.Data4[5], g.Data4[6], g.Data4[7]);
        return std::string(buf);
    }

    inline Payload buildPayload(const std::string& username, const std::string& password) {
        Payload p;
        p.key    = generateKey(username, password);
        p.sum    = crypto::md5Hex(p.key);
        p.sumBig = crypto::md5Hex(p.sum + username + ".....");
        p.sumBigX= crypto::md5Hex("......" + p.sumBig + "......");
        p.sumBigY= crypto::md5Hex("craftrise#" + username);
        p.staticSessionKey = generateUuid();
        return p;
    }
}
