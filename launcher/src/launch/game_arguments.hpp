#pragma once
#include <string>
#include <vector>
#include <windows.h>
#include <bcrypt.h>
#include "../crypto/aes.hpp"
#include "../crypto/base64.hpp"
#include "../crypto/md5.hpp"
#include "../auth/launcher_api.hpp"
#include "../util/logger.hpp"

namespace kiwii::launch {

    inline std::string offlineUuid(const std::string& username) {
        std::string basis = "OfflinePlayer:" + username;
        BCRYPT_ALG_HANDLE alg = nullptr;
        BCRYPT_HASH_HANDLE h = nullptr;
        BCryptOpenAlgorithmProvider(&alg, BCRYPT_MD5_ALGORITHM, nullptr, 0);
        BCryptCreateHash(alg, &h, nullptr, 0, nullptr, 0, 0);
        BCryptHashData(h, (PUCHAR) basis.data(), (ULONG) basis.size(), 0);
        BYTE digest[16];
        BCryptFinishHash(h, digest, 16, 0);
        BCryptDestroyHash(h);
        BCryptCloseAlgorithmProvider(alg, 0);

        digest[6] = (digest[6] & 0x0F) | 0x30;
        digest[8] = (digest[8] & 0x3F) | 0x80;

        char buf[33];
        for (int i = 0; i < 16; i++) std::snprintf(buf + i * 2, 3, "%02x", digest[i]);
        buf[32] = 0;
        return std::string(buf, 32);
    }

    inline std::string buildMAdFromKeyValidator(const std::string& username, const std::string& keyValidatorDecoded) {
        if (keyValidatorDecoded.empty()) { logger::warn("keyValidator empty"); return {}; }

        std::string uuidformad;
        size_t p1 = keyValidatorDecoded.find("###");
        if (p1 != std::string::npos) {
            size_t p2 = keyValidatorDecoded.find("###", p1 + 3);
            if (p2 != std::string::npos) uuidformad = keyValidatorDecoded.substr(p1 + 3, p2 - (p1 + 3));
        }

        std::string raw = username + "###" + uuidformad + "###" + auth::nowMillisStr();
        std::string s2 = crypto::base64Encode(raw);
        std::string s3 = crypto::aesEncryptBase64(s2, auth::DEFAULT_AES_KEY);
        std::string s4 = crypto::aesEncryptBase64(s3, auth::DEFAULT_AES_KEY);
        return crypto::base64Encode(s4);
    }

    inline std::string buildGameArgs(const std::string& username, const std::string& password,
                                     const std::string& baseDir, const std::string& globalSessionHash,
                                     const std::string& keyValidatorDecoded) {
        std::string uuid = offlineUuid(username);
        std::string mAd  = buildMAdFromKeyValidator(username, keyValidatorDecoded);

        std::string encUsername = crypto::aesEncryptBase64(
                username + "###" + auth::nowMillisStr() + "###minecraft.jar", auth::DEFAULT_AES_KEY);
        std::string encPassword = crypto::base64Encode(password);

        std::vector<std::pair<std::string, std::string>> args = {
            { "--username",         encUsername },
            { "--version",          "RiseClient_1.8.9" },
            { "--gameDir",          baseDir },
            { "--assetsDir",        baseDir + "\\assets" },
            { "--assetIndex",       "1.8" },
            { "--uuid",             uuid },
            { "--accessToken",      "0000000000000" },
            { "--launcherKeys",     "kffvBRbsgZKd8KFvqnqOGKpDppOxko96OEG8lhXCnINYNqR6OaA9Rok0XqF9a4nicG7KXD+WatD4Tedk4uFfWlIqyMfjXGa7ow49nyTNcuo=" },
            { "--userType",         "legacy" },
            { "--width",            "854" },
            { "--height",           "480" },
            { "--server",           "play.craftrise.tc" },
            { "--port",             "25565" },
            { "--password",         encPassword },
            { "--launcherKey",      "I_AM_RISELAUNCHER_NEW" },
            { "--launcherLang",     "0" },
            { "--sessionHash",      globalSessionHash },
            { "--LJavaVersion",     "1.8.0_51" },
            { "--Lpid",             "00000" },
            { "--launcherSecHash",  "26Ffcm3VhyUNP8QrI03Jp3jyrRN3lAZpyZXBRqFo6VRJSQmjXU6qaDlFYZRVVhT0" },
            { "--mAd",              mAd },
        };

        std::string sb;
        bool first = true;
        for (auto& kv : args) {
            if (!first) sb += "###";
            sb += kv.first + "###" + kv.second;
            first = false;
        }
        return sb;
    }
}
