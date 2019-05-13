package com.empowerops.volition.ref_oasis.optimizer

import com.empowerops.volition.dto.*
import com.empowerops.volition.ref_oasis.model.ModelService
import com.empowerops.volition.ref_oasis.model.hasName
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class OptimizerEndpoint(
        private val apiService: IApiService,
        private val startStopper: IStaterStopper,
        private val modelService: ModelService
) : OptimizerGrpc.OptimizerImplBase() {

    override fun changeNodeName(
            request: NodeNameChangeCommandDTO,
            responseObserver: StreamObserver<NodeNameChangeResponseDTO>
    ) = responseObserver.consume {
        checkThenRun(modelService.simulations.hasName(request.oldName)) {
            apiService.changeNodeName(request)
        }
    }

    override fun autoConfigure(
            request: NodeStatusCommandOrResponseDTO,
            responseObserver: StreamObserver<NodeChangeConfirmDTO>
    ) = responseObserver.consume {
        checkThenRun(modelService.simulations.hasName(request.name)) {
            apiService.autoConfigure(request)
        }
    }

    override fun updateConfiguration(
            request: ConfigurationCommandDTO,
            responseObserver: StreamObserver<ConfigurationResponseDTO>
    ) = responseObserver.consume {
        checkThenRun(modelService.simulations.hasName(request.name)) {
            apiService.updateConfiguration(request)
        }
    }

    override fun requestRunResult(
            request: ResultRequestDTO,
            responseObserver: StreamObserver<ResultResponseDTO>
    ) = responseObserver.consume {
        checkThenRun(modelService.simulations.hasName(request.name)) {
            apiService.resultRequest(request)
        }
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
    ) = responseObserver.consumeAsync {
        checkThenRun(modelService.simulations.hasName(request.name)) {
            startStopper.start(request)
        }
    }

    override fun stopOptimization(
            request: StopOptimizationCommandDTO,
            responseObserver: StreamObserver<StopOptimizationResponseDTO>
    ) = responseObserver.consumeAsync {
        checkThenRun(modelService.simulations.hasName(request.name)) {
            startStopper.stop(request)
        }
    }

    override fun updateNode(
            request: NodeStatusCommandOrResponseDTO,
            responseObserver: StreamObserver<NodeChangeConfirmDTO>
    ) = responseObserver.consume {
        checkThenRun(modelService.simulations.hasName(request.name)) {
            apiService.updateNode(request)
        }
    }

    override fun sendMessage(
            request: MessageCommandDTO,
            responseObserver: StreamObserver<MessageResponseDTO>
    ) = responseObserver.consume {
        checkThenRun(modelService.simulations.hasName(request.name)) {
            apiService.sendMessage(request)
        }
    }

    override fun unregisterRequest(
            request: RequestUnRegistrationRequestDTO,
            responseObserver: StreamObserver<UnRegistrationResponseDTO>
    ) = responseObserver.consume {
        checkThenRun(modelService.simulations.hasName(request.name)) {
            apiService.unregister(request)
        }
    }

    override fun offerSimulationResult(
            request: SimulationResponseDTO,
            responseObserver: StreamObserver<SimulationResultConfirmDTO>
    ) = responseObserver.consumeAsync {
        checkThenRun(modelService.simulations.hasName(request.name)) {
            apiService.offerResult(request)
        }
    }

    override fun offerErrorResult(
            request: ErrorResponseDTO,
            responseObserver: StreamObserver<ErrorConfirmDTO>
    ) = responseObserver.consumeAsync {
        checkThenRun(modelService.simulations.hasName(request.name)) {
            apiService.offerError(request)
        }
    }

    override fun offerSimulationConfig(
            request: NodeStatusCommandOrResponseDTO,
            responseObserver: StreamObserver<NodeChangeConfirmDTO>
    ) = responseObserver.consumeAsync {
        checkThenRun(modelService.simulations.hasName(request.name)) {
            apiService.offerConfig(request)
        }
    }

    private inline fun <V> checkThenRun(hasPermission: Boolean, action: () -> V): V {
        if (hasPermission) {
            return action()
        } else {
            throw StatusRuntimeException(Status.PERMISSION_DENIED)
        }
    }

}
