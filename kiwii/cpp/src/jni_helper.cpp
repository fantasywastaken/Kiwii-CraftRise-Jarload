#include "jni_helper.h"
#include "jar_loader.h"   
#include <cstdio>
#include <string>
#include <Windows.h>
#include "bypass.hpp"

extern std::atomic<bool> g_bypassHook;

JavaVM* JNIHelper::g_jvm = nullptr;
JNIEnv* JNIHelper::g_env = nullptr;
jvmtiEnv* JNIHelper::g_jvmti = nullptr;
jobject JNIHelper::g_customClassLoader = nullptr;
std::vector<jclass> JNIHelper::g_loadedClasses;

JNIHelper::tFindClass JNIHelper::oFindClass = nullptr;
JNIHelper::tGetMethodID JNIHelper::oGetMethodID = nullptr;
JNIHelper::tGetStaticMethodID JNIHelper::oGetStaticMethodID = nullptr;
JNIHelper::tGetFieldID JNIHelper::oGetFieldID = nullptr;
JNIHelper::tGetStaticFieldID JNIHelper::oGetStaticFieldID = nullptr;
JNIHelper::tGetObjectField JNIHelper::oGetObjectField = nullptr;
JNIHelper::tGetStaticObjectField JNIHelper::oGetStaticObjectField = nullptr;
JNIHelper::tCallStaticObjectMethod JNIHelper::oCallStaticObjectMethod = nullptr;
JNIHelper::tCallObjectMethod JNIHelper::oCallObjectMethod = nullptr;
JNIHelper::tCallVoidMethod JNIHelper::oCallVoidMethod = nullptr;
JNIHelper::tCallDoubleMethod JNIHelper::oCallDoubleMethod = nullptr;
JNIHelper::tNewStringUTF JNIHelper::oNewStringUTF = nullptr;
JNIHelper::tGetStringUTFChars JNIHelper::oGetStringUTFChars = nullptr;
JNIHelper::tReleaseStringUTFChars JNIHelper::oReleaseStringUTFChars = nullptr;
JNIHelper::tGetArrayLength JNIHelper::oGetArrayLength = nullptr;
JNIHelper::tGetObjectArrayElement JNIHelper::oGetObjectArrayElement = nullptr;
JNIHelper::tNewGlobalRef JNIHelper::oNewGlobalRef = nullptr;
JNIHelper::tDeleteLocalRef JNIHelper::oDeleteLocalRef = nullptr;
JNIHelper::tNewObject JNIHelper::oNewObject = nullptr;

jobject JNIHelper::GetFieldObject(JNIEnv* env, jobject obj, const char* fieldName) {
    if (!obj) return nullptr;

    auto GetObjectClass_ = (jclass(JNICALL*)(JNIEnv*, jobject))unhook(env->functions->GetObjectClass);
    jclass objClass = GetObjectClass_(env, obj);

    jclass classClass = oFindClass(env, "java/lang/Class");
    jmethodID getDeclaredField = oGetMethodID(
        env, classClass, "getDeclaredField",
        "(Ljava/lang/String;)Ljava/lang/reflect/Field;"
    );

    jstring name = oNewStringUTF(env, fieldName);

    jobject field = oCallObjectMethod(env, objClass, getDeclaredField, name);
    if (!field) {
        oDeleteLocalRef(env, name);
        oDeleteLocalRef(env, objClass);
        return nullptr;
    }

    jclass fieldClass = oFindClass(env, "java/lang/reflect/Field");

    jmethodID setAccessible = oGetMethodID(env, fieldClass, "setAccessible", "(Z)V");
    jmethodID getMethod = oGetMethodID(env, fieldClass, "get", "(Ljava/lang/Object;)Ljava/lang/Object;");

    oCallVoidMethod(env, field, setAccessible, JNI_TRUE);

    jobject result = oCallObjectMethod(env, field, getMethod, obj);

    oDeleteLocalRef(env, name);
    oDeleteLocalRef(env, field);
    oDeleteLocalRef(env, objClass);

    return result;
}

double JNIHelper::GetDoubleField(JNIEnv* env, jobject obj, const char* fieldName) {
    jobject valObj = GetFieldObject(env, obj, fieldName);
    if (!valObj) return 0.0;

    jclass doubleClass = oFindClass(env, "java/lang/Double");
    jmethodID doubleValue = oGetMethodID(env, doubleClass, "doubleValue", "()D");

    double val = oCallDoubleMethod(env, valObj, doubleValue);

    oDeleteLocalRef(env, valObj);
    oDeleteLocalRef(env, doubleClass);
    return val;
}

float JNIHelper::GetFloatField(JNIEnv* env, jobject obj, const char* fieldName) {
    jobject valObj = GetFieldObject(env, obj, fieldName);
    if (!valObj) return 0.0f;

    jclass floatClass = oFindClass(env, "java/lang/Float");
    jmethodID floatValue = oGetMethodID(env, floatClass, "floatValue", "()F");

    auto CallFloatMethod_ = (jfloat(JNICALL*)(JNIEnv*, jobject, jmethodID, ...))unhook(env->functions->CallFloatMethod);
    float val = CallFloatMethod_(env, valObj, floatValue);

    oDeleteLocalRef(env, valObj);
    oDeleteLocalRef(env, floatClass);
    return val;
}

