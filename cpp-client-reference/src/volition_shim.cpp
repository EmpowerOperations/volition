#include "volition_api.h"

#include "optimizer.grpc.pb.h"
#include "optimizer.pb.h"

#include <bit>
#include <memory>
#include <utility>

using namespace empowerops::volition::dto;
using namespace google;

template<class T>
constexpr auto into_unique(auto& p) noexcept -> std::unique_ptr<T> {
    // TODO: Zero out `p`?
    return std::unique_ptr<T>{std::bit_cast<T*>(p)};
}

struct continuous final {
    ContinuousDTO inner{};
};

auto create_continuous(continuous_params const* params) noexcept -> continuous* {
    try {
        auto p = std::make_unique<continuous>();
        p->inner.set_lower_bound(params->lower_bound);
        p->inner.set_upper_bound(params->upper_bound);
        return p.release();
    } catch (...) {
        return nullptr;
    }
}

struct discrete_range final {
    DiscreteRangeDTO inner{};
};

struct input_parameter final {
    InputParameterDTO inner{};
};

auto create_input_parameter(input_parameter_params const* params) noexcept -> input_parameter* {
    try {
        auto p = std::make_unique<input_parameter>();
        p->inner.set_name(params->name);
        auto domain = into_unique<protobuf::Message>(params->domain);
        if (auto* m = protobuf::DynamicCastToGenerated<ContinuousDTO>(domain.get())) {
            *p->inner.mutable_continuous() = std::move(*m);
        } else if (auto* m = protobuf::DynamicCastToGenerated<DiscreteRangeDTO>(domain.get())) {
            *p->inner.mutable_discrete_range() = std::move(*m);
        } else {
            // TODO: Assert
        }
        return p.release();
    } catch (...) {
        return nullptr;
    }
}

auto destroy_input_parameter(input_parameter* obj) noexcept -> void { delete obj; }

struct optimizer_generated_query_stream final {};

auto unary_optimizer_start_optimitzation(
    unary_optimizer* optimizer,
    start_optimization_command const* cmd) noexcept -> optimizer_generated_query_stream* {
    try {
        auto p = std::make_unique<optimizer_generated_query_stream>();
        return p.release();
    } catch (...) {
        return nullptr;
    }
}
