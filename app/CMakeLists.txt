cmake_minimum_required(VERSION 3.4.1)

add_library( # Sets the name of the library.
             native-lib
             SHARED
             src/main/cpp/native-lib.cpp )

# Incluye headers de nodejs-mobile
include_directories(${CMAKE_SOURCE_DIR}/../nodejs-mobile-v0.3.3/include)

find_library( # Sets the name of the path variable.
              log-lib
              log )

# Enlaza con libnode.so (debe estar en jniLibs)
target_link_directories(native-lib PUBLIC
    ${CMAKE_SOURCE_DIR}/src/main/jniLibs/${ANDROID_ABI}
)
target_link_libraries( native-lib
                       ${log-lib}
                       node ) 

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++11") 