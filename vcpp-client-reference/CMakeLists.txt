cmake_minimum_required(VERSION 3.21.0)

set(CMAKE_TOOLCHAIN_FILE
    "${CMAKE_CURRENT_SOURCE_DIR}/vcpkg/scripts/buildsystems/vcpkg.cmake"
    CACHE STRING "Vcpkg toolchain file")

project(volition)

find_package(gRPC CONFIG REQUIRED)
find_package(Protobuf REQUIRED)

add_library(optimizer-proto optimizer.proto)
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

add_executable(vcpp-client-reference Source.cpp)
target_link_libraries(vcpp-client-reference PRIVATE optimizer-proto)
