// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <cstdint>

namespace search::multivalue {

template <typename T>
class Value {
public:
    Value() noexcept : _v() {}
    Value(T v) noexcept : _v(v) { }
    Value(T v, int32_t w) noexcept : _v(v) { (void) w; }
    T value()           const { return _v; }
    const T& value_ref() const { return _v; }
    T& value_ref()            { return _v; }
    operator T ()       const { return _v; }
    operator T & ()           { return _v; }
    int32_t weight()    const { return 1; }
    bool operator ==(const Value<T> & rhs) const { return _v == rhs._v; }
    bool operator <(const Value<T> & rhs) const { return _v < rhs._v; }
    bool operator >(const Value<T> & rhs) const { return _v > rhs._v; }
private:
    T _v;
};

template <typename T>
class WeightedValue {
public:
    WeightedValue() noexcept : _v(), _w(1) { }
    WeightedValue(T v, int32_t w) noexcept : _v(v), _w(w) { }
    T value()             const { return _v; }
    const T& value_ref() const  { return _v; }
    T& value_ref()              { return _v; }
    operator T ()         const { return _v; }
    operator T & ()             { return _v; }
    int32_t weight()      const { return _w; }

    bool operator==(const WeightedValue<T> & rhs) const { return _v == rhs._v; }
    bool operator <(const WeightedValue<T> & rhs) const { return _v < rhs._v; }
    bool operator >(const WeightedValue<T> & rhs) const { return _v > rhs._v; }
private:
    T       _v;
    int32_t _w;
};

}
