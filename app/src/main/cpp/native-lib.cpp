#include <jni.h>
#include <android/log.h>
#include <string>
#include <thread>
#include <unistd.h>
#include <fcntl.h>

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
    // === INICIO: Redirección de stdout/stderr a Logcat ===
    int pipe_fd[2];
    pipe(pipe_fd);
    dup2(pipe_fd[1], STDOUT_FILENO);
    dup2(pipe_fd[1], STDERR_FILENO);

    std::thread([=]() {
        char buffer[128];
        ssize_t count;
        while ((count = read(pipe_fd[0], buffer, sizeof(buffer) - 1)) > 0) {
            buffer[count] = '\0';
            __android_log_print(ANDROID_LOG_INFO, "nodejs", "%s", buffer);
        }
    }).detach();
    // === FIN: Redirección de stdout/stderr a Logcat ===
    int result = node::Start(argc, argv);
    LOGI("node::Start terminó con código %d", result);
    for (jsize i = 0; i < argc; ++i) {
        free(argv[i]);
    }
    delete[] argv;
    return result;
}
} 