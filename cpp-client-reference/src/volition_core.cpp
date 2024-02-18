#include "volition_core.hpp"

#include <stdexcept>

using namespace empowerops::volition::dto;

namespace volition {

optimizer::optimizer(char const* connection)
    : channel_{grpc::CreateChannel(connection, grpc::InsecureChannelCredentials())},
      stub_{empowerops::volition::dto::UnaryOptimizer::NewStub(channel_)} {
    if (channel_->GetState(true) != GRPC_CHANNEL_READY)
        throw std::runtime_error{"failed to connect"};
}

// TODO: Inline this.
continuous_input::continuous_input(char const* name, double lower_bound, double upper_bound)
    : name_{name}, lower_bound_{lower_bound}, upper_bound_{upper_bound} {}

// TODO: Inline this.
step_input::step_input(char const* name, double lower_bound, double upper_bound, double step_size)
    : name_{name}, lower_bound_{lower_bound}, upper_bound_{upper_bound}, step_size_{step_size} {}

} // namespace volition
