cmake_minimum_required(VERSION 3.21.0)

set(CMAKE_TOOLCHAIN_FILE
    "${CMAKE_CURRENT_SOURCE_DIR}/vcpkg/scripts/buildsystems/vcpkg.cmake"
    CACHE STRING "Vcpkg toolchain file")

project(volition)

find_package(gRPC CONFIG REQUIRED)
find_package(Protobuf REQUIRED)

set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(optimizer-proto optimizer.proto)
set_target_properties(optimizer-proto PROPERTIES POSITION_INDEPENDENT_CODE TRUE)
# Include the generated header files
target_include_directories(optimizer-proto INTERFACE "${CMAKE_CURRENT_BINARY_DIR}")
target_link_libraries(optimizer-proto PUBLIC gRPC::grpc++)
# Generate Protobuf files
protobuf_generate(TARGET optimizer-proto)
# Generate gRPC files
protobuf_generate(
    TARGET optimizer-proto
    LANGUAGE grpc
    PLUGIN protoc-gen-grpc=$<TARGET_FILE:gRPC::grpc_cpp_plugin>
    GENERATE_EXTENSIONS .grpc.pb.h .grpc.pb.cc)

add_executable(cpp-client-reference Source.cpp)
target_link_libraries(cpp-client-reference PRIVATE optimizer-proto)

add_library(volition SHARED
    src/volition_shim.cpp)
set_target_properties(volition PROPERTIES
    CXX_EXTENSIONS OFF
    CXX_STANDARD 20)
target_include_directories(volition PUBLIC include)
target_link_libraries(volition PRIVATE optimizer-proto)

add_executable(test_c99 test/test_c99.c)
set_target_properties(test_c99 PROPERTIES
    C_EXTENSIONS OFF
    C_STANDARD 99)
target_link_libraries(test_c99 PRIVATE volition)

add_executable(test_cxx98 test/test_cxx98.cpp)
set_target_properties(test_cxx98 PROPERTIES
    CXX_EXTENSIONS OFF
    CXX_STANDARD 98)
target_link_libraries(test_cxx98 PRIVATE volition)

add_executable(test_cxx11 test/test_cxx11.cpp)
set_target_properties(test_cxx11 PROPERTIES
    CXX_EXTENSIONS OFF
    CXX_STANDARD 11)
target_link_libraries(test_cxx11 PRIVATE volition)
