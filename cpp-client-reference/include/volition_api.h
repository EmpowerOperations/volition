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

#define UUID_BYTES (128 / 8)

typedef uint8_t uuid_t[UUID_BYTES];

typedef struct continuous* continuous_t;
struct continuous_params {
    double lower_bound;
    double upper_bound;
};

VLAPI_PUBLIC
continuous_t create_continuous(struct continuous_params const* params) VLAPI_NOEXCEPT;

typedef struct discrete_range* discrete_range_t;
struct discrete_range_params {
    double lower_bound;
    double upper_bound;
    double step_size;
};

VLAPI_PUBLIC
discrete_range_t create_discrete_range(struct discrete_range_params const* params) VLAPI_NOEXCEPT;

typedef struct input_parameter* input_parameter_t;
struct input_parameter_params {
    char const* name;
    union {
        continuous_t continuous;
        discrete_range_t discrete_range;
    } domain;
};

VLAPI_PUBLIC input_parameter_t create_input_parameter(struct input_parameter_params const* params)
    VLAPI_NOEXCEPT;
VLAPI_PUBLIC void destroy_input_parameter(input_parameter_t obj) VLAPI_NOEXCEPT;

typedef struct babel_scalar_node* babel_scalar_node_t;
struct babel_scalar_node_params {
    char const* output_name;
    char const* scalar_expression;
};

typedef struct variable_name* variable_name_t;
struct variable_name_params {
    char const* value;
};

struct string_to_variable_name {
    char const* key;
    variable_name_t value;
};

typedef struct variable_mapping* variable_mapping_t;
struct variable_mapping_params {
    struct string_to_variable_name const* inputs;
    size_t input_count;
    struct string_to_variable_name const* outputs;
    size_t output_count;
};

typedef struct simulation_input_parameter* simulation_input_parameter_t;
struct simulation_input_parameter_params {
    char const* name;
};

typedef struct simulation_output_parameter* simulation_output_parameter_t;
struct simulation_output_parameter_params {
    char const* name;
    bool is_boolean;
};

typedef struct simulation_node* simulation_node_t;
struct simulation_node_params {
    char const* name;
    variable_mapping_t mapping_table;
    simulation_input_parameter_t inputs;
    size_t input_count;
    simulation_output_parameter_t outputs;
    size_t output_count;
    duration_t timeout;
};

typedef struct babel_constraint_node* babel_constraint_node_t;
struct babel_constraint_node_params {
    char const* output_name;
    char const* boolean_expression;
};

typedef struct evaluable_node* evaluable_node_t;
struct evaluable_node_params {
    union {
        babel_scalar_node_t transform;
        simulation_node_t simulation;
        babel_constraint_node_t constraint;
    } value;
};

typedef struct problem_definition* problem_definition_t;
struct problem_definition_params {
    input_parameter_t const* inputs;
    size_t input_count;
    evaluable_node_t const* evaluables;
    size_t evaluable_count;
};

typedef struct optimization_settings* optimization_settings_t;
struct optimization_settings_params {
    duration_t const* run_time;
    uint32_t const* iteration_count;
    double const* target_objective_value;
    uint32_t const* concurrent_run_count;
};

typedef struct seed_row* seed_row_t;
struct seed_row_params {
    double const* inputs;
    size_t input_count;
    double const* outputs;
    size_t output_count;
};

typedef struct start_optimization_command* start_optimization_command_t;
struct start_optimization_command_params {
    problem_definition_t problem_definition;
    optimization_settings_t settings;
    seed_row_t const* seed_points;
    size_t seed_point_count;
};

VLAPI_PUBLIC
start_optimization_command_t create_start_optimization_command(
    struct start_optimization_command_params const* params) VLAPI_NOEXCEPT;

struct string_to_double {
    char const* key;
    double value;
};

typedef struct simulation_evaluation_request* simulation_evaluation_request_t;
struct simulation_evaluation_request_params {
    char const* name;
    struct string_to_double const* input_vector;
    size_t input_vector_len;
    uint32_t iteration_index;
};

typedef struct simulation_cancel_request* simulation_cancel_request_t;
struct simulation_cancel_request_params {
    char const* name;
    uint32_t iteration_index;
};

typedef struct optimizer_generated_query* optimization_generated_query_t;
struct optimizer_generated_query_params {
    union optimizer_generated_query_purpose {
        simulation_evaluation_request_t evaluation_request;
        simulation_cancel_request_t cancel_request;
    } purpose;
};

struct optimizer_generated_query_purpose_callbacks {
    void (*on_evaluation_request)(
        void* user_data,
        struct simulation_evaluation_request_params const* params);
    void (
        *on_cancel_request)(void* user_data, struct simulation_cancel_request_params const* params);
};

VLAPI_PUBLIC void visit_optimizer_generated_query_purpose(
    void* user_data,
    union optimizer_generated_query_purpose purpose,
    struct optimizer_generated_query_purpose_callbacks const* callbacks);

typedef struct optimizer_generated_query_stream* optimizer_generated_query_stream_t;

typedef struct unary_optimizer* unary_optimizer_t;

VLAPI_PUBLIC unary_optimizer_t create_unary_optimizer(char const* connection) VLAPI_NOEXCEPT;
VLAPI_PUBLIC void destroy_unary_optimizer(unary_optimizer_t optimizer) VLAPI_NOEXCEPT;

VLAPI_PUBLIC
optimizer_generated_query_stream_t unary_optimizer_start_optimitzation(
    unary_optimizer_t optimizer,
    start_optimization_command_t cmd) VLAPI_NOEXCEPT;

typedef struct simulation_evaluation_completed_response* simulation_evaluation_completed_response_t;
typedef struct simulation_evaluation_result_confirm* simulation_evaluation_result_confirm_t;

VLAPI_PUBLIC
simulation_evaluation_result_confirm_t unary_optimizer_offer_simulation_result(
    unary_optimizer_t optimizer,
    simulation_evaluation_completed_response_t response) VLAPI_NOEXCEPT;

typedef struct simulation_evaluation_error_response* simulation_evaluation_error_response_t;
typedef struct simulation_evaluation_error_confirm* simulation_evaluation_error_confirm_t;

VLAPI_PUBLIC
simulation_evaluation_error_confirm_t unary_optimizer_offer_error_result(
    unary_optimizer_t optimizer,
    simulation_evaluation_error_response_t response) VLAPI_NOEXCEPT;

typedef struct status_message_command* status_message_command_t;
typedef struct status_message_confirm* status_message_confirm_t;

VLAPI_PUBLIC
status_message_confirm_t unary_optimizer_offer_evaluation_status_message(
    unary_optimizer_t optimizer,
    status_message_command_t cmd) VLAPI_NOEXCEPT;

typedef struct stop_optimization_command* stop_optimization_command_t;
typedef struct stop_optimization_confirm* stop_optimization_confirm_t;

VLAPI_PUBLIC
stop_optimization_confirm_t unary_optimizer_stop_optimization(
    unary_optimizer_t optimizer,
    stop_optimization_command_t cmd) VLAPI_NOEXCEPT;

typedef struct optimization_results_query* optimization_results_query_t;
typedef struct optimization_results_response* optimization_results_response_t;

VLAPI_PUBLIC
optimization_results_response_t unary_optimizer_request_run_result(
    unary_optimizer_t optimizer,
    optimization_results_query_t query) VLAPI_NOEXCEPT;

#ifdef __cplusplus
}
#endif

#endif // VOLITION_API_H
