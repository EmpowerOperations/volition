#include "volition.h"

#include <math.h>
#include <stdio.h>

#define COUNT(arr) (sizeof(arr) / sizeof(arr[0]))

typedef struct {
    double last_value;
} simulation_state;

void on_evaluation_request(void* user_data, vl_evaluation_token token, double const* input) {
    simulation_state* state = (simulation_state*)user_data;
    double const x1 = input[0];
    vl_evaluation_result const result = {
        .output = (double[]){x1 * x1 * state->last_value},
    };
    vl_send_evaluation_result(token, &result);
    state->last_value -= 0.1;
}

void on_optimization_complete(void* user_data, vl_completion_token token) {
    printf("optimization complete.\n");
}

int main() {
    vl_optimizer const optimizer = vl_create_optimizer("localhost:27016", NULL);
    if (!optimizer) return -1;
    vl_optimization_settings const settings = {
        .target_objective_value = -INFINITY,
        .concurrent_run_count = 2,
        .iteration_count = 5,
    };
    vl_input const inputs[] = {vl_create_continuous_input("x1", 0.5, 5.0)};
    vl_client_input const sim_inputs[] = {{.name = "x1", .client_name = "x1"}};
    vl_client_output const sim_outputs[] = {{.name = "f1", .client_name = "f1", .is_bool = false}};
    vl_evaluable const evaluables[] = {vl_create_client_simulation_evaluable(
        "test",
        sim_inputs,
        COUNT(sim_inputs),
        sim_outputs,
        COUNT(sim_outputs),
        (vl_duration){.seconds = 0})};
    vl_problem_definition const problem = {
        .inputs = inputs,
        .input_count = COUNT(inputs),
        .evaluables = evaluables,
        .evaluable_count = COUNT(evaluables),
    };
    vl_seed_point seed_points[] = {
        {.input = (double[]){0.6}, .output = (double[]){42.0}},
        {.input = (double[]){0.7}, .output = (double[]){42.0}},
    };
    vl_event_stream const event_stream = vl_create_event_stream(
        optimizer, &settings, &problem, seed_points, COUNT(seed_points), NULL);
    if (!event_stream) return -2;
    simulation_state state = {
        .last_value = 42.0,
    };
    vl_event_callbacks callbacks = {
        .user_data = &state,
        .on_evaluation_request = on_evaluation_request,
        .on_optimization_complete = on_optimization_complete,
    };
    vl_status status;
    while (!(status = vl_wait_event(event_stream, &callbacks))) {}
    if (status != VL_STREAM_FINISHED) return -3;

    vl_destroy_event_stream(event_stream);
    vl_destroy_optimizer(optimizer);
    return 0;
}
