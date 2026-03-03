cmake_minimum_required(VERSION 3.4.1)

# 1. LOCK DOWN LIBJPEG-TURBO
set(ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(ENABLE_STATIC ON CACHE BOOL "" FORCE)
set(ENABLE_EXECUTABLES OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(WITH_JPEG8 ON CACHE BOOL "" FORCE)

# CRITICAL FIX 1: Disable NEON to prevent "Illegal Instruction" hardware crash
set(WITH_SIMD OFF CACHE BOOL "" FORCE)

add_subdirectory(libjpeg-turbo)
add_library(native-lib SHARED native-lib.cpp)

# CRITICAL FIX 2: Tell compiler where to find auto-generated headers (jconfig.h)
target_include_directories(native-lib PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/libjpeg-turbo
    ${CMAKE_CURRENT_BINARY_DIR}/libjpeg-turbo
)

# CRITICAL FIX 3: THE CLOAKING SHIELD
# This forces the linker to hide our libjpeg functions from the Sony OS to prevent Symbol Collisions
set_target_properties(native-lib PROPERTIES LINK_FLAGS "-Wl,-Bsymbolic -Wl,--exclude-libs,ALL")

find_library(log-lib log)
target_link_libraries(native-lib jpeg-static ${log-lib})