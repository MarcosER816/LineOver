cmake_minimum_required(VERSION 3.22)
project(lineover_engine VERSION 0.0.1 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(LO_WARNINGS -Wall -Wextra -Wpedantic -Werror)

# Portable engine core: no Android, GL or JNI headers. Builds everywhere.
add_library(lo_engine STATIC
    src/engine.cpp
    src/ops_identity.cpp)
target_include_directories(lo_engine PUBLIC include)
target_compile_definitions(lo_engine PRIVATE LO_ENGINE_VERSION="${PROJECT_VERSION}")
target_compile_options(lo_engine PRIVATE ${LO_WARNINGS})

if(ANDROID)
    # liblineover_engine.so: the core plus the JNI bridge and the GLES backend,
    # loaded by dev.mark.lineover.core.engine.LoEngine.
    add_library(lineover_engine SHARED
        android/jni_bridge.cpp
        android/gl_smoke.cpp)
    target_compile_options(lineover_engine PRIVATE ${LO_WARNINGS})
    target_link_libraries(lineover_engine PRIVATE lo_engine EGL GLESv3 log)
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
            LO_GOLDEN_DIFF_DIR="${LO_GOLDEN_DIFF_DIR}")
        target_link_libraries(lo_testing PUBLIC lo_engine ZLIB::ZLIB)

        foreach(test_name sample_test golden_assert_test golden_identity_test)
            add_executable(${test_name} tests/${test_name}.cpp)
            target_compile_options(${test_name} PRIVATE ${LO_WARNINGS})
            target_link_libraries(${test_name} PRIVATE lo_testing)
            add_test(NAME ${test_name} COMMAND ${test_name})
        endforeach()
    endif()
endif()
