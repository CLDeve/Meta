cmake_minimum_required(VERSION 3.22.1)

project(cameraaccess_yuv)

add_library(cameraaccess_yuv SHARED yuv_to_argb.cpp)

target_compile_features(cameraaccess_yuv PRIVATE cxx_std_17)
target_compile_options(cameraaccess_yuv PRIVATE -O3)

find_library(log_lib log)
target_link_libraries(cameraaccess_yuv ${log_lib})

