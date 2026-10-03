cmake_minimum_required(VERSION 3.22.1)
project(pcs_term CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(pcs_term SHARED term.cpp)

target_link_libraries(pcs_term
        android
        log
        util)          # forkpty/openpty 在 libutil 中

# 让 NDK 允许 forkpty 等声明
target_compile_options(pcs_term PRIVATE -Wall -Wextra)
