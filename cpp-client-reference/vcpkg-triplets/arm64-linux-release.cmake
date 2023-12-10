set(VCPKG_TARGET_ARCHITECTURE arm64)
set(VCPKG_CRT_LINKAGE dynamic)
set(VCPKG_LIBRARY_LINKAGE static)

set(VCPKG_CMAKE_SYSTEM_NAME Linux)
set(VCPKG_BUILD_TYPE release)

set(VCPKG_C_FLAGS_RELEASE -fvisibility=hidden)
set(VCPKG_CXX_FLAGS_RELEASE -fvisibility=hidden)
