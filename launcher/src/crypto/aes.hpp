#pragma once
#include <string>
#include <vector>
#include <windows.h>
#include <bcrypt.h>
#include "base64.hpp"

#pragma comment(lib, "bcrypt.lib")

namespace kiwii::crypto {

    inline std::vector<uint8_t> aesEcbCrypt(bool encrypt, const std::vector<uint8_t>& input, const std::string& key) {
        std::vector<uint8_t> out;
        BCRYPT_ALG_HANDLE alg = nullptr;
        BCRYPT_KEY_HANDLE keyHandle = nullptr;

        if (BCryptOpenAlgorithmProvider(&alg, BCRYPT_AES_ALGORITHM, nullptr, 0) != 0) return {};
        if (BCryptSetProperty(alg, BCRYPT_CHAINING_MODE, (PUCHAR) BCRYPT_CHAIN_MODE_ECB,
                              sizeof(BCRYPT_CHAIN_MODE_ECB), 0) != 0) { BCryptCloseAlgorithmProvider(alg, 0); return {}; }

        std::vector<uint8_t> keyBytes(key.begin(), key.end());
        if (BCryptGenerateSymmetricKey(alg, &keyHandle, nullptr, 0,
                                       keyBytes.data(), (ULONG) keyBytes.size(), 0) != 0) {
            BCryptCloseAlgorithmProvider(alg, 0); return {};
        }

        ULONG outLen = 0;
        DWORD flags = BCRYPT_BLOCK_PADDING;
        if (encrypt) {
            BCryptEncrypt(keyHandle, (PUCHAR) input.data(), (ULONG) input.size(), nullptr,
                          nullptr, 0, nullptr, 0, &outLen, flags);
            out.resize(outLen);
            BCryptEncrypt(keyHandle, (PUCHAR) input.data(), (ULONG) input.size(), nullptr,
                          nullptr, 0, out.data(), outLen, &outLen, flags);
        } else {
            BCryptDecrypt(keyHandle, (PUCHAR) input.data(), (ULONG) input.size(), nullptr,
                          nullptr, 0, nullptr, 0, &outLen, flags);
            out.resize(outLen);
            BCryptDecrypt(keyHandle, (PUCHAR) input.data(), (ULONG) input.size(), nullptr,
                          nullptr, 0, out.data(), outLen, &outLen, flags);
        }
        out.resize(outLen);

        BCryptDestroyKey(keyHandle);
        BCryptCloseAlgorithmProvider(alg, 0);
        return out;
    }

    inline std::string aesEncryptBase64(const std::string& plainText, const std::string& key) {
        std::vector<uint8_t> input(plainText.begin(), plainText.end());
        auto cipher = aesEcbCrypt(true, input, key);
        return base64Encode(cipher.data(), cipher.size());
    }

    inline std::string aesDecryptBase64(const std::string& cipherB64, const std::string& key) {
        auto cipher = base64DecodeBytes(cipherB64);
        auto plain = aesEcbCrypt(false, cipher, key);
        return std::string((const char*) plain.data(), plain.size());
    }
}
