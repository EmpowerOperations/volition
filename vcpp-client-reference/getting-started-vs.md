## Building the cpp reference client from source with Visual Studio ##

This page describes how to build the reference cpp client application.

> You should be able to modify the cpp client reference as you see fit and follow these steps with your own code.

We will assume you have cloned this repository into `~/volition`

> Note: it is not sufficient to use the "Download Code" Button on github as this will not download the necessary submodule `microsoft/vcpkg`, which we use to pull binaries for grpc and protobuf

To build our CPP application we must:

1. pull the necessary protobuf runtime dll and grpc header code using vcpkg.
2. generate cpp volition code for the service definitions outlined in [`optimizer.proto`](../api/src/main/proto/optimizer.proto)
3. build [`Source.cpp`]


> Note: the protbuf runtime (`protobuf.dll`), the `protoc` compiler, and generated protobuf & grpc source files (`optimizer.pb.cc`, et al) **must all have the same version**. If a different version of protoc is used to generate the optimizer.cpp code than is used on the INCLUDE path, you will see version missmatch errors.
### use vcpkg to pull grpc ###

This library uses GRPC to communicate with the optimizer service. GRPC has 3 main parts:

1. client runtime --this consists mostly of protobuf.dll
2. client header files
3. a code-generation tool "protoc"

> note: GRPC itself is a plugin to the google searialization system protobuf (and its generation tool `protoc`). when running the code generation system we run a command synonymous with `protoc --plugin grpc.exe ...` rather than a specific `grpc.exe`.

We will need to build all three locally before we can build the reference client. 

this repository uses vcpkg, a visual studio native-package manager, to download grpc dependencies and build them locally.

> note: a future command _may_ need administrative permissions to operate. I suggest running this shell as administrator, but it is probably not necessary. 

From a windows terminal:

```shell
cd volition/vcpp-client-reference
.\vcpkg\vcpkg.exe --triplet=x64-windows install grpc
```

> note: we specify --triplet=x64-windows to restrict vcpkg to only download tools for x64. At time of writing some of the tools were not available for the x86 variant, and it would require more time to pull these targets.

This will cause vcpkg to download necessary tools for grpc.

We can then add the protobuf runtime and grpc header files to the global visual studio configuration with

```shell
.\vcpkg\vcpkg.exe --triplet=x64-windows integrate install
```

> Note: this command _may_ need administrator permissions. In my testing on my machines it does not, but according to several issues on vcpkg's issue tracker, there may be some visual studio configuration files that get modified in protected directories, requireing administrator permission. 

After running this command, this will cause visual studio to globally include grpc, and should mean that the line in [`Source.cpp`]

```cpp
#include <grpcpp/grpcpp.h>
```

now includes properly.

> If you do not use `vcpkg.exe integrate install`, then you must manually update your include paths in the visual studio project to contain the grpc headers in `~\volition\vcpkg\vcpkg_installed\x64-windows\include`, and you must update the compiler to include the binaries in `~\volition\vcpkg\vcpkg_installed\x64-windows\lib`. I have not been able to get visual studio build paths updated manually.
> 
> Note also that other lines, namely the ones that include optimizer.pb.cc, will not yet compile. This is because we have not yet generated them.

### generate cpp volition code ###

next we need to use the grpc generator to make the API source code out of `optimizer.proto`

run the command

```shell
./Generate-Source.ps1 
```

This should (re)create the cpp files `optimizer.grpc.pb.cc`, `optimizer.grpc.pb.h`, `optimizer.pb.cc`, `optimizer.pb.h` in the `volition\vcpp-client-reference\gen-src` folder

> Note: GRPC itself generates a version check consisting of precompiler headers and several `#error` lines. If the version of GRPC & Protobuf used to generate the code does not match the linked version it will produce a compiler error here. This error should not be ignored, as is there to prevent bizarre runtime errors. To fix this error, you need to ensure that the protobuf & grpc runtime versions match the grpc code generator version.

If you look at [`Source.cpp`], it should mean that the lines

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