## Building the csharp client from source ##

To build the cpp reference client you should only need to open the visual studio project and build it.

It requires the dotnet framework 4.6, and has a runtime dependency on the GRPC nuget package (and its transitive dependencies, namely protobuf).

The `volition.sln` projects includes the csharp reference client and should be runnable if set as the default startup project.

TODO