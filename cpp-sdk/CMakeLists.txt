cmake_minimum_required(VERSION 3.21.0)

set(CMAKE_TOOLCHAIN_FILE
    "${CMAKE_CURRENT_SOURCE_DIR}/vcpkg/scripts/buildsystems/vcpkg.cmake"
    CACHE STRING "Vcpkg toolchain file")

project(volition)

include(CheckPIESupported)
include(GNUInstallDirs)

# https://cmake.org/cmake/help/latest/guide/importing-exporting/index.html

check_pie_supported()

find_package(gRPC CONFIG REQUIRED)
find_package(Protobuf REQUIRED)

set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(common_config INTERFACE)
target_compile_definitions(common_config INTERFACE
    $<$<CXX_COMPILER_ID:Clang,GNU>:
    _FORTIFY_SOURCE=3
    _GLIBCXX_ASSERTIONS>)
target_compile_features(common_config INTERFACE cxx_std_20)
target_compile_options(common_config INTERFACE
    $<$<CXX_COMPILER_ID:Clang,GNU>:
    -O2
    -Wall -Wconversion -Wformat -Wformat=2 -Wimplicit-fallthrough
    -fno-rtti
    -fsanitize=address,undefined
    -fstack-clash-protection
    #-fstrict-flex-arrays=3
    -fstack-protector-strong>)
target_link_options(common_config INTERFACE
    $<$<CXX_COMPILER_ID:Clang,GNU>:
    -fsanitize=address,undefined
    "LINKER:-z,nodlopen,-z,noexecstack,-z,now,-z,relro"
    >)

add_library(optimizer_proto optimizer.proto)
# Include the generated header files
set_target_properties(optimizer_proto PROPERTIES POSITION_INDEPENDENT_CODE TRUE)
target_include_directories(optimizer_proto INTERFACE "${CMAKE_CURRENT_BINARY_DIR}")
target_link_libraries(optimizer_proto PUBLIC common_config gRPC::grpc++)
# Generate Protobuf files
protobuf_generate(TARGET optimizer_proto)
# Generate gRPC files
protobuf_generate(
    TARGET optimizer_proto
    LANGUAGE grpc
    PLUGIN protoc-gen-grpc=$<TARGET_FILE:gRPC::grpc_cpp_plugin>
    GENERATE_EXTENSIONS .grpc.pb.h .grpc.pb.cc)

add_library(volition SHARED
    src/volition_shim.cpp)
set_target_properties(volition PROPERTIES POSITION_INDEPENDENT_CODE TRUE)
target_include_directories(volition PUBLIC include)
target_link_libraries(volition
    PUBLIC common_config
    PRIVATE optimizer_proto)

#add_executable(test_c99 test/test_c99.c)
#set_target_properties(test_c99 PROPERTIES POSITION_INDEPENDENT_CODE TRUE)
#target_compile_features(test_c99 PRIVATE c_std_99)
#target_link_libraries(test_c99 PRIVATE common_config volition)

#add_executable(test_cxx98 test/test_cxx98.cpp)
#set_target_properties(test_cxx98 PROPERTIES POSITION_INDEPENDENT_CODE TRUE)
#target_compile_features(test_cxx98 PRIVATE cxx_std_98)
#target_link_libraries(test_cxx98 PRIVATE common_config volition)

#add_executable(test_cxx11 test/test_cxx11.cpp)
#set_target_properties(test_cxx11 PROPERTIES POSITION_INDEPENDENT_CODE TRUE)
#target_compile_features(test_cxx11 PRIVATE cxx_std_11)
#target_link_libraries(test_cxx11 PRIVATE common_config volition)
