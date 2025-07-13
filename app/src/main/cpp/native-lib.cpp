#include <jni.h>
#include <android/log.h>
#include <string>

#define LOG_TAG "NodeJSMobileJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Declaración de la función de Node.js Mobile
namespace node {
    int Start(int argc, char* argv[]);
}

extern "C" {
JNIEXPORT jint JNICALL
Java_com_t91s_webview_MainActivity_startNodeWithArguments(JNIEnv* env, jobject /* this */, jobjectArray arguments) {
    LOGI("Entrando a startNodeWithArguments JNI");
    jsize argc = env->GetArrayLength(arguments);
    char** argv = new char*[argc];
    for (jsize i = 0; i < argc; ++i) {
        jstring arg = (jstring) env->GetObjectArrayElement(arguments, i);
        const char* utf = env->GetStringUTFChars(arg, 0);
        argv[i] = strdup(utf);
        env->ReleaseStringUTFChars(arg, utf);
    }
    LOGI("Llamando a node::Start con %d argumentos", argc);
    int result = node::Start(argc, argv);
    LOGI("node::Start terminó con código %d", result);
    for (jsize i = 0; i < argc; ++i) {
        free(argv[i]);
    }
    delete[] argv;
    return result;
}
} 