#pragma once
#ifndef VOLITION_API_H
#define VOLITION_API_H

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

typedef struct duration {
    int64_t seconds;
    int32_t nanos;
} duration_t;

typedef uint8_t uuid_t[16];

typedef struct unary_optimizer* unary_optimizer_t;

VLAPI_PUBLIC unary_optimizer_t create_unary_optimizer() VLAPI_NOEXCEPT;
VLAPI_PUBLIC void destroy_unary_optimizer(unary_optimizer_t optimizer) VLAPI_NOEXCEPT;

typedef struct continuous* continuous_t;
typedef struct continuous_params {
    double lower_bound;
    double upper_bound;
} continuous_params_t;

VLAPI_PUBLIC
continuous_t create_continuous(continuous_params_t const* params) VLAPI_NOEXCEPT;

typedef struct discrete_range* discrete_range_t;
typedef struct discrete_range_params {
    double lower_bound;
    double upper_bound;
    double step_size;
} discrete_range_params_t;

VLAPI_PUBLIC
discrete_range_t create_discrete_range(discrete_range_params_t const* params) VLAPI_NOEXCEPT;

typedef struct input_parameter* input_parameter_t;
typedef struct input_parameter_params {
    char const* name;
    union {
        continuous_t continuous;
        discrete_range_t discrete_range;
    } domain;
} input_parameter_params_t;

VLAPI_PUBLIC
input_parameter_t create_input_parameter(input_parameter_params_t const* params) VLAPI_NOEXCEPT;
VLAPI_PUBLIC void destroy_input_parameter(input_parameter_t obj) VLAPI_NOEXCEPT;

typedef struct babel_scalar_node_s* babel_scalar_node;
typedef struct babel_scalar_node_params_s {
    char const* output_name;
    char const* scalar_expression;
} babel_scalar_node_params;

typedef struct variable_name_s* variable_name;
typedef struct variable_name_params_s {
    char const* value;
} variable_name_params;

typedef struct variable_mapping_s* variable_mapping;
typedef struct variable_mapping_params_s {
    struct {
        char const* key;
        variable_name value;
    } const* inputs;
    size_t input_count;
    struct {
        char const* key;
        variable_name value;
    } const* outputs;
    size_t output_count;
} variable_mapping_params;

typedef struct simulation_input_parameter_s* simulation_input_parameter;
typedef struct simulation_input_parameter_params_s {
    char const* name;
} simulation_input_parameter_params;

typedef struct simulation_output_parameter_s* simulation_output_parameter;
typedef struct simulation_output_parameter_params_s {
    char const* name;
    bool is_boolean;
} simulation_output_parameter_params;

typedef struct simulation_node_s* simulation_node;
typedef struct simulation_node_params_s {
    char const* name;
    variable_mapping mapping_table;
    simulation_input_parameter inputs;
    size_t input_count;
    simulation_output_parameter outputs;
    size_t output_count;
    duration_t timeout;
} simulation_node_params;

typedef struct babel_constraint_node_s* babel_constraint_node;
typedef struct babel_constraint_node_params_s {
    char const* output_name;
    char const* boolean_expression;
} babel_constraint_node_params;

typedef struct evaluable_node_s* evaluable_node;
typedef struct evaluable_node_params_s {
    union {
        babel_scalar_node transform;
        simulation_node simulation;
        babel_constraint_node constraint;
    } value;
} evaluable_node_params;

typedef struct problem_definition_s* problem_definition;
typedef struct problem_definition_params_s {
    input_parameter_t const* inputs;
    size_t input_count;
    evaluable_node const* evaluables;
    size_t evaluable_count;
} problem_definition_params;

typedef struct optimization_settings_s* optimization_settings;
typedef struct optimization_settings_params_s {
    duration_t const* run_time;
    uint32_t const* iteration_count;
    double const* target_objective_value;
    uint32_t const* concurrent_run_count;
} optimization_settings_params;

typedef struct seed_row_s* seed_row;
typedef struct seed_row_params_s {
    double const* inputs;
    size_t input_count;
    double const* outputs;
    size_t output_count;
} seed_row_params;

typedef struct start_optimization_command* start_optimization_command_t;
typedef struct start_optimization_command_params {
    problem_definition problem_definition;
    optimization_settings settings;
    seed_row const* seed_points;
    size_t seed_point_count;
} start_optimization_command_params_t;

VLAPI_PUBLIC
start_optimization_command_t create_start_optimization_command(
    start_optimization_command_params_t const* params) VLAPI_NOEXCEPT;

typedef struct simulation_evaluation_request* simulation_evaluation_request_t;
typedef struct simulation_evaluation_request_params {
    char const* name;
    struct {
        char const* key;
        double value;
    } const* input_vector;
    size_t input_vector_len;
    uint32_t iteration_index;
} simulation_evaluation_request_params_t;

// TODO: Take an object?
VLAPI_PUBLIC simulation_evaluation_request_params_t const* as_simulation_evaluation_request(void*)
    VLAPI_NOEXCEPT;

typedef struct optimizer_generated_query* optimization_results_query_t;

typedef struct optimizer_generated_query_callbacks {
    // TODO: WIP
} optimizer_generated_query_callbacks_t;

VLAPI_PUBLIC void visit_optimizer_generated_query(
    optimization_results_query_t query,
    void* context,
    optimizer_generated_query_callbacks_t const* callbacks);

typedef struct optimizer_generated_query_stream* optimizer_generated_query_stream_t;

VLAPI_PUBLIC
optimizer_generated_query_stream_t unary_optimizer_start_optimitzation(
    unary_optimizer_t optimizer,
    start_optimization_command_t const* cmd) VLAPI_NOEXCEPT;

typedef struct simulation_evaluation_completed_response_s* simulation_evaluation_completed_response;
typedef struct simulation_evaluation_result_confirm_s* simulation_evaluation_result_confirm;

VLAPI_PUBLIC
simulation_evaluation_result_confirm unary_optimizer_offer_simulation_result(
    unary_optimizer_t optimizer,
    simulation_evaluation_completed_response response) VLAPI_NOEXCEPT;

typedef struct simulation_evaluation_error_response_s* simulation_evaluation_error_response;
typedef struct simulation_evaluation_error_confirm_s* simulation_evaluation_error_confirm;

VLAPI_PUBLIC
simulation_evaluation_error_confirm unary_optimizer_offer_error_result(
    unary_optimizer_t optimizer,
    simulation_evaluation_error_response response) VLAPI_NOEXCEPT;

typedef struct status_message_command_s* status_message_command;
typedef struct status_message_confirm_s* status_message_confirm;

VLAPI_PUBLIC
status_message_confirm unary_optimizer_offer_evaluation_status_message(
    unary_optimizer_t optimizer,
    status_message_command cmd) VLAPI_NOEXCEPT;

typedef struct stop_optimization_command_s* stop_optimization_command;
typedef struct stop_optimization_confirm_s* stop_optimization_confirm;

VLAPI_PUBLIC
stop_optimization_confirm unary_optimizer_stop_optimization(
    unary_optimizer_t optimizer,
    stop_optimization_command cmd) VLAPI_NOEXCEPT;

typedef struct optimization_results_query_s* optimization_results_query;
typedef struct optimization_results_response_s* optimization_results_response;

VLAPI_PUBLIC
optimization_results_response unary_optimizer_request_run_result(
    unary_optimizer_t optimizer,
    optimization_results_query query) VLAPI_NOEXCEPT;

#ifdef __cplusplus
}
#endif

#endif // VOLITION_API_H
