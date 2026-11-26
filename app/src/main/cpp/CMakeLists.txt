cmake_minimum_required(VERSION 3.22.1)
project(openiv_native LANGUAGES CXX)

add_library(openiv_native SHARED native_deflate.cpp)
set_target_properties(openiv_native PROPERTIES
    CXX_STANDARD 23
    CXX_STANDARD_REQUIRED ON
    CXX_EXTENSIONS OFF
)
target_compile_options(openiv_native PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(openiv_native PRIVATE z)
