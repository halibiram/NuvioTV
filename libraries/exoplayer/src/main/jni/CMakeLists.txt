cmake_minimum_required(VERSION 3.21.0 FATAL_ERROR)

project(media3_exoplayer_jni C CXX)

set(CMAKE_CXX_STANDARD 17)

add_library(media3_exoplayer_jni
            SHARED
            exoplayer_jni.cpp)

target_link_options(media3_exoplayer_jni
                    PRIVATE "-Wl,-z,max-page-size=16384")
