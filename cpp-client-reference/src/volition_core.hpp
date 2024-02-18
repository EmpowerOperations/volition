#pragma once

#include "optimizer.grpc.pb.h"

#include "grpcpp/grpcpp.h"

namespace volition {

class optimizer {
public:
    optimizer(char const* connection);

private:
    std::shared_ptr<grpc::Channel> channel_{};
    std::unique_ptr<empowerops::volition::dto::UnaryOptimizer::Stub> stub_{};
    grpc::ClientContext ctx_{};
};

struct continuous_input {
public:
    continuous_input(char const* name, double lower_bound, double upper_bound);

private:
    char const* name_{};
    double lower_bound_{};
    double upper_bound_{};
};

class step_input {
public:
    step_input(char const* name, double lower_bound, double upper_bound, double step_size);

private:
    char const* name_{};
    double lower_bound_{};
    double upper_bound_{};
    double step_size_{};
};

class babel_constraint_evaluable {

};

} // namespace volition
