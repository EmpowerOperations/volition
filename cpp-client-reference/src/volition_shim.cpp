#include "volition.h"

#include "volition_core.hpp"

#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused-parameter"

using namespace volition;

struct vl_optimizer_s : optimizer {
    using optimizer::optimizer;
};

auto vl_create_optimizer(char const* connection, vl_status* status) noexcept -> vl_optimizer {
    try {
        auto* p = new vl_optimizer_s{connection};
        if (status) *status = VL_SUCCESS;
        return p;
    } catch (...) {
        if (status) *status = VL_ERROR_CONNECT_FAILED;
        return nullptr;
    }
}

auto vl_destroy_optimizer(vl_optimizer optimizer) noexcept -> void { delete optimizer; }

struct vl_input_s {
    virtual ~vl_input_s() = default;
};

struct vl_continuous_input_s : vl_input_s, continuous_input {
    using continuous_input::continuous_input;
};

auto vl_create_continuous_input(char const* name, double lower_bound, double upper_bound) noexcept
    -> vl_input {
    try {
        return new vl_continuous_input_s{name, lower_bound, upper_bound};
    } catch (...) {
        return nullptr;
    }
}

struct vl_step_input_s : vl_input_s, step_input {
    using step_input::step_input;
};

auto vl_create_step_input(
    char const* name,
    double lower_bound,
    double upper_bound,
    double step_size) noexcept -> vl_input {
    try {
        return new vl_step_input_s{name, lower_bound, upper_bound, step_size};
    } catch (...) {
        return nullptr;
    }
}

auto vl_destroy_input(vl_input input) noexcept -> void { delete input; }

vl_evaluable vl_create_babel_constraint_evaluable(
    char const* output_name,
    char const* expr) noexcept {
    return nullptr;
}

vl_evaluable vl_create_babel_scalar_evaluable(char const* output_name, char const* expr) noexcept {
    return nullptr;
}

vl_evaluable vl_create_client_simulation_evaluable(
    char const* name,
    vl_client_input const* inputs,
    size_t input_count,
    vl_client_output const* outputs,
    size_t output_count,
    vl_duration timeout) noexcept {
    return nullptr;
}

struct vl_event_stream_s {
private:
    std::unique_ptr<
        grpc::ClientReaderInterface<empowerops::volition::dto::OptimizerGeneratedQueryDTO>>
        reader_;
};

vl_event_stream vl_create_event_stream(
    vl_optimizer optimizer,
    vl_optimization_settings const* settings,
    vl_problem_definition const* problem_definition,
    vl_seed_point const* seed_points,
    size_t point_count,
    vl_status* status) noexcept {
    return nullptr;
}

void vl_destroy_event_stream(vl_event_stream event_stream) noexcept {}

vl_status vl_wait_event(vl_event_stream event_stream, vl_event_callbacks* callbacks) {
    return VL_SUCCESS;
}

vl_status vl_stop_optimization(vl_event_stream events) noexcept { return VL_SUCCESS; }

vl_status vl_send_evaluation_result(
    vl_evaluation_token token,
    vl_evaluation_result const* result) noexcept {
    return VL_SUCCESS;
}

#pragma GCC diagnostic pop
