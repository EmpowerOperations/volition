<#
.SYNOPSIS TODO

.DESCRIPTION
TODO

.PARAMETER TODO

.REMARKS
TODO
#>

Param(
    [string]
    [ValidateScript({$(Get-Item $_) -ne $null})]
    $ProtoInputFilesDir = "$(pwd)/api/src/main/proto",
    
    [string]
    $TargetProtoFile = "optimizer.proto"
)
$ErrorActionPreference = "Stop"


$ProjectDir = "$(pwd)"
$Triplet = "x64-windows"

$VcpkgDir = "$ProjectDir/vcpkg"
$Protoc = Get-Item "$VcpkgDir/installed/$Triplet/tools/protobuf/protoc.exe"
$GrpcProtocPlugin = Get-Item "$VcpkgDir/installed/$Triplet/tools/grpc/grpc_cpp_plugin.exe"
$ProtoInputFilesDir = "$ProjectDir/../api/src/main/proto"
$ProtoGeneratedSourceDir = "$ProjectDir/gen-src"
$TargetProtoFile = "optimizer.proto"

#protoc -I ../../protos --grpc_out=. --plugin=protoc-gen-grpc=`which grpc_cpp_plugin` ../../protos/route_guide.proto
& $Protoc `
    "-I" "$ProtoInputFilesDir" `
    "--grpc_out=$ProtoGeneratedSourceDir" `
    "--plugin=protoc-gen-grpc=$GrpcProtocPlugin" `
    $TargetProtoFile
Write-Host "generated grpc service endpoint definition in $ProtoGeneratedSourceDir"

#protoc -I ../../protos --cpp_out=. ../../protos/route_guide.proto
& $Protoc `
    "-I" "$ProtoInputFilesDir" `
    "--cpp_out=$ProtoGeneratedSourceDir" `
    $TargetProtoFile
Write-Host "generated grpc message definitions in $ProtoGeneratedSourceDir"
