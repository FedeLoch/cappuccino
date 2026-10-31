cmake_minimum_required(VERSION 3.22)
project(cappuccino CXX)

set(ENGINE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../engine/src)

add_library(cappuccino SHARED
    jni.cpp
    ${ENGINE_DIR}/fill.cpp
    ${ENGINE_DIR}/liquify.cpp
    ${ENGINE_DIR}/renderer.cpp
    ${ENGINE_DIR}/stroke.cpp)

target_include_directories(cappuccino PRIVATE ${ENGINE_DIR})
target_compile_options(cappuccino PRIVATE -Wall -Wextra)
target_link_libraries(cappuccino PRIVATE GLESv3 log)
