package com.empowerops.volition.ref_oasis.optimizer

import com.empowerops.volition.dto.*
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.GlobalScope

class OptimizerEndpoint(
        private val apiService: ApiService
) : OptimizerGrpc.OptimizerImplBase() {

    val scope = GlobalScope //TODO

    override fun register(
            request: RegistrationCommandDTO,
            responseObserver: StreamObserver<OptimizerGeneratedQueryDTO>
    ) {
        apiService.register(request, responseObserver)
    }

    override fun unregister(
            request: UnregistrationCommandDTO,
            responseObserver: StreamObserver<UnregistrationConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver) {
        apiService.unregister(request)
    }

    override fun offerSimulationConfig(
            request: NodeStatusResponseDTO,
            responseObserver: StreamObserver<NodeChangeConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver) {
        apiService.offerConfig(request)
    }

    override fun offerSimulationResult(
            request: SimulationResponseDTO,
            responseObserver: StreamObserver<SimulationResultConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver) {
        apiService.offerResult(request)
    }

    override fun offerErrorResult(
            request: ErrorResponseDTO,
            responseObserver: StreamObserver<ErrorConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver) {
        apiService.offerError(request)
    }

    override fun offerEvaluationStatusMessage(
            request: MessageCommandDTO,
            responseObserver: StreamObserver<MessageResponseDTO>
    ) = scope.consumeSingleAsync(responseObserver) {
        apiService.sendMessage(request)
    }


    override fun startOptimization(
            request: StartOptimizationCommandDTO,
            responseObserver: StreamObserver<StartOptimizationConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver) {
        apiService.start(request)
    }

    override fun stopOptimization(
            request: StopOptimizationCommandDTO,
            responseObserver: StreamObserver<StopOptimizationConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver) {
        apiService.stop(request)
    }

    override fun requestRunResult(
            request: OptimizationResultsQueryDTO,
            responseObserver: StreamObserver<OptimizationResultsResponseDTO>
    ) = scope.consumeSingleAsync(responseObserver){
        apiService.runResults(request)
    }

    override fun requestIssues(
            request: IssuesQueryDTO,
            responseObserver: StreamObserver<IssuesResponseDTO>
    ) = scope.consumeSingleAsync(responseObserver){
        TODO("apiService.requestIssues()")
    }

    override fun requestEvaluationNode(
            request: NodeStatusQueryDTO,
            responseObserver: StreamObserver<NodeStatusResponseDTO>
    ) = scope.consumeSingleAsync(responseObserver){
        TODO("apiService.requestEvaluationNode(request)")
    }

    override fun updateEvaluationNode(
            request: NodeChangeCommandDTO,
            responseObserver: StreamObserver<NodeChangeConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver){
        TODO("apiService.updateEvaluationNode(request)")
    }

    override fun requestProblemDefinition(
            request: ProblemDefinitionQueryDTO,
            responseObserver: StreamObserver<ProblemDefinitionResponseDTO>
    ) = scope.consumeSingleAsync(responseObserver){
        TODO("apiService.requestProblemDefinition(request)")
    }

    override fun updateProblemDefinition(
            request: ProblemDefinitionUpdateCommandDTO,
            responseObserver: StreamObserver<ProblemDefinitionConfirmDTO>
    ) = scope.consumeSingleAsync(responseObserver){
        TODO("apiService.updateProblemDefinition(request)")
    }


}
