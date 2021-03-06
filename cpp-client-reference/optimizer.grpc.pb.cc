// Generated by the gRPC C++ plugin.
// If you make any local change, they will be lost.
// source: optimizer.proto

#include "optimizer.pb.h"
#include "optimizer.grpc.pb.h"

#include <functional>
#include <grpcpp/impl/codegen/async_stream.h>
#include <grpcpp/impl/codegen/async_unary_call.h>
#include <grpcpp/impl/codegen/channel_interface.h>
#include <grpcpp/impl/codegen/client_unary_call.h>
#include <grpcpp/impl/codegen/client_callback.h>
#include <grpcpp/impl/codegen/message_allocator.h>
#include <grpcpp/impl/codegen/method_handler.h>
#include <grpcpp/impl/codegen/rpc_service_method.h>
#include <grpcpp/impl/codegen/server_callback.h>
#include <grpcpp/impl/codegen/server_callback_handlers.h>
#include <grpcpp/impl/codegen/server_context.h>
#include <grpcpp/impl/codegen/service_type.h>
#include <grpcpp/impl/codegen/sync_stream.h>
namespace empowerops {
namespace volition {
namespace dto {

std::unique_ptr< FederatedOptimizer::Stub> FederatedOptimizer::NewStub(const std::shared_ptr< ::grpc::ChannelInterface>& channel, const ::grpc::StubOptions& options) {
  (void)options;
  std::unique_ptr< FederatedOptimizer::Stub> stub(new FederatedOptimizer::Stub(channel));
  return stub;
}

FederatedOptimizer::Stub::Stub(const std::shared_ptr< ::grpc::ChannelInterface>& channel)
  : channel_(channel){}

FederatedOptimizer::Service::Service() {
}

FederatedOptimizer::Service::~Service() {
}


static const char* UnaryOptimizer_method_names[] = {
  "/empowerops.volition.dto.UnaryOptimizer/StartOptimization",
  "/empowerops.volition.dto.UnaryOptimizer/OfferSimulationResult",
  "/empowerops.volition.dto.UnaryOptimizer/OfferErrorResult",
  "/empowerops.volition.dto.UnaryOptimizer/OfferEvaluationStatusMessage",
  "/empowerops.volition.dto.UnaryOptimizer/StopOptimization",
  "/empowerops.volition.dto.UnaryOptimizer/RequestRunResult",
};

std::unique_ptr< UnaryOptimizer::Stub> UnaryOptimizer::NewStub(const std::shared_ptr< ::grpc::ChannelInterface>& channel, const ::grpc::StubOptions& options) {
  (void)options;
  std::unique_ptr< UnaryOptimizer::Stub> stub(new UnaryOptimizer::Stub(channel));
  return stub;
}

UnaryOptimizer::Stub::Stub(const std::shared_ptr< ::grpc::ChannelInterface>& channel)
  : channel_(channel), rpcmethod_StartOptimization_(UnaryOptimizer_method_names[0], ::grpc::internal::RpcMethod::SERVER_STREAMING, channel)
  , rpcmethod_OfferSimulationResult_(UnaryOptimizer_method_names[1], ::grpc::internal::RpcMethod::NORMAL_RPC, channel)
  , rpcmethod_OfferErrorResult_(UnaryOptimizer_method_names[2], ::grpc::internal::RpcMethod::NORMAL_RPC, channel)
  , rpcmethod_OfferEvaluationStatusMessage_(UnaryOptimizer_method_names[3], ::grpc::internal::RpcMethod::NORMAL_RPC, channel)
  , rpcmethod_StopOptimization_(UnaryOptimizer_method_names[4], ::grpc::internal::RpcMethod::NORMAL_RPC, channel)
  , rpcmethod_RequestRunResult_(UnaryOptimizer_method_names[5], ::grpc::internal::RpcMethod::NORMAL_RPC, channel)
  {}

::grpc::ClientReader< ::empowerops::volition::dto::OptimizerGeneratedQueryDTO>* UnaryOptimizer::Stub::StartOptimizationRaw(::grpc::ClientContext* context, const ::empowerops::volition::dto::StartOptimizationCommandDTO& request) {
  return ::grpc::internal::ClientReaderFactory< ::empowerops::volition::dto::OptimizerGeneratedQueryDTO>::Create(channel_.get(), rpcmethod_StartOptimization_, context, request);
}

void UnaryOptimizer::Stub::experimental_async::StartOptimization(::grpc::ClientContext* context, ::empowerops::volition::dto::StartOptimizationCommandDTO* request, ::grpc::experimental::ClientReadReactor< ::empowerops::volition::dto::OptimizerGeneratedQueryDTO>* reactor) {
  ::grpc::internal::ClientCallbackReaderFactory< ::empowerops::volition::dto::OptimizerGeneratedQueryDTO>::Create(stub_->channel_.get(), stub_->rpcmethod_StartOptimization_, context, request, reactor);
}

::grpc::ClientAsyncReader< ::empowerops::volition::dto::OptimizerGeneratedQueryDTO>* UnaryOptimizer::Stub::AsyncStartOptimizationRaw(::grpc::ClientContext* context, const ::empowerops::volition::dto::StartOptimizationCommandDTO& request, ::grpc::CompletionQueue* cq, void* tag) {
  return ::grpc::internal::ClientAsyncReaderFactory< ::empowerops::volition::dto::OptimizerGeneratedQueryDTO>::Create(channel_.get(), cq, rpcmethod_StartOptimization_, context, request, true, tag);
}

::grpc::ClientAsyncReader< ::empowerops::volition::dto::OptimizerGeneratedQueryDTO>* UnaryOptimizer::Stub::PrepareAsyncStartOptimizationRaw(::grpc::ClientContext* context, const ::empowerops::volition::dto::StartOptimizationCommandDTO& request, ::grpc::CompletionQueue* cq) {
  return ::grpc::internal::ClientAsyncReaderFactory< ::empowerops::volition::dto::OptimizerGeneratedQueryDTO>::Create(channel_.get(), cq, rpcmethod_StartOptimization_, context, request, false, nullptr);
}

::grpc::Status UnaryOptimizer::Stub::OfferSimulationResult(::grpc::ClientContext* context, const ::empowerops::volition::dto::SimulationEvaluationCompletedResponseDTO& request, ::empowerops::volition::dto::SimulationEvaluationResultConfirmDTO* response) {
  return ::grpc::internal::BlockingUnaryCall(channel_.get(), rpcmethod_OfferSimulationResult_, context, request, response);
}

void UnaryOptimizer::Stub::experimental_async::OfferSimulationResult(::grpc::ClientContext* context, const ::empowerops::volition::dto::SimulationEvaluationCompletedResponseDTO* request, ::empowerops::volition::dto::SimulationEvaluationResultConfirmDTO* response, std::function<void(::grpc::Status)> f) {
  ::grpc::internal::CallbackUnaryCall(stub_->channel_.get(), stub_->rpcmethod_OfferSimulationResult_, context, request, response, std::move(f));
}

void UnaryOptimizer::Stub::experimental_async::OfferSimulationResult(::grpc::ClientContext* context, const ::empowerops::volition::dto::SimulationEvaluationCompletedResponseDTO* request, ::empowerops::volition::dto::SimulationEvaluationResultConfirmDTO* response, ::grpc::experimental::ClientUnaryReactor* reactor) {
  ::grpc::internal::ClientCallbackUnaryFactory::Create(stub_->channel_.get(), stub_->rpcmethod_OfferSimulationResult_, context, request, response, reactor);
}

::grpc::ClientAsyncResponseReader< ::empowerops::volition::dto::SimulationEvaluationResultConfirmDTO>* UnaryOptimizer::Stub::PrepareAsyncOfferSimulationResultRaw(::grpc::ClientContext* context, const ::empowerops::volition::dto::SimulationEvaluationCompletedResponseDTO& request, ::grpc::CompletionQueue* cq) {
  return ::grpc::internal::ClientAsyncResponseReaderFactory< ::empowerops::volition::dto::SimulationEvaluationResultConfirmDTO>::Create(channel_.get(), cq, rpcmethod_OfferSimulationResult_, context, request, false);
}

::grpc::ClientAsyncResponseReader< ::empowerops::volition::dto::SimulationEvaluationResultConfirmDTO>* UnaryOptimizer::Stub::AsyncOfferSimulationResultRaw(::grpc::ClientContext* context, const ::empowerops::volition::dto::SimulationEvaluationCompletedResponseDTO& request, ::grpc::CompletionQueue* cq) {
  auto* result =
    this->PrepareAsyncOfferSimulationResultRaw(context, request, cq);
  result->StartCall();
  return result;
}

::grpc::Status UnaryOptimizer::Stub::OfferErrorResult(::grpc::ClientContext* context, const ::empowerops::volition::dto::SimulationEvaluationErrorResponseDTO& request, ::empowerops::volition::dto::SimulationEvaluationErrorConfirmDTO* response) {
  return ::grpc::internal::BlockingUnaryCall(channel_.get(), rpcmethod_OfferErrorResult_, context, request, response);
}

void UnaryOptimizer::Stub::experimental_async::OfferErrorResult(::grpc::ClientContext* context, const ::empowerops::volition::dto::SimulationEvaluationErrorResponseDTO* request, ::empowerops::volition::dto::SimulationEvaluationErrorConfirmDTO* response, std::function<void(::grpc::Status)> f) {
  ::grpc::internal::CallbackUnaryCall(stub_->channel_.get(), stub_->rpcmethod_OfferErrorResult_, context, request, response, std::move(f));
}

void UnaryOptimizer::Stub::experimental_async::OfferErrorResult(::grpc::ClientContext* context, const ::empowerops::volition::dto::SimulationEvaluationErrorResponseDTO* request, ::empowerops::volition::dto::SimulationEvaluationErrorConfirmDTO* response, ::grpc::experimental::ClientUnaryReactor* reactor) {
  ::grpc::internal::ClientCallbackUnaryFactory::Create(stub_->channel_.get(), stub_->rpcmethod_OfferErrorResult_, context, request, response, reactor);
}

::grpc::ClientAsyncResponseReader< ::empowerops::volition::dto::SimulationEvaluationErrorConfirmDTO>* UnaryOptimizer::Stub::PrepareAsyncOfferErrorResultRaw(::grpc::ClientContext* context, const ::empowerops::volition::dto::SimulationEvaluationErrorResponseDTO& request, ::grpc::CompletionQueue* cq) {
  return ::grpc::internal::ClientAsyncResponseReaderFactory< ::empowerops::volition::dto::SimulationEvaluationErrorConfirmDTO>::Create(channel_.get(), cq, rpcmethod_OfferErrorResult_, context, request, false);
}

::grpc::ClientAsyncResponseReader< ::empowerops::volition::dto::SimulationEvaluationErrorConfirmDTO>* UnaryOptimizer::Stub::AsyncOfferErrorResultRaw(::grpc::ClientContext* context, const ::empowerops::volition::dto::SimulationEvaluationErrorResponseDTO& request, ::grpc::CompletionQueue* cq) {
  auto* result =
    this->PrepareAsyncOfferErrorResultRaw(context, request, cq);
  result->StartCall();
  return result;
}

::grpc::Status UnaryOptimizer::Stub::OfferEvaluationStatusMessage(::grpc::ClientContext* context, const ::empowerops::volition::dto::StatusMessageCommandDTO& request, ::empowerops::volition::dto::StatusMessageConfirmDTO* response) {
  return ::grpc::internal::BlockingUnaryCall(channel_.get(), rpcmethod_OfferEvaluationStatusMessage_, context, request, response);
}

void UnaryOptimizer::Stub::experimental_async::OfferEvaluationStatusMessage(::grpc::ClientContext* context, const ::empowerops::volition::dto::StatusMessageCommandDTO* request, ::empowerops::volition::dto::StatusMessageConfirmDTO* response, std::function<void(::grpc::Status)> f) {
  ::grpc::internal::CallbackUnaryCall(stub_->channel_.get(), stub_->rpcmethod_OfferEvaluationStatusMessage_, context, request, response, std::move(f));
}

void UnaryOptimizer::Stub::experimental_async::OfferEvaluationStatusMessage(::grpc::ClientContext* context, const ::empowerops::volition::dto::StatusMessageCommandDTO* request, ::empowerops::volition::dto::StatusMessageConfirmDTO* response, ::grpc::experimental::ClientUnaryReactor* reactor) {
  ::grpc::internal::ClientCallbackUnaryFactory::Create(stub_->channel_.get(), stub_->rpcmethod_OfferEvaluationStatusMessage_, context, request, response, reactor);
}

::grpc::ClientAsyncResponseReader< ::empowerops::volition::dto::StatusMessageConfirmDTO>* UnaryOptimizer::Stub::PrepareAsyncOfferEvaluationStatusMessageRaw(::grpc::ClientContext* context, const ::empowerops::volition::dto::StatusMessageCommandDTO& request, ::grpc::CompletionQueue* cq) {
  return ::grpc::internal::ClientAsyncResponseReaderFactory< ::empowerops::volition::dto::StatusMessageConfirmDTO>::Create(channel_.get(), cq, rpcmethod_OfferEvaluationStatusMessage_, context, request, false);
}

::grpc::ClientAsyncResponseReader< ::empowerops::volition::dto::StatusMessageConfirmDTO>* UnaryOptimizer::Stub::AsyncOfferEvaluationStatusMessageRaw(::grpc::ClientContext* context, const ::empowerops::volition::dto::StatusMessageCommandDTO& request, ::grpc::CompletionQueue* cq) {
  auto* result =
    this->PrepareAsyncOfferEvaluationStatusMessageRaw(context, request, cq);
  result->StartCall();
  return result;
}

::grpc::Status UnaryOptimizer::Stub::StopOptimization(::grpc::ClientContext* context, const ::empowerops::volition::dto::StopOptimizationCommandDTO& request, ::empowerops::volition::dto::StopOptimizationConfirmDTO* response) {
  return ::grpc::internal::BlockingUnaryCall(channel_.get(), rpcmethod_StopOptimization_, context, request, response);
}

void UnaryOptimizer::Stub::experimental_async::StopOptimization(::grpc::ClientContext* context, const ::empowerops::volition::dto::StopOptimizationCommandDTO* request, ::empowerops::volition::dto::StopOptimizationConfirmDTO* response, std::function<void(::grpc::Status)> f) {
  ::grpc::internal::CallbackUnaryCall(stub_->channel_.get(), stub_->rpcmethod_StopOptimization_, context, request, response, std::move(f));
}

void UnaryOptimizer::Stub::experimental_async::StopOptimization(::grpc::ClientContext* context, const ::empowerops::volition::dto::StopOptimizationCommandDTO* request, ::empowerops::volition::dto::StopOptimizationConfirmDTO* response, ::grpc::experimental::ClientUnaryReactor* reactor) {
  ::grpc::internal::ClientCallbackUnaryFactory::Create(stub_->channel_.get(), stub_->rpcmethod_StopOptimization_, context, request, response, reactor);
}

::grpc::ClientAsyncResponseReader< ::empowerops::volition::dto::StopOptimizationConfirmDTO>* UnaryOptimizer::Stub::PrepareAsyncStopOptimizationRaw(::grpc::ClientContext* context, const ::empowerops::volition::dto::StopOptimizationCommandDTO& request, ::grpc::CompletionQueue* cq) {
  return ::grpc::internal::ClientAsyncResponseReaderFactory< ::empowerops::volition::dto::StopOptimizationConfirmDTO>::Create(channel_.get(), cq, rpcmethod_StopOptimization_, context, request, false);
}

::grpc::ClientAsyncResponseReader< ::empowerops::volition::dto::StopOptimizationConfirmDTO>* UnaryOptimizer::Stub::AsyncStopOptimizationRaw(::grpc::ClientContext* context, const ::empowerops::volition::dto::StopOptimizationCommandDTO& request, ::grpc::CompletionQueue* cq) {
  auto* result =
    this->PrepareAsyncStopOptimizationRaw(context, request, cq);
  result->StartCall();
  return result;
}

::grpc::Status UnaryOptimizer::Stub::RequestRunResult(::grpc::ClientContext* context, const ::empowerops::volition::dto::OptimizationResultsQueryDTO& request, ::empowerops::volition::dto::OptimizationResultsResponseDTO* response) {
  return ::grpc::internal::BlockingUnaryCall(channel_.get(), rpcmethod_RequestRunResult_, context, request, response);
}

void UnaryOptimizer::Stub::experimental_async::RequestRunResult(::grpc::ClientContext* context, const ::empowerops::volition::dto::OptimizationResultsQueryDTO* request, ::empowerops::volition::dto::OptimizationResultsResponseDTO* response, std::function<void(::grpc::Status)> f) {
  ::grpc::internal::CallbackUnaryCall(stub_->channel_.get(), stub_->rpcmethod_RequestRunResult_, context, request, response, std::move(f));
}

void UnaryOptimizer::Stub::experimental_async::RequestRunResult(::grpc::ClientContext* context, const ::empowerops::volition::dto::OptimizationResultsQueryDTO* request, ::empowerops::volition::dto::OptimizationResultsResponseDTO* response, ::grpc::experimental::ClientUnaryReactor* reactor) {
  ::grpc::internal::ClientCallbackUnaryFactory::Create(stub_->channel_.get(), stub_->rpcmethod_RequestRunResult_, context, request, response, reactor);
}

::grpc::ClientAsyncResponseReader< ::empowerops::volition::dto::OptimizationResultsResponseDTO>* UnaryOptimizer::Stub::PrepareAsyncRequestRunResultRaw(::grpc::ClientContext* context, const ::empowerops::volition::dto::OptimizationResultsQueryDTO& request, ::grpc::CompletionQueue* cq) {
  return ::grpc::internal::ClientAsyncResponseReaderFactory< ::empowerops::volition::dto::OptimizationResultsResponseDTO>::Create(channel_.get(), cq, rpcmethod_RequestRunResult_, context, request, false);
}

::grpc::ClientAsyncResponseReader< ::empowerops::volition::dto::OptimizationResultsResponseDTO>* UnaryOptimizer::Stub::AsyncRequestRunResultRaw(::grpc::ClientContext* context, const ::empowerops::volition::dto::OptimizationResultsQueryDTO& request, ::grpc::CompletionQueue* cq) {
  auto* result =
    this->PrepareAsyncRequestRunResultRaw(context, request, cq);
  result->StartCall();
  return result;
}

UnaryOptimizer::Service::Service() {
  AddMethod(new ::grpc::internal::RpcServiceMethod(
      UnaryOptimizer_method_names[0],
      ::grpc::internal::RpcMethod::SERVER_STREAMING,
      new ::grpc::internal::ServerStreamingHandler< UnaryOptimizer::Service, ::empowerops::volition::dto::StartOptimizationCommandDTO, ::empowerops::volition::dto::OptimizerGeneratedQueryDTO>(
          [](UnaryOptimizer::Service* service,
             ::grpc::ServerContext* ctx,
             const ::empowerops::volition::dto::StartOptimizationCommandDTO* req,
             ::grpc::ServerWriter<::empowerops::volition::dto::OptimizerGeneratedQueryDTO>* writer) {
               return service->StartOptimization(ctx, req, writer);
             }, this)));
  AddMethod(new ::grpc::internal::RpcServiceMethod(
      UnaryOptimizer_method_names[1],
      ::grpc::internal::RpcMethod::NORMAL_RPC,
      new ::grpc::internal::RpcMethodHandler< UnaryOptimizer::Service, ::empowerops::volition::dto::SimulationEvaluationCompletedResponseDTO, ::empowerops::volition::dto::SimulationEvaluationResultConfirmDTO>(
          [](UnaryOptimizer::Service* service,
             ::grpc::ServerContext* ctx,
             const ::empowerops::volition::dto::SimulationEvaluationCompletedResponseDTO* req,
             ::empowerops::volition::dto::SimulationEvaluationResultConfirmDTO* resp) {
               return service->OfferSimulationResult(ctx, req, resp);
             }, this)));
  AddMethod(new ::grpc::internal::RpcServiceMethod(
      UnaryOptimizer_method_names[2],
      ::grpc::internal::RpcMethod::NORMAL_RPC,
      new ::grpc::internal::RpcMethodHandler< UnaryOptimizer::Service, ::empowerops::volition::dto::SimulationEvaluationErrorResponseDTO, ::empowerops::volition::dto::SimulationEvaluationErrorConfirmDTO>(
          [](UnaryOptimizer::Service* service,
             ::grpc::ServerContext* ctx,
             const ::empowerops::volition::dto::SimulationEvaluationErrorResponseDTO* req,
             ::empowerops::volition::dto::SimulationEvaluationErrorConfirmDTO* resp) {
               return service->OfferErrorResult(ctx, req, resp);
             }, this)));
  AddMethod(new ::grpc::internal::RpcServiceMethod(
      UnaryOptimizer_method_names[3],
      ::grpc::internal::RpcMethod::NORMAL_RPC,
      new ::grpc::internal::RpcMethodHandler< UnaryOptimizer::Service, ::empowerops::volition::dto::StatusMessageCommandDTO, ::empowerops::volition::dto::StatusMessageConfirmDTO>(
          [](UnaryOptimizer::Service* service,
             ::grpc::ServerContext* ctx,
             const ::empowerops::volition::dto::StatusMessageCommandDTO* req,
             ::empowerops::volition::dto::StatusMessageConfirmDTO* resp) {
               return service->OfferEvaluationStatusMessage(ctx, req, resp);
             }, this)));
  AddMethod(new ::grpc::internal::RpcServiceMethod(
      UnaryOptimizer_method_names[4],
      ::grpc::internal::RpcMethod::NORMAL_RPC,
      new ::grpc::internal::RpcMethodHandler< UnaryOptimizer::Service, ::empowerops::volition::dto::StopOptimizationCommandDTO, ::empowerops::volition::dto::StopOptimizationConfirmDTO>(
          [](UnaryOptimizer::Service* service,
             ::grpc::ServerContext* ctx,
             const ::empowerops::volition::dto::StopOptimizationCommandDTO* req,
             ::empowerops::volition::dto::StopOptimizationConfirmDTO* resp) {
               return service->StopOptimization(ctx, req, resp);
             }, this)));
  AddMethod(new ::grpc::internal::RpcServiceMethod(
      UnaryOptimizer_method_names[5],
      ::grpc::internal::RpcMethod::NORMAL_RPC,
      new ::grpc::internal::RpcMethodHandler< UnaryOptimizer::Service, ::empowerops::volition::dto::OptimizationResultsQueryDTO, ::empowerops::volition::dto::OptimizationResultsResponseDTO>(
          [](UnaryOptimizer::Service* service,
             ::grpc::ServerContext* ctx,
             const ::empowerops::volition::dto::OptimizationResultsQueryDTO* req,
             ::empowerops::volition::dto::OptimizationResultsResponseDTO* resp) {
               return service->RequestRunResult(ctx, req, resp);
             }, this)));
}

UnaryOptimizer::Service::~Service() {
}

::grpc::Status UnaryOptimizer::Service::StartOptimization(::grpc::ServerContext* context, const ::empowerops::volition::dto::StartOptimizationCommandDTO* request, ::grpc::ServerWriter< ::empowerops::volition::dto::OptimizerGeneratedQueryDTO>* writer) {
  (void) context;
  (void) request;
  (void) writer;
  return ::grpc::Status(::grpc::StatusCode::UNIMPLEMENTED, "");
}

::grpc::Status UnaryOptimizer::Service::OfferSimulationResult(::grpc::ServerContext* context, const ::empowerops::volition::dto::SimulationEvaluationCompletedResponseDTO* request, ::empowerops::volition::dto::SimulationEvaluationResultConfirmDTO* response) {
  (void) context;
  (void) request;
  (void) response;
  return ::grpc::Status(::grpc::StatusCode::UNIMPLEMENTED, "");
}

::grpc::Status UnaryOptimizer::Service::OfferErrorResult(::grpc::ServerContext* context, const ::empowerops::volition::dto::SimulationEvaluationErrorResponseDTO* request, ::empowerops::volition::dto::SimulationEvaluationErrorConfirmDTO* response) {
  (void) context;
  (void) request;
  (void) response;
  return ::grpc::Status(::grpc::StatusCode::UNIMPLEMENTED, "");
}

::grpc::Status UnaryOptimizer::Service::OfferEvaluationStatusMessage(::grpc::ServerContext* context, const ::empowerops::volition::dto::StatusMessageCommandDTO* request, ::empowerops::volition::dto::StatusMessageConfirmDTO* response) {
  (void) context;
  (void) request;
  (void) response;
  return ::grpc::Status(::grpc::StatusCode::UNIMPLEMENTED, "");
}

::grpc::Status UnaryOptimizer::Service::StopOptimization(::grpc::ServerContext* context, const ::empowerops::volition::dto::StopOptimizationCommandDTO* request, ::empowerops::volition::dto::StopOptimizationConfirmDTO* response) {
  (void) context;
  (void) request;
  (void) response;
  return ::grpc::Status(::grpc::StatusCode::UNIMPLEMENTED, "");
}

::grpc::Status UnaryOptimizer::Service::RequestRunResult(::grpc::ServerContext* context, const ::empowerops::volition::dto::OptimizationResultsQueryDTO* request, ::empowerops::volition::dto::OptimizationResultsResponseDTO* response) {
  (void) context;
  (void) request;
  (void) response;
  return ::grpc::Status(::grpc::StatusCode::UNIMPLEMENTED, "");
}


}  // namespace empowerops
}  // namespace volition
}  // namespace dto

