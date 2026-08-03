#pragma once
#include <string>
#include "craftrise_paths.hpp"

namespace kiwii::launch {

    inline const char* LIB_JARS[] = {
        "cache2k-api-2.2.1.Final.jar",
        "cache2k-core-2.2.1.Final.jar",
        "codecjorbis-20101023.jar",
        "codecwav-20101023.jar",
        "commons-codec-1.9.jar",
        "commons-compress-1.8.1.jar",
        "commons-io-2.4.jar",
        "commons-lang3-3.3.2.jar",
        "commons-logging-1.2.jar",
        "fastutil-8.5.6.jar",
        "gson-2.2.4.jar",
        "guava-17.0.jar",
        "httpclient-4.5.13.jar",
        "httpcore-4.4.15.jar",
        "icu4j-51.2.jar",
        "jinput-2.0.5.jar",
        "jinput-platform-2.0.5-natives-windows.jar",
        "jna-4.5.0.jar",
        "jopt-simple-4.6.jar",
        "jutils-1.0.0.jar",
        "libraryjavasound-20101123.jar",
        "librarylwjglopenal-20100824.jar",
        "log4j-api-2.17.2.jar",
        "log4j-core-2.17.2.jar",
        "lwjgl-2.9.4-nightly-20150209-windows.jar",
        "lwjgl-platform-2.9.4-nightly-20150209-natives-windows.jar",
        "lwjgl-platform-2.9.4-nightly-20150209-windows.jar",
        "lwjgl_util-2.9.4-nightly-20150209-windows.jar",
        "netty-all.jar",
        "oshi-core-1.1.jar",
        "platform-4.5.0.jar",
        "soundsystem-20120107.jar",
        "trove4j-3.0.3.jar",
        "assets.jar"
    };

    inline std::string getJavaLibrariesClasspath(const Paths& p) {
        std::string baseLib = p.base + "\\libraries\\";
        std::string versionsPath = p.clientFolder + "\\RiseClient_1.8.9.jar";
        std::string sb;
        for (const char* jar : LIB_JARS) sb += baseLib + jar + ";";
        sb += versionsPath;
        return sb;
    }
}
