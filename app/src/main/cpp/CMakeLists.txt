cmake_minimum_required(VERSION 3.22.1)

project("voice2txt")

set(CMAKE_CXX_STANDARD 17)

include(FetchContent)

# Enable optimizations
set(GGML_VULKAN OFF CACHE BOOL "vulkan" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "openmp" FORCE)
set(GGML_ARM_NEON ON CACHE BOOL "neon" FORCE)

# Fetch whisper.cpp
FetchContent_Declare(
    whisper_cpp
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG        v1.8.4
)

FetchContent_MakeAvailable(whisper_cpp)

# Ensure release flags are aggressive
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -fexceptions -frtti -ffunction-sections -fdata-sections")
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -O3 -fexceptions -ffunction-sections -fdata-sections")

# Define our JNI library
add_library(
    voice2txt
    SHARED
    jni.c
)

# Link log library and whisper.cpp
target_link_libraries(
    voice2txt
    whisper
    log
    android
)

# Include directories for whisper.cpp
target_include_directories(
    voice2txt
    PRIVATE
    ${whisper_cpp_SOURCE_DIR}
    ${whisper_cpp_SOURCE_DIR}/include
    ${whisper_cpp_SOURCE_DIR}/ggml/include
)
