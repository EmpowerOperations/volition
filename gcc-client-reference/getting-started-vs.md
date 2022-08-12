## Building the cpp reference client from source with Visual Studio ##

This page describes how to build the reference cpp client application.

> You should be able to modify the cpp client reference as you see fit and follow these steps with your own code.

We will assume you have cloned this repository into `~/volition`

> Note: it is not sufficient to use the "Download Code" Button on github as this will not download the necessary submodule `microsoft/vcpkg`, which we use to pull binaries for grpc and protobuf

To build our CPP application we must:

1. pull the necessary protobuf runtime dll and grpc header code using vcpkg.
2. generate cpp volition code for the service definitions outlined in [`optimizer.proto`](api/src/main/proto/optimizer.proto)
3. build [`Source.cpp`](cpp-client-reference/source.cpp)


> Note: the protbuf runtime (`protobuf.dll`), the `protoc` compiler, and generated protobuf & grpc source files (`optimizer.pb.cc`, et al) **must all have the same version**. If a different version of protoc is used to generate the optimizer.cpp code than is used on the INCLUDE path, you will see version missmatch errors.
### use vcpkg to pull grpc ###

This library uses GRPC to communicate with the optimizer service. GRPC has 3 main parts:

1. client runtime --this consists mostly of protobuf.dll
2. client header files
3. a code-generation tool.

> note: GRPC itself is a plugin to the google searialization system protobuf (and its generation tool `protoc`). when running the code generation system we run a command synonymous with `protoc --plugin grpc.exe ...` rather than a specific `grpc.exe`.

We will need to build all three locally before we can build the reference client. 

this repository uses vcpkg, a visual studio native-package manager, to download grpc dependencies and build them locally.

From a windows terminal:

```shell
cd ~/volition
./gradlew.bat vcpkgInstall
```

This instructs gradle, a build tool, to use vcpkg to download grpc and build it with your local copy of visual studio. Once finished, the created header and binary files will be in `~/volition/vcpkg_installed/`

We can then add the protobuf runtime and grpc header files to the global visual studio configuration with

```shell
./gradlew.bat vcpkgIntegrate
```

After running this command, this will cause visual studio to globally include grpc, and should mean that the line in [`Source.cpp`](cpp-client-reference/Source.cpp)

```cpp
#include <grpcpp/grpcpp.h>
```

now includes properly.

> If you do not use `./gradlew.bat vcpkgIntegrate`, then you must manually update your include paths in the visual studio project to contain the grpc headers in `~\volition\vcpkg\vcpkg_installed\x64-windows\include`, and you must update the compiler to include the binaries in `~\volition\vcpkg\vcpkg_installed\x64-windows\lib`
> 
> Note also that other lines, namely the ones that include optimizer.pb.cc, will not yet compile. This is because we have not yet generated them.

### generate cpp volition code ###

next we need to use the grpc generator to make the API source code out of `optimizer.proto`

run the command

```shell
./gradlew.bat generateProto
```

This should create code for all languages, including cpp, under the folders `volition\api\build\generated\source\proto\main\cpp` and `volition\api\build\generated\source\proto\main\grpc_cpp`

If you look at [`Source.cpp`](cpp-client-reference/Source.cpp), it should mean that the lines

```cpp
#include "../api/build/generated/source/proto/main/cpp/optimizer.pb.h"
#include "../api/build/generated/source/proto/main/grpc_cpp/optimizer.grpc.pb.h"
```

now includes properly

### Build and Run with Visual Studio ###

From here you should be able to build & run the cpp-client-reference project. In visual studio you should be able to right-click `cpp-client-reference` project and select `Set as Startup Project` and then simply hit the Run Button in Visual studio.

> Note, if you are not running `oasis.cli.exe --Volition 5550`, or the reference optimizer on that port then nothing will happen.

With your optimization service running, you should see the reference client run a single iteration of an optimization loop.



### Next steps ###

From here you should be able to modify Source.cpp to more closely mimmick your own softwares behaviour. 