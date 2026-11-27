cmake_minimum_required(VERSION 3.22)
project(warp_dpi_windows C)
if(NOT WIN32 OR NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
    message(FATAL_ERROR "Windows x64 MinGW toolchain required")
endif()
if(NOT EXISTS "${JAVA_HOME}/include/jni.h" OR NOT EXISTS "${JAVA_HOME}/include/win32/jni_md.h")
    message(FATAL_ERROR "Set JAVA_HOME to a Windows JDK")
endif()
set(JNI_INCLUDE_DIRS "${JAVA_HOME}/include" "${JAVA_HOME}/include/win32")
get_filename_component(SHARED_NATIVE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../shared/native" ABSOLUTE)
add_library(warp_dpi_windows SHARED windows_engine.c windows_jni.c
    "${SHARED_NATIVE}/warp_dpi_arguments.c"
    "${SHARED_NATIVE}/byedpi/main.c" "${SHARED_NATIVE}/byedpi/proxy.c"
    "${SHARED_NATIVE}/byedpi/conev.c" "${SHARED_NATIVE}/byedpi/desync.c"
    "${SHARED_NATIVE}/byedpi/extend.c" "${SHARED_NATIVE}/byedpi/mpool.c"
    "${SHARED_NATIVE}/byedpi/packets.c")
target_include_directories(warp_dpi_windows PRIVATE "${SHARED_NATIVE}" ${JNI_INCLUDE_DIRS})
target_compile_features(warp_dpi_windows PRIVATE c_std_11)
target_compile_definitions(warp_dpi_windows PRIVATE _WIN32_WINNT=0x0601 WIN32_LEAN_AND_MEAN)
target_link_libraries(warp_dpi_windows PRIVATE ws2_32 mswsock)
target_link_options(warp_dpi_windows PRIVATE -static-libgcc)
set_target_properties(warp_dpi_windows PROPERTIES PREFIX "")
