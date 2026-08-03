#pragma once
#include <string>
#include <vector>
#include <cstdint>
#include <winsock2.h>
#include <ws2tcpip.h>
#include "../util/logger.hpp"

#pragma comment(lib, "ws2_32.lib")

namespace kiwii::net {

    inline bool winsockInit() {
        static bool ready = false;
        if (ready) return true;
        WSADATA wsa;
        if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) return false;
        ready = true;
        return true;
    }

    inline bool recvExact(SOCKET s, char* buf, int n) {
        int got = 0;
        while (got < n) {
            int r = recv(s, buf + got, n - got, 0);
            if (r <= 0) return false;
            got += r;
        }
        return true;
    }

    inline std::string sendAndReceive(const std::string& host, int port,
                                      const std::string& payload, int timeoutMs = 10000) {
        if (!winsockInit()) { logger::warn("winsock init failed"); return {}; }

        addrinfo hints{}; hints.ai_family = AF_INET; hints.ai_socktype = SOCK_STREAM;
        addrinfo* res = nullptr;
        std::string portStr = std::to_string(port);
        if (getaddrinfo(host.c_str(), portStr.c_str(), &hints, &res) != 0) {
            logger::warn("getaddrinfo failed for " + host);
            return {};
        }

        SOCKET s = socket(res->ai_family, res->ai_socktype, res->ai_protocol);
        if (s == INVALID_SOCKET) { freeaddrinfo(res); return {}; }

        DWORD tmo = (DWORD) timeoutMs;
        setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, (const char*) &tmo, sizeof(tmo));
        setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, (const char*) &tmo, sizeof(tmo));

        if (connect(s, res->ai_addr, (int) res->ai_addrlen) == SOCKET_ERROR) {
            logger::warn("tcp connect failed to " + host + ":" + portStr);
            closesocket(s); freeaddrinfo(res); return {};
        }
        freeaddrinfo(res);

        uint32_t strLen = (uint32_t) payload.size();
        uint32_t innerLen = 1 + 1 + 2 + strLen;

        std::vector<char> framed;
        framed.reserve(4 + innerLen);
        framed.push_back((char) ((innerLen >> 24) & 0xFF));
        framed.push_back((char) ((innerLen >> 16) & 0xFF));
        framed.push_back((char) ((innerLen >> 8)  & 0xFF));
        framed.push_back((char) ((innerLen)       & 0xFF));
        framed.push_back((char) 0x05);
        framed.push_back((char) 0x74);
        framed.push_back((char) ((strLen >> 8) & 0xFF));
        framed.push_back((char) (strLen & 0xFF));
        framed.insert(framed.end(), payload.begin(), payload.end());

        logger::info("tcp send framed=" + std::to_string(framed.size()) + " payload=" + std::to_string(strLen));

        if (send(s, framed.data(), (int) framed.size(), 0) == SOCKET_ERROR) {
            logger::warn("tcp send failed");
            closesocket(s); return {};
        }

        char lenBuf[4];
        if (!recvExact(s, lenBuf, 4)) {
            logger::warn("tcp recv length prefix failed");
            closesocket(s); return {};
        }
        uint32_t respLen = ((uint32_t)(uint8_t)lenBuf[0] << 24) |
                           ((uint32_t)(uint8_t)lenBuf[1] << 16) |
                           ((uint32_t)(uint8_t)lenBuf[2] << 8)  |
                            (uint32_t)(uint8_t)lenBuf[3];

        if (respLen < 4 || respLen > 4 * 1024 * 1024) {
            logger::warn("tcp bad response length: " + std::to_string(respLen));
            closesocket(s); return {};
        }

        std::vector<char> respBuf(respLen);
        if (!recvExact(s, respBuf.data(), (int) respLen)) {
            logger::warn("tcp recv body failed, wanted=" + std::to_string(respLen));
            closesocket(s); return {};
        }
        closesocket(s);

        uint8_t version = (uint8_t) respBuf[0];
        uint8_t tag     = (uint8_t) respBuf[1];
        if (version != 0x05) {
            logger::warn("tcp resp bad version: 0x" + std::to_string(version));
            return {};
        }

        if (tag == 0x74) {
            if (respLen < 4) return {};
            uint16_t utfLen = ((uint16_t)(uint8_t)respBuf[2] << 8) | (uint16_t)(uint8_t)respBuf[3];
            if (4 + utfLen > respLen) {
                logger::warn("tcp TC_STRING truncated: utfLen=" + std::to_string(utfLen) + " respLen=" + std::to_string(respLen));
                return {};
            }
            logger::info("tcp resp TC_STRING utfLen=" + std::to_string(utfLen));
            return std::string(respBuf.data() + 4, utfLen);
        } else if (tag == 0x7C) {
            if (respLen < 10) return {};
            uint64_t utfLen = 0;
            for (int i = 0; i < 8; i++) utfLen = (utfLen << 8) | (uint8_t) respBuf[2 + i];
            if (10 + utfLen > respLen) {
                logger::warn("tcp TC_LONGSTRING truncated");
                return {};
            }
            logger::info("tcp resp TC_LONGSTRING utfLen=" + std::to_string(utfLen));
            return std::string(respBuf.data() + 10, (size_t) utfLen);
        }

        logger::warn("tcp resp unknown tag: 0x" + std::to_string(tag));
        return {};
    }
}
