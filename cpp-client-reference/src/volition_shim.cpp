#include "volition_api.h"

#include "object.hpp"

#include "optimizer.grpc.pb.h"
#include "optimizer.pb.h"

#include "grpcpp/grpcpp.h"

#include <bit>
#include <cstdint>
#include <memory>
#include <string_view>
#include <utility>
#include <vector>

using namespace empowerops::volition;
using namespace empowerops::volition::dto;
using namespace google;

template<class T>
constexpr auto into_unique(auto p) noexcept -> std::unique_ptr<T> {
    return std::unique_ptr<T>{std::bit_cast<T*>(p)};
}

template<class T, class F>
auto map(T&& input, F fn) -> std::vector<decltype(fn(*begin(input)))> {
    auto output = std::vector<decltype(fn(*begin(input)))>{};
    for (auto&& it : input)
        output.push_back(fn(it));
    return output;
}

struct continuous : castable<continuous> {
public:
    continuous(continuous_params const& params) : castable{this} {
        inner.set_lower_bound(params.lower_bound);
        inner.set_upper_bound(params.upper_bound);
    }

    auto take() -> ContinuousDTO { return std::exchange(inner, {}); }

private:
    ContinuousDTO inner{};
};

auto create_continuous(continuous_params const* params) noexcept -> continuous* {
    try {
        return new continuous{*params};
    } catch (...) {
        return nullptr;
    }
}

struct discrete_range : castable<discrete_range> {
public:
    discrete_range(discrete_range_params const& params) : castable{this} {
        inner.set_lower_bound(params.lower_bound);
        inner.set_upper_bound(params.upper_bound);
        inner.set_step_size(params.step_size);
    }

    auto take() -> DiscreteRangeDTO { return std::exchange(inner, {}); }

private:
    DiscreteRangeDTO inner{};
};

auto create_discrete_range(discrete_range_params const* params) noexcept -> discrete_range* {
    try {
        return new discrete_range{*params};
    } catch (...) {
        return nullptr;
    }
}

struct input_parameter : castable<input_parameter> {
public:
    input_parameter(input_parameter_params const& params) : castable{this} {
        inner.set_name(params.name);
        auto domain = into_unique<object>(params.domain);
        if (auto* m = domain->as<continuous>()) {
            *inner.mutable_continuous() = m->take();
        } else if (auto* m = domain->as<discrete_range>()) {
            *inner.mutable_discrete_range() = m->take();
        } else {
            // TODO: Assert
        }
    }

private:
    InputParameterDTO inner{};
};

auto create_input_parameter(input_parameter_params const* params) noexcept -> input_parameter* {
    try {
        return new input_parameter{*params};
    } catch (...) {
        return nullptr;
    }
}

auto destroy_input_parameter(input_parameter* obj) noexcept -> void { delete obj; }

struct simulation_evaluation_request : castable<simulation_evaluation_request> {
public:
    class view {
    public:
        view(
            std::string_view name,
            std::vector<string_to_double> input_vector,
            std::uint32_t iteration_index)
            : name_{name},
              input_vector_{std::move(input_vector)},
              iteration_index_{iteration_index} {}

        auto as_params() & -> simulation_evaluation_request_params {
            return {
                .name = name_.data(),
                .input_vector = input_vector_.data(),
                .input_vector_len = input_vector_.size(),
                .iteration_index = iteration_index_,
            };
        }

    private:
        std::string_view name_{};
        std::vector<string_to_double> input_vector_{};
        std::uint32_t iteration_index_{};
    };

    simulation_evaluation_request(SimulationEvaluationRequestDTO msg)
        : castable{this}, inner{std::move(msg)} {}

    auto as_view() & -> view {
        return {
            inner.name(),
            map(inner.input_vector(),
                [](auto&& it) {
                    auto&& [key, value] = it;
                    return string_to_double{key.c_str(), value};
                }),
            inner.iteration_index(),
        };
    }

private:
    SimulationEvaluationRequestDTO inner{};
};

struct simulation_cancel_request : castable<simulation_cancel_request> {
public:
    class view {
    public:
        view(std::string_view name, std::uint32_t iteration_index)
            : name_{name}, iteration_index_{iteration_index} {}

        auto as_params() & -> simulation_cancel_request_params {
            return {.name = name_.data(), .iteration_index = iteration_index_};
        }

    private:
        std::string_view name_{};
        std::uint32_t iteration_index_{};
    };

    simulation_cancel_request(SimulationCancelRequestDTO msg)
        : castable{this}, inner{std::move(msg)} {}

    auto as_view() & -> view { return {inner.name(), inner.iteration_index()}; }

private:
    SimulationCancelRequestDTO inner{};
};

auto visit_optimizer_generated_query_purpose(
    void* user_data,
    optimizer_generated_query_params::optimizer_generated_query_purpose variant,
    optimizer_generated_query_purpose_callbacks const* callbacks) -> void {
    auto* p = std::bit_cast<object*>(variant);
    if (auto* obj = p->as<simulation_evaluation_request>()) {
        auto view = obj->as_view();
        auto params = view.as_params();
        callbacks->on_evaluation_request(user_data, &params);
    } else if (auto* obj = p->as<simulation_cancel_request>()) {
        auto view = obj->as_view();
        auto params = view.as_params();
        callbacks->on_cancel_request(user_data, &params);
    }
}

struct unary_optimizer {
public:
    unary_optimizer(std::string_view connection)
        : channel_{grpc::CreateChannel(connection.data(), grpc::InsecureChannelCredentials())},
          stub_{UnaryOptimizer::NewStub(channel_)} {}

private:
    std::shared_ptr<grpc::Channel> channel_{};
    std::unique_ptr<UnaryOptimizer::Stub> stub_{};
};

auto create_unary_optimizer(char const* connection) noexcept -> unary_optimizer* {
    try {
        return new unary_optimizer{connection};
    } catch (...) {
        return nullptr;
    }
}

auto destroy_unary_optimizer(unary_optimizer* optimizer) noexcept -> void { delete optimizer; }

struct optimizer_generated_query_stream {};

auto unary_optimizer_start_optimitzation(
    unary_optimizer* /*optimizer*/,
    start_optimization_command const* /*cmd*/) noexcept -> optimizer_generated_query_stream* {
    try {
        auto p = std::make_unique<optimizer_generated_query_stream>();
        return p.release();
    } catch (...) {
        return nullptr;
    }
}
