// Tiny test harness for the ctest executables (no third-party framework).
//
//   LO_TEST(name) { LO_CHECK(1 + 1 == 2); }
//   int main() { return lo::testing::runAllTests(); }
#pragma once

#include <exception>
#include <functional>
#include <iostream>
#include <string>
#include <vector>

namespace lo::testing {

struct TestCase {
    std::string name;
    std::function<void()> body;
};

inline std::vector<TestCase>& registry() {
    static std::vector<TestCase> tests;
    return tests;
}

inline int& failureCount() {
    static int failures = 0;
    return failures;
}

struct Registrar {
    Registrar(const char* name, std::function<void()> body) { registry().push_back({name, std::move(body)}); }
};

inline int runAllTests() {
    for (const TestCase& test : registry()) {
        const int before = failureCount();
        try {
            test.body();
        } catch (const std::exception& e) {
            ++failureCount();
            std::cerr << "  exception: " << e.what() << "\n";
        }
        std::cout << (failureCount() == before ? "[ OK ] " : "[FAIL] ") << test.name << std::endl;
    }
    std::cout << registry().size() << " test(s), " << failureCount() << " failed check(s)" << std::endl;
    return failureCount() == 0 ? 0 : 1;
}

}  // namespace lo::testing

#define LO_TEST(name)                                                              \
    static void lo_test_##name();                                                  \
    static const ::lo::testing::Registrar lo_registrar_##name(#name, lo_test_##name); \
    static void lo_test_##name()

#define LO_CHECK(condition)                                                                 \
    do {                                                                                    \
        if (!(condition)) {                                                                 \
            ++::lo::testing::failureCount();                                                \
            std::cerr << "  " << __FILE__ << ":" << __LINE__ << ": check failed: " #condition "\n"; \
        }                                                                                   \
    } while (false)

// Like LO_CHECK for a GoldenResult, printing its message on failure.
#define LO_CHECK_GOLDEN(result_expression)                                        \
    do {                                                                          \
        const auto lo_result = (result_expression);                               \
        if (!lo_result) {                                                         \
            ++::lo::testing::failureCount();                                      \
            std::cerr << "  " << __FILE__ << ":" << __LINE__ << ": golden mismatch: " << lo_result.message << "\n"; \
        }                                                                         \
    } while (false)
