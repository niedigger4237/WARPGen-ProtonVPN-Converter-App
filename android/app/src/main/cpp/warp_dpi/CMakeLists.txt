cmake_minimum_required(VERSION 3.22.1)
project(warp_dpi C)

get_filename_component(SHARED_NATIVE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../shared/native" ABSOLUTE)
add_library(warp_dpi SHARED byedpi_jni.c warp_dpi_engine.c private_stream.c
    "${SHARED_NATIVE}/warp_dpi_arguments.c"
    "${SHARED_NATIVE}/byedpi/main.c" "${SHARED_NATIVE}/byedpi/proxy.c"
    "${SHARED_NATIVE}/byedpi/conev.c" "${SHARED_NATIVE}/byedpi/desync.c"
    "${SHARED_NATIVE}/byedpi/extend.c" "${SHARED_NATIVE}/byedpi/mpool.c"
    "${SHARED_NATIVE}/byedpi/packets.c")
target_include_directories(warp_dpi PRIVATE "${SHARED_NATIVE}")
target_compile_definitions(warp_dpi PRIVATE _GNU_SOURCE)
target_compile_features(warp_dpi PRIVATE c_std_11)
target_compile_options(warp_dpi PRIVATE -fvisibility=hidden)
target_link_options(warp_dpi PRIVATE "-Wl,-z,max-page-size=16384")
