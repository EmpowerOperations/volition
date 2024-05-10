cmake_minimum_required(VERSION 3.21.0)

project(volition_client)

set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(volition SHARED IMPORTED)
set_target_properties(volition PROPERTIES
    IMPORTED_LOCATION ...)

add_executable(test_c99 test/test_c99.c)
set_target_properties(test_c99 PROPERTIES POSITION_INDEPENDENT_CODE TRUE)
target_compile_features(test_c99 PRIVATE c_std_99)
target_link_libraries(test_c99 PRIVATE common_config volition)

add_executable(test_cxx98 test/test_cxx98.cpp)
set_target_properties(test_cxx98 PROPERTIES POSITION_INDEPENDENT_CODE TRUE)
target_compile_features(test_cxx98 PRIVATE cxx_std_98)
target_link_libraries(test_cxx98 PRIVATE common_config volition)

add_executable(test_cxx11 test/test_cxx11.cpp)
set_target_properties(test_cxx11 PROPERTIES POSITION_INDEPENDENT_CODE TRUE)
target_compile_features(test_cxx11 PRIVATE cxx_std_11)
target_link_libraries(test_cxx11 PRIVATE common_config volition)
