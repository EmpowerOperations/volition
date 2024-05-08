# Getting Started with the Volition Optimization API #

The Volition Optimization API is a service API that allows applications to connect to an optimizer for optimization services. It is implemented as a service (instead of as a library file).

To use this API, you will need some familiarity with:

- client-server APIs; the Volition API is a service API, not a library (dll) API.
- one of: C++, C#, Java, (coming soon: python)
- one of: gradle, cmake, msbuild (Visual studio's Build system)
- code generation

The general flow this guide will be following is:

1. downloading existing public binary releases of a reference optimizer and a reference client.
2. checking-out the necessary sample client code
3. modifying the sample code such that it more accurately resembles your use case

> Note, in none of these steps do we need a copy of OASIS. Both the Reference Optimizer and OASIS implement the volition service API, and are thus interchangeable.

---

## Running the binary releases ##

You should start by downloading the most recent reference-client and reference-optimizer-server on the [Releases](/releases/latest).

This will verify that we can start, run, and connect to a Volition Optimization Service running on your local machine.

For our purposes we will use the 

- `csharp-EmpowerOps.Volition.RefClient-X.X.X.zip` reference client  
- `kotlin-optimizer-reference-X.X.X.zip` reference optimizer server
  > At time of writing, the reference-optimizer-server was written in Java and may require you to install the [Java Runtime](https://www.oracle.com/java/technologies/downloads/#java17)

> there is a cpp reference client as well, but it is a much simpler command line utility. For the purposes of verifying that we can run & connect to a Volition Optimization Service, even if we are working in a c++ environment, we do not need the cpp-reference-client yet.

> You can use OASIS 2022 instead of the reference optimizer service by running `oasis.cli.exe --volition 5550`

From here, extract the two zip files into a convenient folder. Open a terminal and run `ref-opt.exe`, then run `EmpowerOps.Volition.RefClient.exe`.

Change the port to `5550`, click **Inputs: Add** once and click **Outputs: Add** once. Then click **Start Optimization**

You should see something similar to 

![volition references running side by side](/web/volition-ref-server-by-volition-ref-client.png)

### Troubleshooting ###

**Clicking "Start Optimization" causes nothing to happen in the command-line window; and the reference-client reports "Error Invoke StartOptimization"**

This implies the reference client could not connect to the optimzation server. Check to make sure they are both running on the same ports; check that at the top of the reference client you see "Port: 5550", and that the first thing reported by the optimization server is `Volition Server running on port 5550`

---

## Building & Running the C++ client from source ##

To integrate your application with the volition server we saw running, you must:

1. checkout this repository with `git clone https://github.com/EmpowerOperations/volition`
   > Note: you must use `git clone` instead of the "download code" button on github. This is because the native client relies on the git submodule vcpkg, which isn't included when you download code.

2. download the necessary artifacts (namely protobuf & GRPC)
   > this is done by scripts in the below guides

3. build the reference client using your tooling

The following guides will walk you through that process

- [Getting started with C++](cpp-client-reference/README.md)
- [Getting started with dotnet](csharp-client-reference/getting-started-cs.md) 

Once finished, you will be able to run the reference client with your local toolchain.

