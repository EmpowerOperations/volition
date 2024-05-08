import grpc
import optimizer_pb2 as vol  # Generated code based on optimize.proto
import optimizer_pb2_grpc as vol_grpc  # Generated code based on optimize.proto

# Discrete set of input values
DISCRETE_SET = ["A", "B", "C", "D", "E"]


def run_oasis_client():
    # OASIS AI service address localhost:5550 - IP:Port
    channel = grpc.insecure_channel("localhost:5550",
                                    options=[('grpc.max_send_message_length', 10000000),  # bytes
                                             ('grpc.max_receive_message_length', 10000000)], )  # bytes
    stub = vol_grpc.UnaryOptimizerStub(channel)

    start_set = vol.OptimizationSettingsDTO()

    # Stopping criteria: number of simulation calls (iteration_count), wall clock time or target objective value
    # (target objective value is only valid for single objective problems)
    start_set.iteration_count = 100

    input_var1 = vol.InputParameterDTO(
        name="x1",
        continuous=vol.ContinuousDTO(lower_bound=-7, upper_bound=4))

    input_var2 = vol.InputParameterDTO(
        name="x2",
        continuous=vol.ContinuousDTO(lower_bound=-7, upper_bound=4))

    # Discrete input variable declaration
    input_var3 = vol.InputParameterDTO(
        name="x3",
        discrete_range=vol.DiscreteRangeDTO(lower_bound=0, upper_bound=len(DISCRETE_SET)-1, step_size=1))

    sim_obj = vol.SimulationNodeDTO(name="HPO",
                                    auto_map=True,
                                    suspend_and_callback=vol.SuspendAndCallbackConfigurationDTO())
    sim_obj.inputs.append(vol.SimulationInputParameterDTO(name="x1"))
    sim_obj.inputs.append(vol.SimulationInputParameterDTO(name="x2"))
    sim_obj.inputs.append(vol.SimulationInputParameterDTO(name="x3"))

    sim_obj.outputs.append(vol.SimulationOutputParameterDTO(name="f1", is_boolean=False))  # minimize f1
    sim_obj.outputs.append(vol.SimulationOutputParameterDTO(name="f2", is_boolean=False))  # expensive constraint
    sim_obj.outputs.append(vol.SimulationOutputParameterDTO(name="f3", is_boolean=False))

    problem_definition = vol.ProblemDefinitionDTO()
    problem_definition.inputs.append(input_var1)
    problem_definition.inputs.append(input_var2)
    problem_definition.inputs.append(input_var3)
    problem_definition.evaluables.append(vol.EvaluableNodeDTO(simulation=sim_obj))

    # Cheap constraint (consume only design variables) declaration with Babel expression, satisfied before sim. call.
    cheap_constraint = vol.BabelConstraintNodeDTO(output_name="c1", boolean_expression="x1+x2<=3")

    # Expensive constraint (consume only sim. output) declaration with Babel expression
    expensive_constraint = vol.BabelConstraintNodeDTO(output_name="c2", boolean_expression="f2<10")

    # Math objective for inverting f3 for maximization
    math_objective = vol.BabelScalarNodeDTO(output_name="maximize_f3", scalar_expression="-f3")  # maximize f3

    # add constraints and math objective to problem_definition evaluable
    problem_definition.evaluables.append(vol.EvaluableNodeDTO(constraint=cheap_constraint))
    problem_definition.evaluables.append(vol.EvaluableNodeDTO(constraint=expensive_constraint))
    problem_definition.evaluables.append(vol.EvaluableNodeDTO(transform=math_objective))

    opt_start = vol.StartOptimizationCommandDTO(problem_definition=problem_definition,
                                                settings=start_set)

    # Start optimization loop and check the message type for action
    for response in stub.StartOptimization(opt_start):
        if response.HasField("optimization_started_notification"):
            print("OASIS Server: Optimization Start")
            print("            : Run-ID= " + response.optimization_started_notification.run_ID.value)

        elif response.HasField("evaluation_request"):
            print("OASIS Server: Evaluation Request")
            for key in response.evaluation_request.input_vector:
                print("            : " + key + "= " + str(response.evaluation_request.input_vector[key]))

            # Simulation call to get scalar simulation outputs
            simulation_outputs = fake_simulation_call(response.evaluation_request.input_vector)
            f1 = simulation_outputs[0]
            f2 = simulation_outputs[1]
            f3 = simulation_outputs[2]

            print("Client: Simulation Output f1=" + str(f1))
            print("Client: Simulation Output f2=" + str(f2))
            print("Client: Simulation Output f3=" + str(f3))

            sim_output = vol.SimulationEvaluationCompletedResponseDTO()
            sim_output.output_vector["f1"] = f1
            sim_output.output_vector["f2"] = f2
            sim_output.output_vector["f3"] = f3

            # Send back the simulation outputs to OASIS AI server
            stub.OfferSimulationResult(sim_output)

        elif response.HasField("design_iteration_completed_notification"):
            print("OASIS Server: Design iteration completed.")

        elif response.HasField("optimization_finished_notification"):
            print("OASIS Server: Optimization Finished Notification")

            # Getting the result sheet from OASIS AI server

            uu_id = vol.UUIDDTO(value=response.optimization_finished_notification.run_ID.value)

            results_query = vol.OptimizationResultsQueryDTO(run_ID=uu_id)

            results = stub.RequestRunResult(results_query)

            header = ""
            for input_names in results.input_columns:
                header += input_names + ","

            for output_names in results.output_columns:
                header += output_names + ","

            # Heading isFeasible, isFrontier description as below
            # isFeasible=True for constrained problems where a design satisfies all constraints
            # isFrontier=True for minimum objective value for single objective problems
            # isFrontier=True indicates pareto frontier designs for multi-objective problems

            header += "isFeasible,isFrontier\n"

            table_rows = ""
            for row in results.points:
                for input_val in row.inputs:
                    table_rows += str(input_val) + ","

                for output_val in row.outputs:
                    table_rows += str(output_val) + ","

                table_rows += f"{row.is_feasible},"
                table_rows += f"{row.is_frontier}"
                table_rows += "\n"

            result_table = header + table_rows

            # save result table
            file = open(r"result-table.csv", "w")
            file.write(result_table)
            file.close()

        elif response.HasField("optimization_not_started_notification"):
            print("OASIS Server: " + str(response.optimization_not_started_notification.issues))

        elif response.HasField("cancel_request"):
            print("OASIS Server: Cancel Request")

        else:
            print("Client: OASIS server response not recognized.")

    print("Python Script Execution Finished.")


def fake_simulation_call(input_vector):
    x1 = float(input_vector["x1"])
    x2 = float(input_vector["x2"])
    x3 = DISCRETE_SET[int(input_vector["x3"])]

    # Toy problem that consumes the input values
    f1 = pow(x1 - 2, 2) + pow(x2 - 1, 2) + 3
    f2 = x1 * pow(x2, 2)
    f3 = 0.5*x1 + x2 - 1

    match x3:
        case "A":
            f1 = f1 * 5
        case "B":
            f1 = f1 * 4
        case "C":
            f1 = f1 * 3
        case "D":
            f1 = f1 * 6
        case "E":
            f1 = f1 * 7

    return [f1, f2, f3]


if __name__ == '__main__':
    run_oasis_client()
