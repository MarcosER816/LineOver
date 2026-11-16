plugins {
    id("lineover.android.library")
}

// The C++17 engine. The same CMake project also builds a host (Linux) static
// library with ctest tests:
//   cmake -S core/engine-native/src/main/cpp -B build/native-host -G Ninja
//   cmake --build build/native-host && ctest --test-dir build/native-host
android {
    namespace = "dev.mark.lineover.core.enginenative"
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
}
