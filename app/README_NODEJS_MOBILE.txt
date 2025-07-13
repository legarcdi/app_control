# Integración Node.js Mobile en WebViewApp

## 1. Librerías nativas
Coloca los siguientes archivos en las carpetas correspondientes:

- `app/src/main/jniLibs/arm64-v8a/libnode.so`
- `app/src/main/jniLibs/arm64-v8a/libc++_shared.so`
- (Opcional) Para 32 bits: `app/src/main/jniLibs/armeabi-v7a/`

Puedes obtener estos archivos desde el NDK (para libc++_shared.so) y desde el build de Node.js Mobile (para libnode.so).

## 2. Proyecto Node.js
Coloca tu proyecto Node.js en:

- `app/src/main/assets/nodejs-project/`

El archivo principal debe ser `index.js`.

## 3. JNI y MainActivity
Tu `MainActivity.kt` ya está preparado para arrancar Node.js Mobile usando:

```
external fun startNodeWithArguments(arguments: Array<String>): Int
```

Necesitas el binding JNI en C/C++ para implementar esta función. Ejemplo de stub:

```cpp
#include <jni.h>
#include <string.h>
#include <android/log.h>

extern "C"
JNIEXPORT jint JNICALL
Java_com_t91s_webview_MainActivity_startNodeWithArguments(JNIEnv *env, jobject thiz, jobjectArray arguments) {
    // Aquí debes llamar a node::Start con los argumentos
    // Este es solo un stub de ejemplo
    return 0;
}
```

Compila este archivo como librería nativa `libnode.so` o inclúyelo en tu build de Node.js Mobile.

## 4. build.gradle
Asegúrate de que tu `build.gradle.kts` incluya:

```
android {
    // ...
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
}
```

## 5. Notas
- Si tienes crash por JNI, revisa que los nombres de los métodos coincidan y que los .so estén en la carpeta correcta.
- Si necesitas soporte para más arquitecturas, repite el proceso en las carpetas correspondientes. 