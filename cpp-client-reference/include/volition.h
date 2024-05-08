#pragma once
#ifndef VOLITION_H
#define VOLITION_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
#if __cplusplus >= 201103L || _MSVC_LANG >= 201103L
#define VLAPI_NOEXCEPT noexcept
#else
#define VLAPI_NOEXCEPT throw()
#endif
#else
#define VLAPI_NOEXCEPT
#endif

#ifdef _WIN32
#define VLAPI_PUBLIC __declspec(dllexport)
#elif __GNUC__ >= 4
#define VLAPI_PUBLIC __attribute__((visibility("default")))
#else
#define VLAPI_PUBLIC
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef struct duration const* duration_t;
struct duration {
    int64_t seconds;
    int32_t nanos;
};

#define UUID_BYTES (128 / 8)

typedef uint8_t uuid_t[UUID_BYTES];

typedef struct continuous* continuous_t;
VLAPI_PUBLIC void continuous_set_lower_bound(continuous_t continuous, double lower_bound)
    VLAPI_NOEXCEPT;
VLAPI_PUBLIC void continuous_set_upper_bound(continuous_t continuous, double upper_bound)
    VLAPI_NOEXCEPT;

typedef struct discrete_range* discrete_range_t;
VLAPI_PUBLIC void discrete_range_set_lower_bound(discrete_range_t discrete, double lower_bound)
    VLAPI_NOEXCEPT;
VLAPI_PUBLIC void discrete_range_set_upper_bound(discrete_range_t discrete, double upper_bound)
    VLAPI_NOEXCEPT;
VLAPI_PUBLIC void discrete_range_set_step_size(discrete_range_t discrete, double step_size)
    VLAPI_NOEXCEPT;

typedef struct input_parameter* input_parameter_t;
VLAPI_PUBLIC void input_parameter_set_name(input_parameter_t input, char const* name)
    VLAPI_NOEXCEPT;
VLAPI_PUBLIC continuous_t input_parameter_set_continuous(input_parameter_t input) VLAPI_NOEXCEPT;
VLAPI_PUBLIC discrete_range_t input_parameter_set_discrete_range(input_parameter_t input)
    VLAPI_NOEXCEPT;

typedef struct babel_scalar_node* babel_scalar_node_t;

typedef struct variable_mapping* variable_mapping_t;
VLAPI_PUBLIC void variable_mapping_set_inputs(
    variable_mapping_t mapping,
    char const* simulation_name,
    char const* optimization_name) VLAPI_NOEXCEPT;
VLAPI_PUBLIC void variable_mapping_set_outputs(
    variable_mapping_t mapping,
    char const* simulation_name,
    char const* optimization_name) VLAPI_NOEXCEPT;

typedef struct simulation_input_parameter* simulation_input_parameter_t;
VLAPI_PUBLIC void simulation_input_parameter_set_name(
    simulation_input_parameter_t input,
    char const* name) VLAPI_NOEXCEPT;

typedef struct simulation_output_parameter* simulation_output_parameter_t;
VLAPI_PUBLIC void simulation_output_parameter_set_name(
    simulation_output_parameter_t output,
    char const* name) VLAPI_NOEXCEPT;
VLAPI_PUBLIC void simulation_output_parameter_set_is_boolean(
    simulation_output_parameter_t output,
    bool boolean) VLAPI_NOEXCEPT;

typedef struct simulation_node* simulation_node_t;
VLAPI_PUBLIC void simulation_node_set_name(simulation_node_t node, char const* name) VLAPI_NOEXCEPT;
VLAPI_PUBLIC variable_mapping_t simulation_node_set_mapping_table(simulation_node_t node)
    VLAPI_NOEXCEPT;
VLAPI_PUBLIC simulation_input_parameter_t simulation_node_add_inputs(simulation_node_t node)
    VLAPI_NOEXCEPT;
VLAPI_PUBLIC simulation_output_parameter_t simulation_node_add_outputs(simulation_node_t node)
    VLAPI_NOEXCEPT;
VLAPI_PUBLIC void simulation_node_set_timeout(simulation_node_t node, duration_t timeout)
    VLAPI_NOEXCEPT;

typedef struct babel_constraint_node* babel_constraint_node_t;

typedef struct evaluable_node* evaluable_node_t;
VLAPI_PUBLIC babel_scalar_node_t evaluable_node_set_transform(evaluable_node_t node) VLAPI_NOEXCEPT;
VLAPI_PUBLIC simulation_node_t evaluable_node_set_simulation(evaluable_node_t node) VLAPI_NOEXCEPT;
VLAPI_PUBLIC babel_constraint_node_t evaluable_node_set_constraint(evaluable_node_t node)
    VLAPI_NOEXCEPT;

typedef struct problem_definition* problem_definition_t;
VLAPI_PUBLIC input_parameter_t problem_definition_add_inputs(problem_definition_t problem)
    VLAPI_NOEXCEPT;
VLAPI_PUBLIC evaluable_node_t problem_definition_add_evaluables(problem_definition_t problem)
    VLAPI_NOEXCEPT;

typedef struct optimization_settings* optimization_settings_t;
VLAPI_PUBLIC void optimization_settings_set_run_time(
    optimization_settings_t settings,
    duration_t time) VLAPI_NOEXCEPT;
