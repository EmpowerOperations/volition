#include "volition.h"

#include "optimizer.grpc.pb.h"
#include "optimizer.pb.h"

#include "grpcpp/grpcpp.h"

#include <memory>
#include <type_traits>
#include <utility>

using namespace empowerops::volition;
using namespace empowerops::volition::dto;
using namespace google;

#define GUARD(cond)      \
    if (!(cond)) return; \
    else static_cast<void>(0) // NOLINT(readability-else-after-return)
#define GUARD_R(cond)       \
    if (!(cond)) return {}; \
    else static_cast<void>(0) // NOLINT(readability-else-after-return)

template<class T>
concept Pointer = std::is_pointer_v<T>;
template<class T>
concept NonPointer = !Pointer<T>;

template<NonPointer T>
constexpr auto into_unique(Pointer auto p) -> std::unique_ptr<T> {
    return std::unique_ptr<T>{reinterpret_cast<T*>(p)};
}

template<NonPointer T, Pointer U>
constexpr auto allocate_opaque() noexcept -> U {
    return reinterpret_cast<U>(std::make_unique<T>().release());
}

auto continuous_set_lower_bound(continuous_t continuous, double lower_bound) noexcept -> void {
    GUARD(continuous);
    reinterpret_cast<ContinuousDTO*>(continuous)->set_lower_bound(lower_bound);
}

auto continuous_set_upper_bound(continuous_t continuous, double upper_bound) noexcept -> void {
    GUARD(continuous);
    reinterpret_cast<ContinuousDTO*>(continuous)->set_upper_bound(upper_bound);
}

auto input_parameter_set_name(input_parameter_t input, char const* name) noexcept -> void {
    GUARD(input && name);
    reinterpret_cast<InputParameterDTO*>(input)->set_name(name);
}

auto input_parameter_set_continuous(input_parameter_t input) noexcept -> continuous_t {
    GUARD_R(input);
    auto* msg = reinterpret_cast<InputParameterDTO*>(input);
    return reinterpret_cast<continuous_t>(msg->mutable_continuous());
}

auto variable_mapping_set_inputs(
    variable_mapping_t mapping,
    char const* simulation_name,
    char const* optimization_name) noexcept -> void {
    GUARD(mapping && simulation_name && optimization_name);
    auto* map = reinterpret_cast<VariableMappingDTO*>(mapping)->mutable_inputs();
    (*map)[simulation_name].set_value(optimization_name);
}

auto variable_mapping_set_outputs(
    variable_mapping_t mapping,
    char const* simulation_name,
    char const* optimization_name) noexcept -> void {
    GUARD(mapping && simulation_name && optimization_name);
    auto* map = reinterpret_cast<VariableMappingDTO*>(mapping)->mutable_outputs();
    (*map)[simulation_name].set_value(optimization_name);
}

auto simulation_input_parameter_set_name(
    simulation_input_parameter_t input,
    char const* name) noexcept -> void {
    GUARD(input && name);
    reinterpret_cast<SimulationInputParameterDTO*>(input)->set_name(name);
}

auto simulation_output_parameter_set_name(
    simulation_output_parameter_t output,
    char const* name) noexcept -> void {
    GUARD(output && name);
    reinterpret_cast<SimulationOutputParameterDTO*>(output)->set_name(name);
}

auto simulation_output_parameter_set_is_boolean(
    simulation_output_parameter_t output,
    bool boolean) noexcept -> void {
    GUARD(output);
    reinterpret_cast<SimulationOutputParameterDTO*>(output)->set_is_boolean(boolean);
}

auto simulation_node_set_mapping_table(simulation_node_t node) noexcept -> variable_mapping_t {
    GUARD_R(node);
    auto* msg = reinterpret_cast<SimulationNodeDTO*>(node);
    return reinterpret_cast<variable_mapping_t>(msg->mutable_mapping_table());
}

auto simulation_node_add_inputs(simulation_node_t node) noexcept -> simulation_input_parameter_t {
    GUARD_R(node);
    auto* msg = reinterpret_cast<SimulationNodeDTO*>(node);
    return reinterpret_cast<simulation_input_parameter_t>(msg->mutable_inputs()->Add());
}

