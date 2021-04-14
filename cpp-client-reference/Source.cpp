#pragma once

#include <random>

#include "optimizer.pb.h"
#include "optimizer.grpc.pb.h"

#include <grpcpp/grpcpp.h>

#include <windows.h>
#include <winsock2.h>
#pragma comment(lib, "Ws2_32.lib")

using grpc::Channel;
using grpc::ClientContext;
using grpc::Status;
using empowerops::volition::dto::UnaryOptimizer;
using empowerops::volition::dto::StartOptimizationCommandDTO;
using empowerops::volition::dto::StartOptimizationCommandDTO_SimulationNode;
using empowerops::volition::dto::PrototypeInputParameter;
using empowerops::volition::dto::PrototypeInputParameter_Continuous;
using empowerops::volition::dto::OptimizerGeneratedQueryDTO;
using empowerops::volition::dto::SimulationEvaluationResultConfirmDTO;
using empowerops::volition::dto::SimulationEvaluationCompletedResponseDTO;

int main() {

	auto channel = grpc::CreateChannel("localhost:5550", grpc::InsecureChannelCredentials());
	auto stub = UnaryOptimizer::NewStub(channel);

	auto context = new ClientContext();
	auto start = new StartOptimizationCommandDTO();
	start->mutable_settings()->mutable_iteration_count()->set_value(5);

	auto input = start->mutable_problem_definition()->mutable_inputs()->Add();
	input->set_name("x1");
	auto bounds = input->continuous();
	bounds.set_lower_bound(0.5);
	bounds.set_upper_bound(1.5);

	auto output = start->mutable_problem_definition()->mutable_objectives()->Add();
	output->set_name("f1");

	auto sim = start->mutable_nodes()->Add();
	sim->set_auto_map(true);
	sim->add_inputs("x1");
	sim->add_outputs("f1");

	OptimizerGeneratedQueryDTO optimizerQuery;
	auto reader = stub->StartOptimization(context, *start);

	// std::default_random_engine randSrc(std::random_device());
	// auto distr = std::uniform_real_distribution<double>(0.0, 10.0);
	
	while (reader->Read(&optimizerQuery)) {

		std::cout << "got request:" << std::endl
			<< optimizerQuery.SerializePartialAsString() << std::endl;
		
		if (optimizerQuery.has_evaluation_request()) {
			auto result = SimulationEvaluationCompletedResponseDTO();

			// result.mutable_output_vector()->at("f1") = distr(randSrc);
			result.mutable_output_vector()->at("f1") = 42.0;

			auto confirm = SimulationEvaluationResultConfirmDTO();
			stub->OfferSimulationResult(context, result, &confirm);

			std::cout << "send result:" << std::endl
				<< result.SerializePartialAsString() << std::endl;
		}
		else
		{
			std::cout << "... didnt know how to handle it. so I did nothing." << std::endl;
		}
	}


	return 4;
}