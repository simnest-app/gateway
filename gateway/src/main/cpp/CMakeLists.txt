cmake_minimum_required(VERSION 3.31)
project(cloudsim_native C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)
add_compile_options(-Os -fvisibility=hidden -fdata-sections -ffunction-sections $<$<COMPILE_LANGUAGE:CXX>:-fno-exceptions> $<$<COMPILE_LANGUAGE:CXX>:-fno-rtti>)
add_link_options(-Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,--strip-all)
# Prebuilt BoringSSL (io.github.vvb2060.ndk:boringssl, OpenSSL/ISC licence) through prefab, as the Shizuku manager does.
find_package(boringssl REQUIRED CONFIG)
add_library(spake2 SHARED spake2.cpp)
target_link_libraries(spake2 boringssl::crypto_static)

# Port mapping for the shell audio bridge: libplum v0.6.0 (MPL-2.0, third_party/libplum/CLOUDSIM.md), loaded by path
# like WebRTC's library.
file(GLOB PLUM_SOURCES third_party/libplum/src/*.c)
add_library(plumjni SHARED plumjni.c ${PLUM_SOURCES})
target_include_directories(plumjni PRIVATE third_party/libplum/include third_party/libplum/include/plum third_party/libplum/src)
target_compile_definitions(plumjni PRIVATE PLUM_STATIC RELEASE=1)