auto simulation_node_add_outputs(simulation_node_t node) noexcept -> simulation_output_parameter_t {
    GUARD_R(node);
    auto* msg = reinterpret_cast<SimulationNodeDTO*>(node);
    return reinterpret_cast<simulation_output_parameter_t>(msg->mutable_outputs()->Add());
}

auto evaluable_node_set_simulation(evaluable_node_t node) noexcept -> simulation_node_t {
    GUARD_R(node);
    auto* msg = reinterpret_cast<EvaluableNodeDTO*>(node);
    return reinterpret_cast<simulation_node_t>(msg->mutable_simulation());
}

auto problem_definition_add_inputs(problem_definition_t problem) noexcept -> input_parameter_t {
    GUARD_R(problem);
    auto* msg = reinterpret_cast<ProblemDefinitionDTO*>(problem);
    return reinterpret_cast<input_parameter_t>(msg->add_inputs());
}

auto problem_definition_add_evaluables(problem_definition_t problem) noexcept -> evaluable_node_t {
    GUARD_R(problem);
    auto* msg = reinterpret_cast<ProblemDefinitionDTO*>(problem);
    return reinterpret_cast<evaluable_node_t>(msg->add_evaluables());
}

auto optimization_settings_set_iteration_count(
    optimization_settings_t settings,
    std::uint32_t count) noexcept -> void {
    GUARD(settings);
    reinterpret_cast<OptimizationSettingsDTO*>(settings)->set_iteration_count(count);
}

auto optimization_settings_set_concurrent_run_count(
    optimization_settings_t settings,
    std::uint32_t count) noexcept -> void {
    GUARD(settings);
    reinterpret_cast<OptimizationSettingsDTO*>(settings)->set_concurrent_run_count(count);
}

auto start_optimization_command_create() noexcept -> start_optimization_command_t {
    return allocate_opaque<StartOptimizationCommandDTO, start_optimization_command_t>();
}

auto start_optimization_command_destroy(start_optimization_command_t command) noexcept -> void {
    into_unique<StartOptimizationCommandDTO>(command);
}

auto start_optimization_command_set_problem_definition(
    start_optimization_command_t command) noexcept -> problem_definition_t {
    GUARD_R(command);
    auto* msg = reinterpret_cast<StartOptimizationCommandDTO*>(command);
    return reinterpret_cast<problem_definition_t>(msg->mutable_problem_definition());
}

auto start_optimization_command_set_settings(start_optimization_command_t command) noexcept
    -> optimization_settings_t {
    GUARD_R(command);
    auto* msg = reinterpret_cast<StartOptimizationCommandDTO*>(command);
    return reinterpret_cast<optimization_settings_t>(msg->mutable_settings());
}

auto unary_optimizer_create(char const* connection) noexcept -> unary_optimizer* try {
    GUARD_R(connection);
    auto channel = grpc::CreateChannel(connection, grpc::InsecureChannelCredentials());
    return reinterpret_cast<unary_optimizer_t>(UnaryOptimizer::NewStub(channel).release());
} catch (...) {
    return {};
}

auto unary_optimizer_destroy(unary_optimizer_t optimizer) noexcept -> void {
    into_unique<UnaryOptimizer::Stub>(optimizer);
}

auto simulation_evaluation_request_get_name(simulation_evaluation_request_t request) noexcept
    -> char const* {
    GUARD_R(request);
    return reinterpret_cast<SimulationEvaluationRequestDTO*>(request)->name().c_str();
}

auto simulation_evaluation_request_get_iteration_index(
    simulation_evaluation_request_t request) noexcept -> std::uint32_t {
    GUARD_R(request);
    return reinterpret_cast<SimulationEvaluationRequestDTO*>(request)->iteration_index();
}

auto simulation_evaluation_request_get_input_vector(
    simulation_evaluation_request_t request,
    char const* name) noexcept -> double {
    GUARD_R(request);
    return reinterpret_cast<SimulationEvaluationRequestDTO*>(request)->input_vector().at(name);
}

auto optimizer_generated_query_destroy(optimizer_generated_query_t query) noexcept -> void {
    into_unique<OptimizerGeneratedQueryDTO>(query);
}

