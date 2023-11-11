#pragma once

#include "optimizer.grpc.pb.h"

#include <google/protobuf/text_format.h>
#include <grpcpp/grpcpp.h>

#include <chrono>
#include <future>
#include <random>
#include <sstream>
#include <thread>

using grpc::Channel;
using grpc::ClientContext;
using grpc::Status;
using empowerops::volition::dto::UnaryOptimizer;
using empowerops::volition::dto::StartOptimizationCommandDTO;
using empowerops::volition::dto::OptimizerGeneratedQueryDTO;
using empowerops::volition::dto::SimulationEvaluationResultConfirmDTO;
using empowerops::volition::dto::SimulationEvaluationCompletedResponseDTO;

int getPortFromArgs(int argCount, char** argValues, int& portOutput);

int main(int argCount, char** argValues) {

	std::cout << "running cpp volition sample client!" << std::endl;

	int port;
	int exitCode = getPortFromArgs(argCount, argValues, port);
	if (exitCode != 0) return exitCode;

	std::ostringstream oss;
	oss << "localhost:" << port;
	auto connectionString = oss.str();

	std::cout << "chose connection string: " << connectionString << std::endl;

	auto channel = grpc::CreateChannel(connectionString, grpc::InsecureChannelCredentials());
	auto stub = UnaryOptimizer::NewStub(channel);

	auto start = new StartOptimizationCommandDTO();
	start->mutable_settings()->set_iteration_count(5);
	start->mutable_settings()->set_concurrent_run_count(2);

	auto input = start->mutable_problem_definition()->mutable_inputs()->Add();
	input->set_name("x1");

	auto bounds = input->mutable_continuous();
	bounds->set_lower_bound(0.5);
	bounds->set_upper_bound(5.0);

	auto evaluable = start->mutable_problem_definition()->mutable_evaluables()->Add();

	auto simulation = evaluable->mutable_simulation();
	simulation->set_auto_map(true);

	auto inputParam = simulation->mutable_inputs()->Add();
	inputParam->set_name("x1");

	auto outputParam = simulation->mutable_outputs()->Add();
	outputParam->set_name("f1");

	OptimizerGeneratedQueryDTO optimizerQuery;
	auto context = new ClientContext();
	auto reader = stub->StartOptimization(context, *start);

	if(channel->GetState(true) != GRPC_CHANNEL_READY)
	{
		std::cout << "couldn't connect!" << std::endl;
		return 2;
	}

	auto formatter = google::protobuf::TextFormat::Printer();

	// std::default_random_engine randSrc(std::random_device());
	// auto distr = std::uniform_real_distribution<double>(0.0, 10.0);
	
	while (reader->Read(&optimizerQuery)) {

		std::string messageAsString;
		formatter.PrintToString(optimizerQuery, &messageAsString);

		std::cout << "got request:" << std::endl
			<< messageAsString << std::endl;

		auto futures = new std::vector<std::future<void>>();
		double lastValue = 42.0;

		if (optimizerQuery.has_evaluation_request()) {

			futures->push_back(std::async(std::launch::async,
				[&formatter, &stub, optimizerQuery, &lastValue]
				{
					std::this_thread::sleep_for(std::chrono::milliseconds(200));

					auto result = SimulationEvaluationCompletedResponseDTO();

					// result.mutable_output_vector()->at("f1") = distr(randSrc);
					(*result.mutable_vector()->mutable_entries())["f1"] = lastValue;
					lastValue -= 0.1;
					result.set_iteration_index(optimizerQuery.evaluation_request().iteration_index());

					std::string temp;
					formatter.PrintToString(result, &temp);

					auto confirm = SimulationEvaluationResultConfirmDTO();
					auto context = new ClientContext();
					stub->OfferSimulationResult(context, result, &confirm);

					std::string resultAsString;
					formatter.PrintToString(result, &resultAsString);

					std::cout << "send result:" << std::endl
						<< resultAsString << std::endl;
				}
			));
		}
		else
		{
			std::cout << "... didnt know how to handle it. so I did nothing." << std::endl;
		}
	}

	std::cout << "channel hit EOF!" << std::endl;

	return 0;
}

int getPortFromArgs(int argCount, char** argValues, int& portOutput)
{
	if (argCount == 1)
	{
		portOutput = 27016;
		return 0;
	}
	if (argCount == 2)
	{
		std::cout << "unknown arg " << argValues[1] << std::endl;
		return 1;
	}
	if (argCount == 3)
	{
		std::string arg0 = argValues[1];
		if (arg0 == "--port" || arg0 == "-p")
		{
			portOutput = std::stoi(argValues[2]);
		}
		else
		{
			std::cout << "unknown arg '" << arg0 << "'" << std::endl;
			return 2;
		}
	}
	return false;
}
