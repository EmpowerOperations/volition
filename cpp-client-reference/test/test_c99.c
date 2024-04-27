#include "volition.h"

#include <stdio.h>

int main() {
    printf("Simulation client started.\n");

    start_optimization_command_t start = start_optimization_command_create();

    problem_definition_t problem = start_optimization_command_set_problem_definition(start);

    input_parameter_t input = problem_definition_add_inputs(problem);
    input_parameter_set_name(input, "x1");
    continuous_t cont = input_parameter_set_continuous(input);
    continuous_set_lower_bound(cont, 0.5);
    continuous_set_upper_bound(cont, 5.0);

    evaluable_node_t evaluable = problem_definition_add_evaluables(problem);
    simulation_node_t sim = evaluable_node_set_simulation(evaluable);
    variable_mapping_t mapping = simulation_node_set_mapping_table(sim);
    variable_mapping_set_inputs(mapping, "x1", "x1");
    variable_mapping_set_outputs(mapping, "f1", "f1");
    simulation_input_parameter_t sim_input = simulation_node_add_inputs(sim);
    simulation_input_parameter_set_name(sim_input, "x1");
    simulation_output_parameter_t sim_output = simulation_node_add_outputs(sim);
    simulation_output_parameter_set_name(sim_output, "f1");

    optimization_settings_t settings = start_optimization_command_set_settings(start);
    optimization_settings_set_iteration_count(settings, 5);
    // TODO: Update example with multi-threaded usage?
    // optimization_settings_set_concurrent_run_count(settings, 2);

    unary_optimizer_t optimizer = unary_optimizer_create("localhost:27016");
    if (!optimizer) {
        printf("Could not connect to optimizer.\n");
        start_optimization_command_destroy(start);
        return -1;
    }

    optimizer_generated_query_stream_t stream =
        unary_optimizer_start_optimization(optimizer, start);

    double last_value = 42.0;
    optimizer_generated_query_t query = NULL;
    while ((query = optimizer_generated_query_stream_wait(stream))) {
        simulation_evaluation_request_t evaluation_request =
            optimizer_generated_query_get_simulation_evaluation_request(query);
        if (evaluation_request) {
            printf("Received evaluation request.\n");

            uint32_t index = simulation_evaluation_request_get_iteration_index(evaluation_request);
            double x1 = simulation_evaluation_request_get_input_vector(evaluation_request, "x1");
            printf("x1 is %f\n", x1);

            simulation_evaluation_completed_response_t response =
                simulation_evaluation_completed_response_create();
            simulation_evaluation_completed_response_set_iteration_index(response, index);
            output_vector_t output = simulation_evaluation_completed_response_set_vector(response);
            double f1 = last_value * x1;
            printf("f1 is %f\n", f1);
            output_vector_set_entries(output, "f1", f1);
            last_value -= 0.1;

            unary_optimizer_offer_simulation_result(optimizer, response);
            printf("Sent result.\n");
            continue;
        }
        printf("Skipping unknown request.\n");
        optimizer_generated_query_destroy(query);
    }
    printf("Channel closed.\n");

    optimizer_generated_query_stream_destroy(stream);

    unary_optimizer_destroy(optimizer);

    return 0;
}