auto optimizer_generated_query_get_simulation_evaluation_request(
    optimizer_generated_query_t query) noexcept -> simulation_evaluation_request_t {
    GUARD_R(query);
    auto* p = reinterpret_cast<OptimizerGeneratedQueryDTO*>(query);
    if (p->has_evaluation_request())
        return reinterpret_cast<simulation_evaluation_request_t>(p->mutable_evaluation_request());
    return {};
}

struct optimizer_generated_query_stream {
    std::unique_ptr<grpc::ClientReader<OptimizerGeneratedQueryDTO>> reader{};
    grpc::ClientContext context{};
};

auto optimizer_generated_query_stream_destroy(optimizer_generated_query_stream_t stream) noexcept
    -> void {
    std::unique_ptr<optimizer_generated_query_stream>{stream};
}

auto optimizer_generated_query_stream_wait(optimizer_generated_query_stream_t stream) noexcept
    -> optimizer_generated_query_t try {
    GUARD_R(stream);
    auto msg = std::make_unique<OptimizerGeneratedQueryDTO>();
    if (stream->reader->Read(msg.get()))
        return reinterpret_cast<optimizer_generated_query_t>(msg.release());
    return {};
} catch (...) {
    return {};
}

auto unary_optimizer_start_optimization(
    unary_optimizer_t optimizer,
    start_optimization_command_t command) noexcept -> optimizer_generated_query_stream_t try {
    GUARD_R(optimizer && command);
    auto* stub = reinterpret_cast<UnaryOptimizer::Stub*>(optimizer);
    auto stream = std::make_unique<optimizer_generated_query_stream>();
    stream->reader = stub->StartOptimization(
        &stream->context, *into_unique<StartOptimizationCommandDTO>(command));
    return stream.release();
} catch (...) {
    return {};
}

auto output_vector_set_entries(output_vector_t output, char const* name, double value) noexcept
    -> void {
    GUARD(output && name);
    (*reinterpret_cast<OutputVectorDTO*>(output)->mutable_entries())[name] = value;
}

auto simulation_evaluation_completed_response_create() noexcept
    -> simulation_evaluation_completed_response_t {
    return allocate_opaque<
        SimulationEvaluationCompletedResponseDTO,
        simulation_evaluation_completed_response_t>();
}

auto simulation_evaluation_completed_response_destroy(
    simulation_evaluation_completed_response_t response) noexcept -> void {
    into_unique<SimulationEvaluationCompletedResponseDTO>(response);
}

auto simulation_evaluation_completed_response_set_name(
    simulation_evaluation_completed_response_t response,
    char const* name) noexcept -> void {
    GUARD(response && name);
    reinterpret_cast<SimulationEvaluationCompletedResponseDTO*>(response)->set_name(name);
}

auto simulation_evaluation_completed_response_set_iteration_index(
    simulation_evaluation_completed_response_t response,
    uint32_t index) noexcept -> void {
    GUARD(response);
    reinterpret_cast<SimulationEvaluationCompletedResponseDTO*>(response)->set_iteration_index(
        index);
}

auto simulation_evaluation_completed_response_set_abort_optimization(
    simulation_evaluation_completed_response_t response,
    bool abort) noexcept -> void {
    GUARD(response);
    reinterpret_cast<SimulationEvaluationCompletedResponseDTO*>(response)->set_abort_optimization(
        abort);
}

auto simulation_evaluation_completed_response_set_vector(
    simulation_evaluation_completed_response_t response) noexcept -> output_vector_t {
    GUARD_R(response);
    auto* msg = reinterpret_cast<SimulationEvaluationCompletedResponseDTO*>(response);
    return reinterpret_cast<output_vector_t>(msg->mutable_vector());
}

auto unary_optimizer_offer_simulation_result(
    unary_optimizer_t optimizer,
    simulation_evaluation_completed_response_t response) noexcept -> void {
    GUARD(optimizer && response);
    auto context = grpc::ClientContext{};
    auto confirm = SimulationEvaluationResultConfirmDTO{};
    reinterpret_cast<UnaryOptimizer::Stub*>(optimizer)->OfferSimulationResult(
        &context, *into_unique<SimulationEvaluationCompletedResponseDTO>(response), &confirm);
}
