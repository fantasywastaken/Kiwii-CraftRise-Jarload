#pragma once
#include <string>
#include <cstdio>
#include <windows.h>
#include <wincrypt.h>

#pragma comment(lib, "advapi32.lib")

namespace kiwii::crypto {

    inline std::string md5Hex(const std::string& input) {
        HCRYPTPROV prov = 0;
        HCRYPTHASH hash = 0;
        std::string out;

        if (!CryptAcquireContextW(&prov, nullptr, nullptr, PROV_RSA_FULL, CRYPT_VERIFYCONTEXT)) return {};
        if (!CryptCreateHash(prov, CALG_MD5, 0, 0, &hash)) { CryptReleaseContext(prov, 0); return {}; }
        if (!CryptHashData(hash, (const BYTE*) input.data(), (DWORD) input.size(), 0)) {
            CryptDestroyHash(hash); CryptReleaseContext(prov, 0); return {};
        }
        BYTE digest[16];
        DWORD digestLen = 16;
        if (!CryptGetHashParam(hash, HP_HASHVAL, digest, &digestLen, 0)) {
            CryptDestroyHash(hash); CryptReleaseContext(prov, 0); return {};
        }
        char buf[33];
        for (int i = 0; i < 16; i++) std::snprintf(buf + i * 2, 3, "%02x", digest[i]);
        buf[32] = 0;
        out.assign(buf, 32);

        CryptDestroyHash(hash);
        CryptReleaseContext(prov, 0);
        return out;
    }
}
