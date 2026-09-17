cmake_minimum_required(VERSION 3.22.1)
project(inferno LANGUAGES C CXX ASM)          # ASM: KleidiAI .S kernels

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE RelWithDebInfo)      # Gradle passes this explicitly; keeps -g for symbolication
endif()

# llama.cpp is vendored as a git submodule at the repository root and compiled in-tree (static)
# so the whole engine ships as one libinferno.so.
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp)
if(NOT EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "llama.cpp submodule missing: git submodule update --init --recursive")
endif()

# Cache-forced so Gradle arguments cannot silently diverge from the verified configuration.
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL        OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH      "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)   # A78/A55: dotprod + fp16, no i8mm/SVE
set(GGML_CPU_KLEIDIAI      ON  CACHE BOOL "" FORCE)
set(GGML_CPU_REPACK        ON  CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)   # persistent ggml threadpools instead (inferno_engine.cpp)
set(GGML_LLAMAFILE         OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE            OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN            OFF CACHE BOOL "" FORCE)

# Adreno GPU path: ggml-opencl (Qualcomm-tuned kernels, embedded as strings at build time -> Python3 needed).
# The backend is linked against inferno_opencl instead of a libOpenCL.so: that target defines every cl*
# entry point as a forwarder into the vendor driver dlopen'ed at runtime, so the same libinferno.so runs on
# phones without OpenCL (the registry then simply lists no GPU device). FindOpenCL accepts a target name
# for OpenCL_LIBRARY; the header-only Khronos package is a submodule.
set(OPENCL_HEADERS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/OpenCL-Headers)
if(NOT EXISTS ${OPENCL_HEADERS_DIR}/CL/cl.h)
    message(FATAL_ERROR "OpenCL-Headers submodule missing: git submodule update --init --recursive")
endif()
add_library(inferno_opencl STATIC inferno_opencl.cpp)
target_include_directories(inferno_opencl PUBLIC ${OPENCL_HEADERS_DIR})
target_compile_definitions(inferno_opencl PRIVATE CL_TARGET_OPENCL_VERSION=300)
target_compile_options(inferno_opencl PRIVATE -fvisibility=hidden -Wall -Wextra -Werror)
target_link_libraries(inferno_opencl PRIVATE log)
set(OpenCL_INCLUDE_DIR ${OPENCL_HEADERS_DIR} CACHE PATH "" FORCE)
set(OpenCL_LIBRARY     inferno_opencl      CACHE STRING "" FORCE)
set(GGML_OPENCL                     ON  CACHE BOOL "" FORCE)
set(GGML_OPENCL_EMBED_KERNELS       ON  CACHE BOOL "" FORCE)
set(GGML_OPENCL_USE_ADRENO_KERNELS  ON  CACHE BOOL "" FORCE)
set(GGML_OPENCL_TARGET_VERSION      "300" CACHE STRING "" FORCE)
set(GGML_ALL_WARNINGS      OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS     OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL          OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD       ON  CACHE BOOL "" FORCE)
option(INFERNO_LTO "Thin LTO (enabled only by the Gradle release build type)" OFF)
if(INFERNO_LTO AND CMAKE_BUILD_TYPE MATCHES "^Rel")
    # Not yet exercised on this tree; drop the Gradle argument if the final link misbehaves.
    set(GGML_LTO ON CACHE BOOL "" FORCE)
endif()
# Offline KleidiAI (optional vendored copy): -DFETCHCONTENT_SOURCE_DIR_KLEIDIAI=<dir> -DFETCHCONTENT_FULLY_DISCONNECTED=ON

add_subdirectory(${LLAMA_DIR} ${CMAKE_BINARY_DIR}/llama.cpp EXCLUDE_FROM_ALL)

add_library(inferno SHARED
    inferno_jni.cpp          # JNI glue only
    inferno_engine.cpp       # Engine singleton: lifecycle, model, context, threadpools, sampler
    inferno_engine_gen.cpp   # Engine: prompt rendering, tokenisation, prefix reuse, generation
    inferno_estimate.cpp     # Engine: GGUF facts + no_alloc memory estimate
    inferno_bench.cpp        # Engine: benchmark
    inferno_cpu.cpp          # core topology + feature detection (no ggml)
    inferno_log.cpp)         # logcat sink for ggml/llama/mtmd
                             # inferno_opencl.cpp: OpenCL loader + policy, its own target above
target_include_directories(inferno PRIVATE
    ${LLAMA_DIR}/include ${LLAMA_DIR}/ggml/include ${LLAMA_DIR}/tools/mtmd
    ${LLAMA_DIR}/src)        # src: llama-ext.h only (llama_get_memory_breakdown)
target_compile_options(inferno PRIVATE
    -fvisibility=hidden -fvisibility-inlines-hidden -fexceptions -frtti
    -Wall -Wextra -Werror -Wno-unused-parameter)
target_link_libraries(inferno PRIVATE mtmd llama ggml inferno_opencl log android)
target_link_options(inferno PRIVATE
    -Wl,-z,max-page-size=16384 -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,--build-id=sha1)
