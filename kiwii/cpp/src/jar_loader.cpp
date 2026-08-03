#include "jar_loader.h"
#include <Windows.h>
#include <cstdio>
#include <thread>
#include <chrono>
#include <fstream>
#include <vector>
#include <iostream>
#include <algorithm>
#include <atomic>

extern std::atomic<bool> g_bypassHook;


namespace JarLoader {
    static jclass g_ingameHudClass = nullptr;
    static jmethodID g_renderFrameMethod = nullptr;
    static bool g_hudLookupAttempted = false;


    std::vector<uint8_t> getBytes(const std::string& file_path) {
        std::ifstream file(file_path, std::ios::binary | std::ios::ate);
        if (!file) {
            printf("[-] Failed to open file: %s\n", file_path.c_str());
            return {};
        }
        
        size_t file_size = static_cast<size_t>(file.tellg());
        file.seekg(0, std::ios::beg);
        
        std::vector<uint8_t> buffer(file_size);
        if (file_size > 0) {
            file.read(reinterpret_cast<char*>(buffer.data()), file_size);
        }
        file.close();
        
        return buffer;
    }

    bool LoadJar(JNIEnv* env, jvmtiEnv* jvmti, const char* jarPath) {
        printf("[*] JAR Loading START\n");
        printf("[*] JAR Path: %s\n", jarPath);
        



        auto FindClass_ = (jclass(JNICALL*)(JNIEnv*, const char*))unhook(env->functions->FindClass);
        auto GetMethodID_ = (jmethodID(JNICALL*)(JNIEnv*, jclass, const char*, const char*))unhook(env->functions->GetMethodID);
        auto GetStaticMethodID_ = (jmethodID(JNICALL*)(JNIEnv*, jclass, const char*, const char*))unhook(env->functions->GetStaticMethodID);
        auto NewObject_ = (jobject(JNICALL*)(JNIEnv*, jclass, jmethodID, ...))unhook(env->functions->NewObject);
        auto CallObjectMethod_ = (jobject(JNICALL*)(JNIEnv*, jobject, jmethodID, ...))unhook(env->functions->CallObjectMethod);
        auto CallIntMethod_ = (jint(JNICALL*)(JNIEnv*, jobject, jmethodID, ...))unhook(env->functions->CallIntMethod);      
        auto CallStaticVoidMethod_ = (void(JNICALL*)(JNIEnv*, jclass, jmethodID, ...))unhook(env->functions->CallStaticVoidMethod);
        auto CallBooleanMethod_ = (jboolean(JNICALL*)(JNIEnv*, jobject, jmethodID, ...))unhook(env->functions->CallBooleanMethod);
        auto NewStringUTF_ = (jstring(JNICALL*)(JNIEnv*, const char*))unhook(env->functions->NewStringUTF);
        auto NewGlobalRef_ = (jobject(JNICALL*)(JNIEnv*, jobject))unhook(env->functions->NewGlobalRef);
        auto DeleteLocalRef_ = (void(JNICALL*)(JNIEnv*, jobject))unhook(env->functions->DeleteLocalRef);
        auto GetStringUTFChars_ = (const char*(JNICALL*)(JNIEnv*, jstring, jboolean*))unhook(env->functions->GetStringUTFChars);
        auto ReleaseStringUTFChars_ = (void(JNICALL*)(JNIEnv*, jstring, const char*))unhook(env->functions->ReleaseStringUTFChars);
        auto GetObjectClass_ = (jclass(JNICALL*)(JNIEnv*, jobject))unhook(env->functions->GetObjectClass);
        auto NewByteArray_ = (jbyteArray(JNICALL*)(JNIEnv*, jsize))unhook(env->functions->NewByteArray);
        auto SetByteArrayRegion_ = (void(JNICALL*)(JNIEnv*, jbyteArray, jsize, jsize, const jbyte*))unhook(env->functions->SetByteArrayRegion);
        auto GetByteArrayRegion_ = (void(JNICALL*)(JNIEnv*, jbyteArray, jsize, jsize, jbyte*))unhook(env->functions->GetByteArrayRegion);
        auto DefineClass_ = (jclass(JNICALL*)(JNIEnv*, const char*, jobject, const jbyte*, jsize))unhook(env->functions->DefineClass);
        auto ExceptionCheck_ = (jboolean(JNICALL*)(JNIEnv*))unhook(env->functions->ExceptionCheck);
        auto ExceptionClear_ = (void(JNICALL*)(JNIEnv*))unhook(env->functions->ExceptionClear);
        auto ExceptionOccurred_ = (jthrowable(JNICALL*)(JNIEnv*))unhook(env->functions->ExceptionOccurred);
        auto CallVoidMethod_ = (void(JNICALL*)(JNIEnv*, jobject, jmethodID, ...))unhook(env->functions->CallVoidMethod);
        



        jobject classLoader = nullptr;
        
        jclass gl11Class = FindClass_(env, "org/lwjgl/opengl/GL11");
        if (!gl11Class) {
            printf("[-] GL11 class not found!\n");
            return false;
        }
        
        jclass clsClass = FindClass_(env, "java/lang/Class");
        jmethodID midGetClassLoader = GetMethodID_(env, clsClass, "getClassLoader", "()Ljava/lang/ClassLoader;");
        
        jobject loader = CallObjectMethod_(env, gl11Class, midGetClassLoader);
        if (loader) {
            classLoader = NewGlobalRef_(env, loader);
            printf("gl11: %p\n", classLoader);
        } else {
            printf("[-] Failed to get GL11 ClassLoader\n");
            return false;
        }
        



        std::vector<uint8_t> jar_data = getBytes(jarPath);
        if (jar_data.empty()) {
            printf("[-] JAR data is empty or file not found\n");
            return false;
        }
        printf("jar size: %d \n", (int)jar_data.size());
        



        jclass bais_cls = FindClass_(env, "java/io/ByteArrayInputStream");
        jmethodID bais_init = GetMethodID_(env, bais_cls, "<init>", "([B)V");
        
        jclass jis_cls = FindClass_(env, "java/util/jar/JarInputStream");
        jmethodID jis_init = GetMethodID_(env, jis_cls, "<init>", "(Ljava/io/InputStream;)V");
        jmethodID jis_get_next = GetMethodID_(env, jis_cls, "getNextJarEntry", "()Ljava/util/jar/JarEntry;");
        jmethodID jis_read = GetMethodID_(env, jis_cls, "read", "([BII)I");
        
        jclass entry_cls = FindClass_(env, "java/util/jar/JarEntry");
        jmethodID entry_get_name = GetMethodID_(env, entry_cls, "getName", "()Ljava/lang/String;");
        
        if (!bais_cls || !jis_cls || !entry_cls) {
            printf("[-] Failed to find necessary JNI classes\n");
            return false;
        }
        



        jbyteArray jar_bytes_arr = NewByteArray_(env, static_cast<jsize>(jar_data.size()));
        SetByteArrayRegion_(env, jar_bytes_arr, 0, static_cast<jsize>(jar_data.size()), reinterpret_cast<const jbyte*>(jar_data.data()));
        
        jobject bais = NewObject_(env, bais_cls, bais_init, jar_bytes_arr);
        jobject jis = NewObject_(env, jis_cls, jis_init, bais);
        
        if (!jis) {
            printf("[-] Failed to initialize JarInputStream\n");
            DeleteLocalRef_(env, jar_bytes_arr);
            return false;
        }
        



        const int buffer_size = 65536;
        jbyteArray read_buffer = NewByteArray_(env, buffer_size);
        
        std::vector<std::pair<std::string, std::vector<uint8_t>>> pending_classes;
        const std::string extension = ".class";
        
        jobject jar_entry = nullptr;
        int total_entries = 0;
        
        
        while ((jar_entry = CallObjectMethod_(env, jis, jis_get_next))) {
            jstring jname = (jstring)CallObjectMethod_(env, jar_entry, entry_get_name);
            if (jname) {
                const char* name_ptr = GetStringUTFChars_(env, jname, nullptr);
                std::string name(name_ptr);
                ReleaseStringUTFChars_(env, jname, name_ptr);
                
                if (name.length() > extension.length() && 
                    name.compare(name.length() - extension.length(), extension.length(), extension) == 0) {
                    
                    std::vector<uint8_t> class_bytes;
                    jint count;

                    while ((count = CallIntMethod_(env, jis, jis_read, read_buffer, 0, buffer_size)) != -1) {
                        if (count > 0) {
                            size_t current_size = class_bytes.size();
                            class_bytes.resize(current_size + count);
                            GetByteArrayRegion_(env, read_buffer, 0, count, reinterpret_cast<jbyte*>(&class_bytes[current_size]));
                        }
                    }
                    pending_classes.push_back({name, std::move(class_bytes)});
                    total_entries++;
                }
                DeleteLocalRef_(env, jname);
            }
            DeleteLocalRef_(env, jar_entry);
        }
        

        DeleteLocalRef_(env, read_buffer);
        DeleteLocalRef_(env, jis);
        DeleteLocalRef_(env, bais);
        DeleteLocalRef_(env, jar_bytes_arr);
        
        printf(" %d classes from JAR\n", total_entries);
        



        
        int defined_count = 0;
        int max_passes = 5;
        
        for (int pass = 0; pass < max_passes && !pending_classes.empty(); pass++) {
            std::vector<std::pair<std::string, std::vector<uint8_t>>> failed_this_pass;
            
            for (auto& entry : pending_classes) {
                std::string internalName = entry.first.substr(0, entry.first.length() - 6);
                
                jclass cls = DefineClass_(
                    env,
                    internalName.c_str(),
                    classLoader,
                    reinterpret_cast<const jbyte*>(entry.second.data()),
                    static_cast<jint>(entry.second.size())
                );
                
                if (cls) {
                    defined_count++;
                    DeleteLocalRef_(env, cls);
                } else {
                    if (ExceptionCheck_(env)) ExceptionClear_(env);
                    failed_this_pass.push_back(std::move(entry));
                }
            }
            
            pending_classes = std::move(failed_this_pass);
            
            if (pending_classes.empty()) break;
        }
        
        



        

        jclass classLoaderClass = GetObjectClass_(env, classLoader);
        jmethodID loadClassMethod = GetMethodID_(env, classLoaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        
        auto FindClassByName_ = [&](const char* name) -> jclass {
            jstring jname = NewStringUTF_(env, name);
            jclass result = (jclass)CallObjectMethod_(env, classLoader, loadClassMethod, jname);
            DeleteLocalRef_(env, jname);
            return result;
        };
        
        jclass mainClass = FindClassByName_("me.kiwii.Main");

        if (!mainClass) {
            printf("[-] Main class not found!\n");
            return false;
        }

        printf("Main ++ \n");

        jclass hudClass = FindClassByName_("me.kiwii.ui.IngameHud");
        if (ExceptionCheck_(env)) ExceptionClear_(env);
        if (hudClass) {
            jmethodID renderFrameMethod = GetStaticMethodID_(env, hudClass, "renderFrame", "()V");
            if (ExceptionCheck_(env)) ExceptionClear_(env);
            if (renderFrameMethod) {
                g_ingameHudClass = (jclass)NewGlobalRef_(env, hudClass);
                g_renderFrameMethod = renderFrameMethod;
                g_hudLookupAttempted = true;
            } else {
                g_hudLookupAttempted = true;
                printf("[-] me.kiwii.ui.IngameHud.renderFrame not found; HUD render hook disabled\n");
            }
            DeleteLocalRef_(env, hudClass);
        } else {
            g_hudLookupAttempted = true;
            printf("[-] me.kiwii.ui.IngameHud not found; HUD render hook disabled\n");
        }
        




        jmethodID startMethod = GetStaticMethodID_(env, mainClass, "StartClient", "(Ljava/util/List;)V");
        if (ExceptionCheck_(env)) ExceptionClear_(env);

        if (!startMethod) {
            printf("[*] StartClient not found, trying main(List, Object)...\n");
            startMethod = GetStaticMethodID_(env, mainClass, "main", "(Ljava/util/List;Ljava/lang/Object;)V");
            if (ExceptionCheck_(env)) ExceptionClear_(env);
        }

        if (!startMethod) {
            printf("[-] No suitable method found!\n");
            return false;
        }

 

        jclass arrayListClass = FindClass_(env, "java/util/ArrayList");
        jmethodID arrayListConstructor = GetMethodID_(env, arrayListClass, "<init>", "()V");
        jmethodID addMethod = GetMethodID_(env, arrayListClass, "add", "(Ljava/lang/Object;)Z");
        
        jobject classList = NewObject_(env, arrayListClass, arrayListConstructor);
        

        auto GetLoadedClasses_ = (jvmtiError(JNICALL*)(jvmtiEnv*, jint*, jclass**))unhook(jvmti->functions->GetLoadedClasses);
        auto Deallocate_ = (jvmtiError(JNICALL*)(jvmtiEnv*, unsigned char*))unhook(jvmti->functions->Deallocate);
        
        jint allClassCount = 0;
        jclass* allClasses = nullptr;
        
        g_bypassHook.store(true);
        bool glcOk = GetLoadedClasses_(jvmti, &allClassCount, &allClasses) == JVMTI_ERROR_NONE;
        g_bypassHook.store(false);

        if (glcOk) {
            printf("Tclasses: %d\n", allClassCount);

            auto GetStringUTFChars2_ = (const char*(JNICALL*)(JNIEnv*, jstring, jboolean*))unhook(env->functions->GetStringUTFChars);
            auto ReleaseStringUTFChars2_ = (void(JNICALL*)(JNIEnv*, jstring, const char*))unhook(env->functions->ReleaseStringUTFChars);
            jclass classClass2 = FindClass_(env, "java/lang/Class");
            jmethodID getNameMethod2 = GetMethodID_(env, classClass2, "getName", "()Ljava/lang/String;");

            int added = 0;
            int filtered = 0;
            for (jint i = 0; i < allClassCount; i++) {
                if (!allClasses[i]) continue;


                jstring jname = (jstring)CallObjectMethod_(env, allClasses[i], getNameMethod2);
                if (ExceptionCheck_(env)) { ExceptionClear_(env); continue; }
                if (!jname) {
                    CallBooleanMethod_(env, classList, addMethod, allClasses[i]);
                    added++;
                    continue;
                }

                const char* nameStr = GetStringUTFChars2_(env, jname, nullptr);
                bool isKiwii = nameStr && strncmp(nameStr, "me.kiwii.", 8) == 0;
                if (nameStr) ReleaseStringUTFChars2_(env, jname, nameStr);
                DeleteLocalRef_(env, jname);

                if (isKiwii) {
                    filtered++;
                    continue; 
                }

                CallBooleanMethod_(env, classList, addMethod, allClasses[i]);
                added++;
            }
            Deallocate_(jvmti, (unsigned char*)allClasses);
            printf(": %d added, %d filtered\n", added, filtered);
        }
        


        if (ExceptionCheck_(env)) ExceptionClear_(env);


        CallStaticVoidMethod_(env, mainClass, startMethod, classList);
        
        if (ExceptionCheck_(env)) {
            printf("[-] Exception during method call:\n");
            jthrowable ex = ExceptionOccurred_(env);
            ExceptionClear_(env);
            if (ex) {

                jclass throwableClass = FindClass_(env, "java/lang/Throwable");
                jmethodID toStringMethod = GetMethodID_(env, throwableClass, "toString", "()Ljava/lang/String;");
                jmethodID getMessageMethod = GetMethodID_(env, throwableClass, "getMessage", "()Ljava/lang/String;");

                jstring msg = (jstring)CallObjectMethod_(env, ex, toStringMethod);
                if (msg) {
                    const char* msgStr = GetStringUTFChars_(env, msg, nullptr);
                    printf("[-] Exception: %s\n", msgStr);
                    ReleaseStringUTFChars_(env, msg, msgStr);
                }


                jclass swClass = FindClass_(env, "java/io/StringWriter");
                jclass pwClass = FindClass_(env, "java/io/PrintWriter");
                jmethodID swInit = GetMethodID_(env, swClass, "<init>", "()V");
                jmethodID pwInit = GetMethodID_(env, pwClass, "<init>", "(Ljava/io/Writer;)V");
                jmethodID printStackMethod = GetMethodID_(env, throwableClass, "printStackTrace", "(Ljava/io/PrintWriter;)V");
                jmethodID swToString = GetMethodID_(env, swClass, "toString", "()Ljava/lang/String;");

                if (swClass && pwClass && swInit && pwInit && printStackMethod && swToString) {
                    jobject sw = NewObject_(env, swClass, swInit);
                    jobject pw = NewObject_(env, pwClass, pwInit, sw);


                    CallVoidMethod_(env, ex, printStackMethod, pw);

                    jstring stackStr = (jstring)CallObjectMethod_(env, sw, swToString);
                    if (stackStr) {
                        const char* stackCStr = GetStringUTFChars_(env, stackStr, nullptr);
                        printf("[-] Stack trace:\n%s\n", stackCStr);
                        ReleaseStringUTFChars_(env, stackStr, stackCStr);
                    }
                }

                if (ExceptionCheck_(env)) ExceptionClear_(env);
            }
        }
        
        return true;
    }

    bool IsHudReady() {
        return g_hudLookupAttempted && g_ingameHudClass && g_renderFrameMethod;
    }

    bool RenderHud(JNIEnv* env) {
        if (!env || !IsHudReady()) {
            return false;
        }

        auto CallStaticVoidMethod_ = (void(JNICALL*)(JNIEnv*, jclass, jmethodID, ...))unhook(env->functions->CallStaticVoidMethod);
        auto ExceptionCheck_ = (jboolean(JNICALL*)(JNIEnv*))unhook(env->functions->ExceptionCheck);
        auto ExceptionClear_ = (void(JNICALL*)(JNIEnv*))unhook(env->functions->ExceptionClear);

        CallStaticVoidMethod_(env, g_ingameHudClass, g_renderFrameMethod);
        if (ExceptionCheck_(env)) {
            ExceptionClear_(env);
            return false;
        }

        return true;
    }
}
