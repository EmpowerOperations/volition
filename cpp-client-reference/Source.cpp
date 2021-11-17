#pragma once

#include <random>
#include <iostream>

#include "optimizer.pb.h"
#include "optimizer.grpc.pb.h"

#include <grpcpp/grpcpp.h>

#include <windows.h>

//winsock2 needed by grpc
#pragma comment(lib, "Ws2_32.lib")

using grpc::Channel;
using grpc::ClientContext;
using grpc::Status;
using empowerops::volition::dto::UnaryOptimizer;
using empowerops::volition::dto::StartOptimizationCommandDTO;
using empowerops::volition::dto::OptimizerGeneratedQueryDTO;
using empowerops::volition::dto::SimulationEvaluationResultConfirmDTO;
using empowerops::volition::dto::SimulationEvaluationCompletedResponseDTO;

int main() {

	std::cout << "running cpp volition sample client!" << std::endl;

	auto channel = grpc::CreateChannel("localhost:5550", grpc::InsecureChannelCredentials());
	auto stub = UnaryOptimizer::NewStub(channel);

	auto context = new ClientContext();
	auto start = new StartOptimizationCommandDTO();
	start->mutable_settings()->set_iteration_count(5);

	auto input = start->mutable_problem_definition()->mutable_inputs()->Add();
	input->set_name("x1");

	auto bounds = input->continuous();
	bounds.set_lower_bound(0.5);
	bounds.set_upper_bound(1.5);

	auto evaluable = start->mutable_problem_definition()->mutable_evaluables()->Add();

	auto simulation = evaluable->mutable_simulation();
	simulation->set_auto_map(true);

	auto inputParam = simulation->mutable_inputs()->Add();
	inputParam->set_name("x1");

	auto outputParam = simulation->mutable_outputs()->Add();
	outputParam->set_name("f1");

	OptimizerGeneratedQueryDTO optimizerQuery;
	auto reader = stub->StartOptimization(context, *start);

	if(channel->GetState(true) != GRPC_CHANNEL_READY)
	{
		std::cout << "couldn't connect!" << std::endl;
	}

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