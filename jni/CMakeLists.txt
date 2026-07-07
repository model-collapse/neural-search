# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# Builds libnsparse_jni.so — the JNI bridge between the neural-search plugin
# (org.opensearch.neuralsearch.sparse.jni.NsparseJni) and the neural-sparse-cpp
# SEISMIC library.
#
# The neural-sparse-cpp sources are pulled in via add_subdirectory; its path is
# supplied by -DNSPARSE_CPP_DIR=<path> (build-jni.sh defaults it to the sibling
# ../neural-sparse-cpp checkout).

cmake_minimum_required(VERSION 3.15)
project(nsparse_jni LANGUAGES CXX)

if(NOT DEFINED NSPARSE_CPP_DIR)
    message(FATAL_ERROR "NSPARSE_CPP_DIR must point to a neural-sparse-cpp checkout")
endif()
get_filename_component(NSPARSE_CPP_DIR "${NSPARSE_CPP_DIR}" ABSOLUTE)
if(NOT EXISTS "${NSPARSE_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "NSPARSE_CPP_DIR (${NSPARSE_CPP_DIR}) is not a neural-sparse-cpp checkout")
endif()

find_package(JNI REQUIRED)

# Build the core C++ library from the neural-sparse-cpp checkout. Its tests /
# python / benchmarks stay off; we only need the `nsparse` library target.
add_subdirectory("${NSPARSE_CPP_DIR}" nsparse-build)

add_library(nsparse_jni SHARED nsparse_jni.cpp)

target_include_directories(nsparse_jni PRIVATE
    ${JNI_INCLUDE_DIRS}
    "${NSPARSE_CPP_DIR}"
)

target_link_libraries(nsparse_jni PRIVATE nsparse)

set_target_properties(nsparse_jni PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    OUTPUT_NAME "nsparse_jni"
    PREFIX "lib"
)

# Strip debug symbols in release builds
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    if(NOT WIN32)
        target_link_options(nsparse_jni PRIVATE -s)
    endif()
endif()