VLAPI_PUBLIC void optimization_settings_set_iteration_count(
    optimization_settings_t settings,
    uint32_t count) VLAPI_NOEXCEPT;
VLAPI_PUBLIC void optimization_settings_set_target_objective_value(
    optimization_settings_t settings,
    double objective) VLAPI_NOEXCEPT;
VLAPI_PUBLIC void optimization_settings_set_concurrent_run_count(
    optimization_settings_t settings,
    uint32_t count) VLAPI_NOEXCEPT;

typedef struct seed_row* seed_row_t;
VLAPI_PUBLIC void seed_row_add_inputs(seed_row_t row, double input) VLAPI_NOEXCEPT;
VLAPI_PUBLIC void seed_row_add_outputs(seed_row_t row, double output) VLAPI_NOEXCEPT;

typedef struct start_optimization_command* start_optimization_command_t;
VLAPI_PUBLIC start_optimization_command_t start_optimization_command_create() VLAPI_NOEXCEPT;
VLAPI_PUBLIC void start_optimization_command_destroy(start_optimization_command_t command)
    VLAPI_NOEXCEPT;
VLAPI_PUBLIC problem_definition_t start_optimization_command_set_problem_definition(
    start_optimization_command_t command) VLAPI_NOEXCEPT;
VLAPI_PUBLIC optimization_settings_t
start_optimization_command_set_settings(start_optimization_command_t command) VLAPI_NOEXCEPT;
VLAPI_PUBLIC seed_row_t
start_optimization_command_add_seed_points(start_optimization_command_t command) VLAPI_NOEXCEPT;

typedef struct unary_optimizer* unary_optimizer_t;
VLAPI_PUBLIC unary_optimizer_t unary_optimizer_create(char const* connection) VLAPI_NOEXCEPT;
VLAPI_PUBLIC void unary_optimizer_destroy(unary_optimizer_t optimizer) VLAPI_NOEXCEPT;

typedef struct simulation_evaluation_request* simulation_evaluation_request_t;
VLAPI_PUBLIC char const* simulation_evaluation_request_get_name(
    simulation_evaluation_request_t request) VLAPI_NOEXCEPT;
VLAPI_PUBLIC double simulation_evaluation_request_get_input_vector(
    simulation_evaluation_request_t request,
    char const* name) VLAPI_NOEXCEPT;
VLAPI_PUBLIC uint32_t simulation_evaluation_request_get_iteration_index(
    simulation_evaluation_request_t request) VLAPI_NOEXCEPT;

typedef struct optimizer_generated_query* optimizer_generated_query_t;
VLAPI_PUBLIC void optimizer_generated_query_destroy(optimizer_generated_query_t query)
    VLAPI_NOEXCEPT;

VLAPI_PUBLIC simulation_evaluation_request_t
optimizer_generated_query_get_simulation_evaluation_request(optimizer_generated_query_t query)
    VLAPI_NOEXCEPT;

typedef struct optimizer_generated_query_stream* optimizer_generated_query_stream_t;
VLAPI_PUBLIC void optimizer_generated_query_stream_destroy(
    optimizer_generated_query_stream_t stream) VLAPI_NOEXCEPT;
VLAPI_PUBLIC optimizer_generated_query_t
optimizer_generated_query_stream_wait(optimizer_generated_query_stream_t stream) VLAPI_NOEXCEPT;

VLAPI_PUBLIC optimizer_generated_query_stream_t unary_optimizer_start_optimization(
    unary_optimizer_t optimizer,
    start_optimization_command_t command) VLAPI_NOEXCEPT;

typedef struct output_vector* output_vector_t;
VLAPI_PUBLIC void output_vector_set_entries(output_vector_t output, char const* name, double value)
    VLAPI_NOEXCEPT;

typedef struct simulation_evaluation_completed_response* simulation_evaluation_completed_response_t;
VLAPI_PUBLIC simulation_evaluation_completed_response_t
simulation_evaluation_completed_response_create() VLAPI_NOEXCEPT;
VLAPI_PUBLIC void simulation_evaluation_completed_response_destroy(
    simulation_evaluation_completed_response_t response) VLAPI_NOEXCEPT;
VLAPI_PUBLIC void simulation_evaluation_completed_response_set_name(
    simulation_evaluation_completed_response_t response,
    char const* name) VLAPI_NOEXCEPT;
VLAPI_PUBLIC void simulation_evaluation_completed_response_set_iteration_index(
    simulation_evaluation_completed_response_t response,
    uint32_t index) VLAPI_NOEXCEPT;
VLAPI_PUBLIC void simulation_evaluation_completed_response_set_abort_optimization(
    simulation_evaluation_completed_response_t response,
    bool abort) VLAPI_NOEXCEPT;
VLAPI_PUBLIC output_vector_t simulation_evaluation_completed_response_set_vector(
    simulation_evaluation_completed_response_t response) VLAPI_NOEXCEPT;

VLAPI_PUBLIC void unary_optimizer_offer_simulation_result(
    unary_optimizer_t optimizer,
    simulation_evaluation_completed_response_t response) VLAPI_NOEXCEPT;

#ifdef __cplusplus
}
#endif

#endif // VOLITION_H
