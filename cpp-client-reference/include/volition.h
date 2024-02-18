#pragma once
#ifndef VOLITION_H
#define VOLITION_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
#if __cplusplus >= 201103L || _MSVC_LANG >= 201103L
#define VL_NULL_HANDLE nullptr
#define VLAPI_NOEXCEPT noexcept
#else
#define VL_NULL_HANDLE NULL
#define VLAPI_NOEXCEPT throw()
#endif
#else
#define VL_NULL_HANDLE NULL
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

// Handle types.
typedef struct vl_evaluable_s* vl_evaluable;
typedef struct vl_event_stream_s* vl_event_stream;
typedef struct vl_input_s* vl_input;
typedef struct vl_optimizer_s* vl_optimizer;
typedef struct vl_result_stream_s* vl_result_stream;

// Data types.
typedef enum vl_status {
    // Success codes.
    VL_SUCCESS = 0,
    VL_TIMEOUT = 1,
    VL_STREAM_FINISHED = 2,
    // Error codes.
    VL_ERROR_CONNECT_FAILED = -1,
} vl_status;

typedef struct vl_duration {
    int64_t seconds;
    int32_t nanos;
} vl_duration;

typedef uint8_t vl_uuid[16];

typedef struct vl_evaluation_token {
    char const* const client;
    uint32_t const iteration_index;
} vl_evaluation_token;

typedef struct vl_completion_token {
    vl_uuid const uuid;
} vl_completion_token;

typedef struct vl_optimization_settings {
    vl_duration run_time;
    double target_objective_value;
    uint32_t concurrent_run_count;
    uint32_t iteration_count;
} vl_optimization_settings;

typedef struct vl_problem_definition {
    vl_input const* inputs;
    size_t input_count;
    vl_evaluable const* evaluables;
    size_t evaluable_count;
} vl_problem_definition;

typedef struct vl_evaluation_result {
    double const* output;
    bool stop_optimization;
} vl_evaluation_result;

typedef struct vl_evaluation_status {
    char const* message;
} vl_evaluation_status;

typedef struct vl_evaluation_error {
    char const* message;
    char const* stacktrace;
    bool stop_optimization;
} vl_evaluation_error;

typedef struct vl_client_input {
    char const* name;
    char const* client_name; // Set to `NULL` to use the same name.
} vl_client_input;

typedef struct vl_client_output {
    char const* name;
    char const* client_name; // Set to `NULL` to use the same name.
    bool is_bool;
} vl_client_output;

typedef struct vl_design_point {
    double const* input;
    double const* output;
    bool is_feasible;
    bool is_frontier;
} vl_design_point;

typedef struct vl_seed_point {
    double const* input;
    double const* output;
} vl_seed_point;

// - `user_data`: Data supplied by the user.
// - `token`: Single-use token that identifies the design iteration in progress. It is used for
// sending the evaluation result/status/error back to the optimizer.
// - `input`: Vector of input values. Its lifetime ends when this callback returns. The user is
// expected to copy `input` to memory they own if the values are needed later (e.g. when the results
// are evaluated asynchronously).
typedef void (*vl_evaluation_request_callback)(
    void* user_data,
    vl_evaluation_token token,
    double const* input);

typedef void (*vl_evaluation_cancel_callback)(void* user_data, vl_evaluation_token token);

typedef void (*vl_design_iteration_complete_callback)(void* user_data);

typedef void (*vl_optimization_abort_callback)(void* user_data);

typedef void (*vl_optimization_complete_callback)(void* user_data, vl_completion_token token);

typedef struct vl_event_callbacks {
    void* user_data;
    vl_evaluation_request_callback on_evaluation_request;
    vl_evaluation_cancel_callback on_evaluation_cancel;
    vl_design_iteration_complete_callback on_design_iteration_complete;
    vl_optimization_abort_callback on_optimization_abort;
    vl_optimization_complete_callback on_optimization_complete;
} vl_event_callbacks;

typedef void (*vl_result_callback)(void* user_data, vl_design_point const* point);

// Service API.
VLAPI_PUBLIC vl_optimizer vl_create_optimizer(char const* connection, vl_status* status)
    VLAPI_NOEXCEPT;

VLAPI_PUBLIC void vl_destroy_optimizer(vl_optimizer optimizer) VLAPI_NOEXCEPT;

VLAPI_PUBLIC vl_input
vl_create_continuous_input(char const* name, double lower_bound, double upper_bound) VLAPI_NOEXCEPT;

VLAPI_PUBLIC vl_input
vl_create_step_input(char const* name, double lower_bound, double upper_bound, double step_size)
    VLAPI_NOEXCEPT;

VLAPI_PUBLIC vl_input
vl_create_discrete_input(char const* name, double const* values, size_t value_count) VLAPI_NOEXCEPT;

VLAPI_PUBLIC void vl_destroy_input(vl_input input) VLAPI_NOEXCEPT;

VLAPI_PUBLIC vl_evaluable
vl_create_babel_constraint_evaluable(char const* output_name, char const* expr) VLAPI_NOEXCEPT;

VLAPI_PUBLIC vl_evaluable
vl_create_babel_scalar_evaluable(char const* output_name, char const* expr) VLAPI_NOEXCEPT;

VLAPI_PUBLIC vl_evaluable vl_create_client_simulation_evaluable(
    char const* name,
    vl_client_input const* inputs,
    size_t input_count,
    vl_client_output const* outputs,
    size_t output_count,
    vl_duration timeout) VLAPI_NOEXCEPT;

VLAPI_PUBLIC void vl_destroy_evaluable(vl_evaluable evaluable) VLAPI_NOEXCEPT;

VLAPI_PUBLIC vl_event_stream vl_create_event_stream(
    vl_optimizer optimizer,
    vl_optimization_settings const* settings,
    vl_problem_definition const* problem_definition,
    vl_seed_point const* seed_points,
    size_t point_count,
    vl_status* status) VLAPI_NOEXCEPT;

VLAPI_PUBLIC void vl_destroy_event_stream(vl_event_stream events) VLAPI_NOEXCEPT;

// May propagate exceptions from callbacks.
VLAPI_PUBLIC vl_status vl_wait_event(vl_event_stream event_stream, vl_event_callbacks* callbacks);

VLAPI_PUBLIC vl_status vl_stop_optimization(vl_event_stream events) VLAPI_NOEXCEPT;

VLAPI_PUBLIC vl_status vl_send_evaluation_result(
    vl_evaluation_token token,
    vl_evaluation_result const* result) VLAPI_NOEXCEPT;

VLAPI_PUBLIC vl_status vl_send_evaluation_status(
    vl_evaluation_token token,
    vl_evaluation_status const* status) VLAPI_NOEXCEPT;

VLAPI_PUBLIC vl_status vl_send_evaluation_error(
    vl_evaluation_token token,
    vl_evaluation_error const* error) VLAPI_NOEXCEPT;

VLAPI_PUBLIC vl_result_stream vl_create_result_stream(vl_completion_token token, vl_status* status)
    VLAPI_NOEXCEPT;

VLAPI_PUBLIC void vl_destroy_result_stream(vl_result_stream) VLAPI_NOEXCEPT;

// May propagate exceptions from callbacks.
VLAPI_PUBLIC vl_status
vl_wait_result(vl_result_stream results, void* user_data, vl_result_callback on_result);

#ifdef __cplusplus
}
#endif

#endif // VOLITION_H
