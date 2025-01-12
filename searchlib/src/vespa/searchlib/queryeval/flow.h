// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once
#include <cstddef>
#include <algorithm>
#include <vespa/vespalib/util/small_vector.h>

// Model how boolean result decisions flow through intermediate nodes
// of different types based on relative estimates for sub-expressions

namespace search::queryeval {

namespace flow {

// the default adapter expects the shape of std::unique_ptr<Blueprint>
// with respect to estimate, cost and strict_cost.
struct DefaultAdapter {
    double estimate(const auto &child) const noexcept { return child->estimate(); }
    double cost(const auto &child) const noexcept { return child->cost(); }
    double strict_cost(const auto &child) const noexcept { return child->strict_cost(); }
};

template <typename ADAPTER, typename T>
struct IndirectAdapter {
    const T &data;
    [[no_unique_address]] ADAPTER adapter;
    IndirectAdapter(ADAPTER adapter_in, const T &data_in) noexcept
      : data(data_in), adapter(adapter_in) {}
    double estimate(size_t child) const noexcept { return adapter.estimate(data[child]); }
    double cost(size_t child) const noexcept { return adapter.cost(data[child]); }
    double strict_cost(size_t child) const noexcept { return adapter.strict_cost(data[child]); }
};

auto make_index(const auto &children) {
    vespalib::SmallVector<uint32_t> index(children.size());
    for (size_t i = 0; i < index.size(); ++i) {
        index[i] = i;
    }
    return index;
}

template <typename ADAPTER>
struct MinAndCost {
    // sort children to minimize total cost of AND flow
    [[no_unique_address]] ADAPTER adapter;
    MinAndCost(ADAPTER adapter_in) noexcept : adapter(adapter_in) {}
    bool operator () (const auto &a, const auto &b) const noexcept {
        return (1.0 - adapter.estimate(a)) * adapter.cost(b) > (1.0 - adapter.estimate(b)) * adapter.cost(a);
    }
};

template <typename ADAPTER>
struct MinOrCost {
    // sort children to minimize total cost of OR flow
    [[no_unique_address]] ADAPTER adapter;
    MinOrCost(ADAPTER adapter_in) noexcept : adapter(adapter_in) {}
    bool operator () (const auto &a, const auto &b) const noexcept {
        return adapter.estimate(a) * adapter.cost(b) > adapter.estimate(b) * adapter.cost(a);
    }
};

template <typename ADAPTER>
struct MinOrStrictCost {
    // sort children to minimize total cost of strict OR flow
    [[no_unique_address]] ADAPTER adapter;
    MinOrStrictCost(ADAPTER adapter_in) noexcept : adapter(adapter_in) {}
    bool operator () (const auto &a, const auto &b) const noexcept {
        return adapter.estimate(a) * adapter.strict_cost(b) > adapter.estimate(b) * adapter.strict_cost(a);
    }
};

template <typename ADAPTER, typename T, typename F>
double estimate_of(ADAPTER adapter, const T &children, F flow) {
    for (const auto &child: children) {
        flow.add(adapter.estimate(child));
    }
    return flow.estimate();
}

template <template <typename> typename ORDER, typename ADAPTER, typename T>
void sort(ADAPTER adapter, T &children) {
    std::sort(children.begin(), children.end(), ORDER(adapter));
}

template <template <typename> typename ORDER, typename ADAPTER, typename T>
void sort_partial(ADAPTER adapter, T &children, size_t offset) {
    if (children.size() > offset) {
        std::sort(children.begin() + offset, children.end(), ORDER(adapter));
    }
}

template <typename ADAPTER, typename T, typename F>
double ordered_cost_of(ADAPTER adapter, const T &children, F flow) {
    double cost = 0.0;
    for (const auto &child: children) {
        double child_cost = flow.strict() ? adapter.strict_cost(child) : adapter.cost(child);
        cost += flow.flow() * child_cost;
        flow.add(adapter.estimate(child));
    }
    return cost;
}

template <typename ADAPTER, typename T>
size_t select_strict_and_child(ADAPTER adapter, const T &children) {
    size_t idx = 0;
    double cost = 0.0;
    size_t best_idx = 0;
    double best_diff = 0.0;
    double est = 1.0;
    for (const auto &child: children) {
        double child_cost = est * adapter.cost(child);
        double child_strict_cost = adapter.strict_cost(child);
        double child_est = adapter.estimate(child);
        if (idx == 0) {
            best_diff = child_strict_cost - child_cost;
        } else {
            double my_diff = (child_strict_cost + child_est * cost) - (cost + child_cost);
            if (my_diff < best_diff) {
                best_diff = my_diff;
                best_idx = idx;
            }
        }
        cost += child_cost;
        est *= child_est;
        ++idx;
    }
    return best_idx;
}

} // flow

template <typename FLOW>
struct FlowMixin {
    static double estimate_of(auto adapter, const auto &children) {
        return flow::estimate_of(adapter, children, FLOW(1.0, false));
    }
    static double estimate_of(const auto &children) {
        return estimate_of(flow::DefaultAdapter(), children);
    }
    static double cost_of(auto adapter, const auto &children, bool strict) {
        auto my_adapter = flow::IndirectAdapter(adapter, children);
        auto order = flow::make_index(children);
        FLOW::sort(my_adapter, order, strict);
        return flow::ordered_cost_of(my_adapter, order, FLOW(1.0, strict));
    }
    static double cost_of(const auto &children, bool strict) {
        return cost_of(flow::DefaultAdapter(), children, strict);
    }
};

class AndFlow : public FlowMixin<AndFlow> {
private:
    double _flow;
    bool _strict;
    bool _first;
public:
    AndFlow(double in, bool strict) noexcept
      : _flow(in), _strict(strict), _first(true) {}
    void add(double est) noexcept {
        _flow *= est;
        _first = false;
    }
    double flow() const noexcept {
        return _flow;
    }
    bool strict() const noexcept {
        return _strict && _first;
    }
    double estimate() const noexcept {
        return _first ? 0.0 : _flow;
    }
    static void sort(auto adapter, auto &children, bool strict) {
        flow::sort<flow::MinAndCost>(adapter, children);
        if (strict && children.size() > 1) {
            size_t idx = flow::select_strict_and_child(adapter, children);
            auto the_one = std::move(children[idx]);
            for (; idx > 0; --idx) {
                children[idx] = std::move(children[idx-1]);
            }
            children[0] = std::move(the_one);
        }
    }
    static void sort(auto &children, bool strict) {
        sort(flow::DefaultAdapter(), children, strict);
    }
};

class OrFlow : public FlowMixin<OrFlow>{
private:
    double _flow;
    bool _strict;
    bool _first;
public:
    OrFlow(double in, bool strict) noexcept
      : _flow(in), _strict(strict), _first(true) {}
    void add(double est) noexcept {
        _flow *= (1.0 - est);
        _first = false;
    }
    double flow() const noexcept {
        return _flow;
    }
    bool strict() const noexcept {
        return _strict;
    }
    double estimate() const noexcept {
        return _first ? 0.0 : (1.0 - _flow);
    }
    static void sort(auto adapter, auto &children, bool strict) {
        if (strict) {
            flow::sort<flow::MinOrStrictCost>(adapter, children);
        } else {
            flow::sort<flow::MinOrCost>(adapter, children);
        }
    }
    static void sort(auto &children, bool strict) {
        sort(flow::DefaultAdapter(), children, strict);
    }
};

class AndNotFlow : public FlowMixin<AndNotFlow> {
private:
    double _flow;
    bool _strict;
    bool _first;
public:
    AndNotFlow(double in, bool strict) noexcept
      : _flow(in), _strict(strict), _first(true) {}
    void add(double est) noexcept {
        _flow *= _first ? est : (1.0 - est);
        _first = false;
    }
    double flow() const noexcept {
        return _flow;
    }
    bool strict() const noexcept {
        return _strict && _first;
    }
    double estimate() const noexcept {
        return _first ? 0.0 : _flow;
    }
    static void sort(auto adapter, auto &children, bool) {
        flow::sort_partial<flow::MinOrCost>(adapter, children, 1);
    }
    static void sort(auto &children, bool strict) {
        sort(flow::DefaultAdapter(), children, strict);
    }
};

}
