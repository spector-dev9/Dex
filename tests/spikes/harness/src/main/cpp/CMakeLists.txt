cmake_minimum_required(VERSION 3.22.1)

project(ptyprobe LANGUAGES CXX)

add_library(ptyprobe SHARED pty_probe.cpp)
target_compile_features(ptyprobe PRIVATE cxx_std_17)