void JNIHelper::HookFunctions() {
    void** vt = *(void***)g_env;


    oFindClass             = (tFindClass)            unhook(vt[6]);
    oGetMethodID           = (tGetMethodID)          unhook(vt[33]);
    oCallObjectMethod      = (tCallObjectMethod)     unhook(vt[34]);
    oCallVoidMethod        = (tCallVoidMethod)       unhook(vt[61]);
    oCallDoubleMethod      = (tCallDoubleMethod)     unhook(vt[37]);
    oGetFieldID            = (tGetFieldID)           unhook(vt[94]);
    oGetObjectField        = (tGetObjectField)       unhook(vt[95]);
    oGetStaticMethodID     = (tGetStaticMethodID)    unhook(vt[113]);
    oCallStaticObjectMethod= (tCallStaticObjectMethod)unhook(vt[114]);
    oGetStaticFieldID      = (tGetStaticFieldID)     unhook(vt[144]);
    oGetStaticObjectField  = (tGetStaticObjectField) unhook(vt[145]);
    oNewStringUTF          = (tNewStringUTF)         unhook(vt[167]);
    oGetStringUTFChars     = (tGetStringUTFChars)    unhook(vt[169]);
    oReleaseStringUTFChars = (tReleaseStringUTFChars)unhook(vt[170]);
    oGetArrayLength        = (tGetArrayLength)       unhook(vt[171]);
    oGetObjectArrayElement = (tGetObjectArrayElement)unhook(vt[173]);
    oNewGlobalRef          = (tNewGlobalRef)         unhook(vt[21]);
    oDeleteLocalRef        = (tDeleteLocalRef)       unhook(vt[23]);
    oNewObject             = (tNewObject)            unhook(vt[28]);
}

bool JNIHelper::FindCrClassLoader() {

    jclass classClass = oFindClass(g_env, "java/lang/Class");
    jmethodID getNameMethod = oGetMethodID(g_env, classClass, "getName", "()Ljava/lang/String;");

    if (g_jvm->GetEnv((void**)&g_jvmti, JVMTI_VERSION_1_2) != JNI_OK) {
        printf("[-] Failed to get JVMTI\n");
        return false;
    }



    auto GetLoadedClasses_ = (jvmtiError(JNICALL*)(jvmtiEnv*, jint*, jclass**))
        unhook(g_jvmti->functions->GetLoadedClasses);
    auto Deallocate_ = (jvmtiError(JNICALL*)(jvmtiEnv*, unsigned char*))
        unhook(g_jvmti->functions->Deallocate);

    jint classCount = 0;
    jclass* classes = nullptr;


    g_bypassHook.store(true);
    jvmtiError loadedClassesErr = GetLoadedClasses_(g_jvmti, &classCount, &classes);
    g_bypassHook.store(false);

    if (loadedClassesErr != JVMTI_ERROR_NONE) {
        printf("[-] Failed to get loaded classes\n");
        return false;
    }

    printf(" %d loaded classes...\n", classCount);

    int loadedMinecraft = 0;
    int loadedCraftrise = 0;

    for (jint i = 0; i < classCount; i++) {
        jstring name = (jstring)oCallObjectMethod(g_env, classes[i], getNameMethod);
        if (g_env->ExceptionCheck()) { g_env->ExceptionClear(); continue; }
        if (!name) continue;

        const char* nameStr = oGetStringUTFChars(g_env, name, nullptr);
        if (nameStr) {
            std::string n(nameStr);

            if (n.find("$Lambda") == std::string::npos) {
                if (n.rfind("net/minecraft/", 0) == 0) {
                    g_loadedClasses.push_back((jclass)oNewGlobalRef(g_env, classes[i]));
                    loadedMinecraft++;
                } else if (n.rfind("craftrise.", 0) == 0 || n.rfind("crsecond.", 0) == 0) {
                    g_loadedClasses.push_back((jclass)oNewGlobalRef(g_env, classes[i]));
                    loadedCraftrise++;
                }
            }

            oReleaseStringUTFChars(g_env, name, nameStr);
        }
        oDeleteLocalRef(g_env, name);
    }

    Deallocate_(g_jvmti, (unsigned char*)classes);


    return !g_loadedClasses.empty();
}

jclass JNIHelper::FindClassByName(const char* targetName) {
    jclass classClass = oFindClass(g_env, "java/lang/Class");
    jmethodID getNameMethod = oGetMethodID(g_env, classClass, "getName", "()Ljava/lang/String;");
    for (jclass cls : g_loadedClasses) {
        jstring name = (jstring)oCallObjectMethod(g_env, cls, getNameMethod);
        if (g_env->ExceptionCheck()) { g_env->ExceptionClear(); continue; }
        if (!name) continue;
        const char* str = oGetStringUTFChars(g_env, name, nullptr);
        bool match = str && strcmp(str, targetName) == 0;
        if (str) oReleaseStringUTFChars(g_env, name, str);
        oDeleteLocalRef(g_env, name);
        if (match) {
            printf("[+] Found class: %s\n", targetName);
            return cls;
        }
    }

    printf("[-] Class not found: %s\n", targetName);
    return nullptr;
}

bool JNIHelper::Initialize(JNIEnv* env) {
    if (!env) return false;

    g_env = env;
    if (g_env->GetJavaVM(&g_jvm) != JNI_OK) {
        printf("[-] Failed to get JavaVM from provided JNIEnv\n");
        return false;
    }

    HookFunctions();
    return FindCrClassLoader();
}
