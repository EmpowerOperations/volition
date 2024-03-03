#pragma once

namespace empowerops::volition {

struct typeinfo {};

template<class T>
inline constexpr typeinfo typeinfo_for = {};

class object {
public:
    virtual ~object() = default;

    template<class T>
    auto as() noexcept -> T* {
        return get_typeinfo() == &typeinfo_for<T> ? static_cast<T*>(this) : nullptr;
    }

private:
    virtual auto get_typeinfo() noexcept -> typeinfo const* = 0;
};

template<class T>
class castable : public object {
protected:
    castable(T* /* derived */) noexcept {}

private:
    auto get_typeinfo() noexcept -> typeinfo const* final { return &typeinfo_for<T>; }
};

} // namespace empowerops::volition
