package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.*
import io.grpc.stub.StreamObserver

class OptimizerEndpoint(private val apiService: IApiService) : OptimizerGrpc.OptimizerImplBase() {

    override fun changeNodeName(
            request: NodeNameChangeCommandDTO,
            responseObserver: StreamObserver<NodeNameChangeResponseDTO>
    ) = responseObserver.consume {
        apiService.changeNodeName(request)
    }

    override fun autoConfigure(
            request: NodeStatusCommandOrResponseDTO,
            responseObserver: StreamObserver<NodeChangeConfirmDTO>
    ) = responseObserver.consume {
        apiService.autoConfigure(request)
    }

    override fun updateConfiguration(
            request: ConfigurationCommandDTO,
            responseObserver: StreamObserver<ConfigurationResponseDTO>
    ) = responseObserver.consume {
        apiService.updateConfiguration(request)
    }

    override fun requestRunResult(
            request: ResultRequestDTO,
            responseObserver: StreamObserver<ResultResponseDTO>
    ) = responseObserver.consume {
        apiService.resultRequest(request)
    }

    override fun registerRequest(
            request: RequestRegistrationCommandDTO,
            responseObserver: StreamObserver<RequestQueryDTO>
    ) {
        apiService.register(request, responseObserver)
    }

    override fun startOptimization(
            request: StartOptimizationCommandDTO,
            responseObserver: StreamObserver<StartOptimizationResponseDTO>
    ) = responseObserver.consumeThen(apiService.issueStartOptimization(request)) { response ->
        if (response.responseCase == StartOptimizationResponseDTO.ResponseCase.RUNID) apiService.startAsync()
    }

    override fun stopOptimization(
            request: StopOptimizationCommandDTO,
            responseObserver: StreamObserver<StopOptimizationResponseDTO>
    ) = responseObserver.consumeThen(apiService.issueStopOptimization(request)) { response ->
        if (response.responseCase == StopOptimizationResponseDTO.ResponseCase.RUNID) apiService.stop()
    }

    override fun updateNode(
            request: NodeStatusCommandOrResponseDTO,
            responseObserver: StreamObserver<NodeChangeConfirmDTO>
    ) = responseObserver.consume {
        apiService.updateNode(request)
    }

    override fun sendMessage(
            request: MessageCommandDTO,
            responseObserver: StreamObserver<MessageResponseDTO>
    ) = responseObserver.consume {
        apiService.sendMessage(request)
    }

    override fun unregisterRequest(
            request: RequestUnRegistrationRequestDTO,
            responseObserver: StreamObserver<UnRegistrationResponseDTO>
    ) = responseObserver.consume {
        apiService.unregister(request)
    }

    override fun offerSimulationResult(
            request: SimulationResponseDTO,
            responseObserver: StreamObserver<SimulationResultConfirmDTO>
    ) = responseObserver.consumeAsync {
        apiService.offerResult(request)
    }

    override fun offerErrorResult(
            request: ErrorResponseDTO,
            responseObserver: StreamObserver<ErrorConfirmDTO>
    ) = responseObserver.consumeAsync {
        apiService.offerError(request)
    }

    override fun offerSimulationConfig(
            request: NodeStatusCommandOrResponseDTO,
            responseObserver: StreamObserver<NodeChangeConfirmDTO>
    ) = responseObserver.consumeAsync {
        apiService.offerConfig(request)
    }
}
