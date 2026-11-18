cmake_minimum_required(VERSION 3.22)
project(lineover_engine VERSION 0.7.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(LO_WARNINGS -Wall -Wextra -Wpedantic -Werror)

# zstd 1.5.7 (BSD licence), vendored sources: compresses spilled tiles.
# See third_party/zstd/README.md.
file(GLOB LO_ZSTD_SOURCES CONFIGURE_DEPENDS
    third_party/zstd/lib/common/*.c
    third_party/zstd/lib/compress/*.c
    third_party/zstd/lib/decompress/*.c)
add_library(lo_zstd STATIC ${LO_ZSTD_SOURCES})
target_include_directories(lo_zstd PUBLIC third_party/zstd/lib)
target_compile_definitions(lo_zstd PRIVATE ZSTD_DISABLE_ASM=1 ZSTD_LEGACY_SUPPORT=0 ZSTD_MULTITHREAD_SUPPORT_DEFAULT=0)
set_target_properties(lo_zstd PROPERTIES C_STANDARD 99 POSITION_INDEPENDENT_CODE ON)

# Portable engine core: no Android, GL or JNI headers. Builds everywhere.
add_library(lo_engine STATIC
    src/engine.cpp
    src/ops_identity.cpp
    src/color.cpp
    src/ops.cpp
    src/light.cpp
    src/color_ops.cpp
    src/grade_ops.cpp
    src/graph.cpp
    src/tiling.cpp
    src/tile_store.cpp)
target_include_directories(lo_engine PUBLIC include)
target_compile_definitions(lo_engine PRIVATE LO_ENGINE_VERSION="${PROJECT_VERSION}")
target_compile_options(lo_engine PRIVATE ${LO_WARNINGS})
target_link_libraries(lo_engine PRIVATE lo_zstd)

if(ANDROID)
    # liblineover_engine.so: the core plus the JNI bridge and the GLES backend,
    # loaded by dev.mark.lineover.core.engine.LoEngine.
    add_library(lineover_engine SHARED
        android/jni_bridge.cpp
        android/gl_smoke.cpp
        android/gl_util.cpp
        android/gpu_pipeline.cpp
        android/engine.cpp
        android/lo_api.cpp)
    target_compile_options(lineover_engine PRIVATE ${LO_WARNINGS})
    target_link_libraries(lineover_engine PRIVATE lo_engine EGL GLESv3 android jnigraphics log)
else()
    # Host build (Linux CI): golden-image helpers and ctest tests.
    include(CTest)
    if(BUILD_TESTING)
        find_package(ZLIB REQUIRED)

        get_filename_component(LO_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
        set(LO_GOLDEN_DIR "${LO_REPO_ROOT}/testdata/golden" CACHE PATH "Golden reference images")
        set(LO_GOLDEN_DIFF_DIR "${CMAKE_BINARY_DIR}/golden-diffs" CACHE PATH "Diff heatmaps of failed comparisons")

        add_library(lo_testing STATIC
            testing/png_io.cpp
            testing/npy_io.cpp
            testing/golden_assert.cpp)
        target_include_directories(lo_testing PUBLIC testing)
        target_compile_options(lo_testing PRIVATE ${LO_WARNINGS})
        target_compile_definitions(lo_testing PUBLIC
            LO_GOLDEN_DIR="${LO_GOLDEN_DIR}"
            LO_GOLDEN_DIFF_DIR="${LO_GOLDEN_DIFF_DIR}"
            LO_TESTDATA_DIR="${LO_REPO_ROOT}/testdata")
        target_link_libraries(lo_testing PUBLIC lo_engine ZLIB::ZLIB)

        foreach(test_name sample_test golden_assert_test golden_identity_test
                color_test ops_test graph_test tiling_test tile_store_test light_test color_ops_test grade_ops_test)
            add_executable(${test_name} tests/${test_name}.cpp)
            target_compile_options(${test_name} PRIVATE ${LO_WARNINGS})
            target_link_libraries(${test_name} PRIVATE lo_testing)
            add_test(NAME ${test_name} COMMAND ${test_name})
        endforeach()
    endif()
endif()
