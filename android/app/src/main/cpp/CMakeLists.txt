cmake_minimum_required(VERSION 3.22.1)
project(elbridge C)

set(CMAKE_C_STANDARD 11)

add_library(elbridge SHARED glfw.c jni_bridge.c jvm.c perf.c)
target_compile_options(elbridge PRIVATE -O2 -fvisibility=hidden -Wall -Wno-unused-result)
# the whole GLFW API must stay exported for LWJGL
target_link_options(elbridge PRIVATE -Wl,--no-undefined)
target_link_libraries(elbridge android log EGL dl)
