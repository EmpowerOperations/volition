#include "volition.h"

#include <stdio.h>

typedef struct {
    double last_value;
} simulation_state;

int main() {
    unary_optimizer_t optimizer = create_unary_optimizer("localhost:27016");
    input_parameter_t input = vl_create(
        input_parameter,
        {.name = "x1",
         .domain.continuous = vl_create(continuous, {.lower_bound = 0.5, .upper_bound = 5.0})});
    vl_destroy(input_parameter, input);

    destroy_unary_optimizer(optimizer);
#if 0
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
#endif
    return 0;
}
