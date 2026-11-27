cmake_minimum_required(VERSION 3.22.1)
project(warp_dpi C)

add_library(warp_dpi SHARED byedpi_jni.c warp_dpi_engine.c warp_dpi_arguments.c private_stream.c
    byedpi/main.c byedpi/proxy.c byedpi/conev.c byedpi/desync.c
    byedpi/extend.c byedpi/mpool.c byedpi/packets.c)
target_compile_definitions(warp_dpi PRIVATE _GNU_SOURCE)
target_compile_features(warp_dpi PRIVATE c_std_11)
target_compile_options(warp_dpi PRIVATE -fvisibility=hidden)
target_link_options(warp_dpi PRIVATE "-Wl,-z,max-page-size=16384")
