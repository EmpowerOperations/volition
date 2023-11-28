# C++ Volition API

[![C++](https://github.com/EmpowerOperations/volition/actions/workflows/cpp.yml/badge.svg)](https://github.com/EmpowerOperations/volition/actions/workflows/cpp.yml)

This is a simple CMake project to help you get started with building the Volition Client API for your C++ Application. This CMake project will use vcpkg to pull the necessary GRPC dependencies, use `protoc` with `optimizer.proto` to generate the necessary client-side code, and use the `Source.cpp` C++ reference client application to build a stub CPP application that demonstrates an Optimization with the Volition C++ API.

## Dependencies

This project includes Vcpkg as a Git submodule to download dependencies such as gRPC. 
> Note: it is not sufficient to use the "Download Code" Button on github as this will not download the necessary submodule `microsoft/vcpkg`.
> if you used git to clone this repository, you can initialize the vcpkg repository with
> ```
> cd .../cpp-client-reference/vcpkg
> git submodule init
> git submodule update

## Building on Windows  

Pre-requisites:
- A recent version of Visual Studio such as 2019 or 2022 with C++ tooling installed.

### Building in Visual Studio

Recent versions of Visual C++ natively support CMake projects. Open this directory, and Visual Studio should recognize this as a CMake project and configure accordingly.

> Note you may also use CMake to generate visual studio project files by calling cmake with the appropriate generator:
> ```
> cmake -G "Visual Studio 17 2022"
> ```

#### Configure the CMake project

Next, in Visual Studio, select the desired build mode or add one such as `x64 MSVC Debug` or `x64 MSVC Release`. Next, select `Project > Configure volition`. Visual Studio will invoke CMake to pull the project dependencies using Vcpkg and build them. 

> Behind the scenes, CMake bootstraps Vcpkg and runs `vcpkg install` for the detected triplet `x64-windows`. This has the same effect as if the following command were entered manually:
> ```
> .\vcpkg\vcpkg.exe --triplet=x64-windows install
> ```
>
> Note that we use triplet `x64-windows` to restrict Vcpkg to only download packages for x64. At the time of writing some of the tools were not available for the x86 variant, and it would require more time to pull these targets.

#### Building the project

Click on `Build > Build All` to build the reference client executable. Visual Studio will use CMake to carry out the following build steps:

1. Invoke the gRPC generator to generate the API source code from the `optimizer.proto` definition.
> This should create the C++ files `optimizer.grpc.pb.cc`, `optimizer.grpc.pb.h`, `optimizer.pb.cc`, `optimizer.pb.h` in the build output folder.
1. Build the API library and link it against the gRPC library.
1. Build the reference client executable and link it against the API library.

> Note: gRPC itself generates a version check consisting of precompiler headers and several `#error` lines. If the version of GRPC & Protobuf used to generate the code does not match the linked version it will produce a compiler error here. This error should not be ignored, as is there to prevent bizarre runtime errors. To fix this error, you need to ensure that the Protobuf and gRPC runtime versions match the gRPC code generator version.

#### Running the reference client

From here you should be able to run the cpp-client-reference project. In Visual Studio you should be able to right-click `cpp-client-reference` project and select `Set as Startup Project` and then simply hit the Run Button in Visual Studio.

> By default, the reference client uses port `27016`. If you are not running `oasis.cli.exe --volition 27016` or the reference optimizer on the specified port, then nothing will happen and the reference client will quit shortly.

With your optimization service running, you should see the reference client run a single iteration of an optimization loop.

### Building from the CLI using CMake and MSVC

This project can also be built from the command line on Windows. First, launch `Developer Powershell for VS2022` (or VS2019), and then run the following:
```
cmake -S . -B out --preset x64-windows-msvc-release
cmake --build out --config Release
```
> Note:
> - Substitute the preset above as appropriate.
> - On Windows, CMake defaults to a Visual Studio multi-config generator. So `--config` (CMake build type) needs to be specified at the build step above.

## Building on Linux

Pre-requisites:
- GCC (`g++`) or Clang

Run the following in the current directory:
```
cmake -S . -B out --preset x64-linux-gcc-release
cmake --build out
```

> Note:
> - Clang presets are also available. Run `cmake --list-presets` to see all the available presets on the current platform.
> - By default, CMake generates `Unix Makefiles` on Linux. To use `Ninja`, pass `-G Ninja` at the CMake configuration step.   

## Next steps

From here you should be able to modify `Source.cpp` to more closely mimmick your own software's behaviour. 
