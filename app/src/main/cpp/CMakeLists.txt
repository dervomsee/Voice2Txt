cmake_minimum_required(VERSION 3.22.1)

project("voice2txt")

set(CMAKE_CXX_STANDARD 17)

include(FetchContent)

# Fetch whisper.cpp
FetchContent_Declare(
    whisper_cpp
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG        v1.7.4
)

FetchContent_MakeAvailable(whisper_cpp)

# Enable Vulkan
set(GGML_VULKAN ON CACHE BOOL "vulkan" FORCE)

# Find Vulkan package
find_package(Vulkan REQUIRED)

# Define our JNI library
add_library(
    voice2txt
    SHARED
    jni.c
)

# Link log library, whisper.cpp, and Vulkan
target_link_libraries(
    voice2txt
    whisper
    log
    android
    Vulkan::Vulkan
)

# Include directories for whisper.cpp
target_include_directories(
    voice2txt
    PRIVATE
    ${whisper_cpp_SOURCE_DIR}
    ${whisper_cpp_SOURCE_DIR}/include
    ${whisper_cpp_SOURCE_DIR}/ggml/include
)
