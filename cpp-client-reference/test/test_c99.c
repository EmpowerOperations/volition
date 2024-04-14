#include "volition.h"
#include "volition_api.h"

#include <stdio.h>

int main() {
    printf("Simulation client started.\n");

    continuous_t cont = continuous_create();
    continuous_set_lower_bound(cont, 0.5);
    continuous_set_upper_bound(cont, 5.0);
    input_parameter_t input = input_parameter_create();
    input_parameter_set_name(input, "x1");
    input_parameter_set_continuous(input, cont);

    variable_mapping_t mapping = variable_mapping_create();
    variable_mapping_set_inputs(mapping, "x1", "x1");
    variable_mapping_set_outputs(mapping, "f1", "f1");
    simulation_input_parameter_t sim_input = simulation_input_parameter_create();
    simulation_input_parameter_set_name(sim_input, "x1");
    simulation_output_parameter_t sim_output = simulation_output_parameter_create();
    simulation_output_parameter_set_name(sim_output, "f1");
    simulation_node_t sim = simulation_node_create();
    simulation_node_set_mapping_table(sim, mapping);
    simulation_node_add_inputs(sim, sim_input);
    simulation_node_add_outputs(sim, sim_output);
    evaluable_node_t evaluable = evaluable_node_create();
    evaluable_node_set_simulation(evaluable, sim);

    problem_definition_t problem = problem_definition_create();
    problem_definition_add_inputs(problem, input);
    problem_definition_add_evaluables(problem, evaluable);

    optimization_settings_t settings = optimization_settings_create();
    optimization_settings_set_iteration_count(settings, 5);
    // TODO: Update example with multi-threaded usage?
    // optimization_settings_set_concurrent_run_count(settings, 2);

    start_optimization_command_t start = start_optimization_command_create();
    start_optimization_command_set_problem_definition(start, problem);
    start_optimization_command_set_settings(start, settings);

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

            output_vector_t output = output_vector_create();
            output_vector_set_entries(output, "f1", last_value * x1);
            last_value -= 0.1;

            simulation_evaluation_completed_response_t response =
                simulation_evaluation_completed_response_create();
            simulation_evaluation_completed_response_set_iteration_index(response, index);
            simulation_evaluation_completed_response_set_vector(response, output);

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
