#pragma once
#include <string>
#include "payload_builder.hpp"
#include "../net/tcp_client.hpp"
#include "../util/logger.hpp"
#include "../crypto/base64.hpp"
#include "../crypto/aes.hpp"

namespace kiwii::auth {

    inline const std::string AUTH_HOST = "185.255.92.10";
    inline const int         AUTH_PORT = 4754;

    inline std::string buildAuthJson(const std::string& username, const std::string& password) {
        Payload p = buildPayload(username, password);
        std::string json;
        json += "{\n";
        json += "  \"messageType\": \"trySplashLogin\",\n";
        json += "  \"datas\": {\n";
        json += "    \"sumBigX\": \"" + p.sumBigX + "\",\n";
        json += "    \"password\": \"" + password + "\",\n";
        json += "    \"sumBig\": \""  + p.sumBig  + "\",\n";
        json += "    \"sumBigY\": \"" + p.sumBigY + "\",\n";
        json += "    \"sum\": \""     + p.sum     + "\",\n";
        json += "    \"key\": \""     + p.key     + "\",\n";
        json += "    \"username\": \""+ username  + "\",\n";
        json += "    \"staticSessionKey\": \"" + p.staticSessionKey + "\"\n";
        json += "  }\n";
        json += "}\n";
        return json;
    }

    inline std::string jsonUnescape(const std::string& s) {
        std::string out; out.reserve(s.size());
        for (size_t i = 0; i < s.size(); i++) {
            if (s[i] == '\\' && i + 1 < s.size()) {
                char c = s[i + 1];
                switch (c) {
                    case '/':  out += '/';  i++; break;
                    case '"':  out += '"';  i++; break;
                    case '\\': out += '\\'; i++; break;
                    case 'n':  out += '\n'; i++; break;
                    case 'r':  out += '\r'; i++; break;
                    case 't':  out += '\t'; i++; break;
                    case 'b':  out += '\b'; i++; break;
                    case 'f':  out += '\f'; i++; break;
                    default:   out += s[i]; break;
                }
            } else out += s[i];
        }
        return out;
    }

    inline std::string extractField(const std::string& json, const std::string& field) {
        std::string needle = "\"" + field + "\"";
        size_t idx = json.find(needle);
        if (idx == std::string::npos) return {};
        idx = json.find(':', idx);
        if (idx == std::string::npos) return {};
        idx++;
        while (idx < json.size() && (json[idx] == ' ' || json[idx] == '\t')) idx++;
        if (idx >= json.size()) return {};
        if (json[idx] == '"') {
            size_t start = idx + 1;
            size_t p = start;
            while (p < json.size()) {
                if (json[p] == '\\' && p + 1 < json.size()) { p += 2; continue; }
                if (json[p] == '"') break;
                p++;
            }
            if (p >= json.size()) return {};
            return jsonUnescape(json.substr(start, p - start));
        }
        size_t end = idx;
        while (end < json.size() && json[end] != ',' && json[end] != '}' && json[end] != '\n') end++;
        std::string v = json.substr(idx, end - idx);
        while (!v.empty() && (v.back() == ' ' || v.back() == '\r' || v.back() == '\t')) v.pop_back();
        return v;
    }

    struct LoginResult {
        std::string globalSessionHash;
        std::string keyValidatorDecoded;
        std::string status;
        std::string message;
    };

    inline std::string errorMessageForCode(const std::string& code) {
        if (code == "4")  return "Yanlış şifre";
        if (code == "11") return "Hesap aktivasyonu gerekli";
        if (code == "12") return "Hesap zaten oyunda";
        if (code == "14") return "E-posta doğrulaması gerekli";
        if (code.empty()) return "Sunucudan cevap alınamadı";
        return "Bilinmeyen hata (kod: " + code + ")";
    }

    inline std::string decodeKeyValidator(const std::string& kv) {
        if (kv.empty()) return {};
        std::string s1 = crypto::aesDecryptBase64(kv, DEFAULT_AES_KEY);
        std::string s2 = crypto::base64Decode(s1);
        std::string s3 = crypto::aesDecryptBase64(s2, DEFAULT_AES_KEY);
        std::string s4 = crypto::aesDecryptBase64(s3, DEFAULT_AES_KEY);
        return crypto::base64Decode(s4);
    }

    inline void sendGetHashsPrelude() {
        std::string reply = net::sendAndReceive(AUTH_HOST, AUTH_PORT, "{\"messageType\":\"getHashs\"}");
        logger::info("getHashs prelude reply=" + std::to_string(reply.size()) + " chars");
    }

    inline LoginResult doLogin(const std::string& username, const std::string& password) {
        LoginResult r;
        sendGetHashsPrelude();

        std::string json = buildAuthJson(username, password);
        std::string reply = net::sendAndReceive(AUTH_HOST, AUTH_PORT, json);
        if (reply.empty()) return r;
        logger::info("login reply[0..400]=" + reply.substr(0, 400));

        r.globalSessionHash   = extractField(reply, "globalSessionHash");
        r.status              = extractField(reply, "status");
        r.message             = extractField(reply, "message");
        std::string kvRaw     = extractField(reply, "keyValidator");
        logger::info("parsed globalSessionHash=" + std::to_string(r.globalSessionHash.size())
                     + " status=" + r.status + " message=" + r.message
                     + " keyValidatorRaw=" + std::to_string(kvRaw.size()));
        r.keyValidatorDecoded = decodeKeyValidator(kvRaw);
        logger::info("keyValidator decoded=" + std::to_string(r.keyValidatorDecoded.size()));
        return r;
    }

    inline std::string getGlobalSessionHash(const std::string& username, const std::string& password) {
        return doLogin(username, password).globalSessionHash;
    }
}
