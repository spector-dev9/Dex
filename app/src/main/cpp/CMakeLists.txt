cmake_minimum_required(VERSION 3.22.1)

project(opencode_pty LANGUAGES CXX)

add_library(opencode_pty SHARED opencode_pty.cpp)
target_compile_features(opencode_pty PRIVATE cxx_std_17)
