// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "bm25_feature.h"
#include <vespa/searchlib/fef/itermdata.h>
#include <vespa/searchlib/fef/itermfielddata.h>
#include <vespa/searchlib/fef/objectstore.h>
#include <cmath>
#include <memory>

namespace search::features {

using fef::AnyWrapper;
using fef::Blueprint;
using fef::FeatureExecutor;
using fef::FieldInfo;
using fef::ITermData;
using fef::ITermFieldData;
using fef::MatchDataDetails;
using fef::objectstore::as_value;

Bm25Executor::Bm25Executor(const fef::FieldInfo& field,
                           const fef::IQueryEnvironment& env,
                           double avg_field_length)
    : FeatureExecutor(),
      _terms(),
      _avg_field_length(avg_field_length),
      _k1_param(1.2),
      _b_param(0.75)
{
    // TODO: Add support for setting k1 and b
    for (size_t i = 0; i < env.getNumTerms(); ++i) {
        const ITermData* term = env.getTerm(i);
        for (size_t j = 0; j < term->numFields(); ++j) {
            const ITermFieldData& term_field = term->field(j);
            if (field.id() == term_field.getFieldId()) {
                // TODO: Add support for using significance instead of default idf if specified in the query
                _terms.emplace_back(term_field.getHandle(MatchDataDetails::Cheap),
                                    calculate_inverse_document_frequency(term_field.get_matching_doc_count(),
                                                                         term_field.get_total_doc_count()));
            }
        }
    }
}

double
Bm25Executor::calculate_inverse_document_frequency(uint32_t matching_doc_count, uint32_t total_doc_count)
{
    return std::log(1 + (static_cast<double>(total_doc_count - matching_doc_count + 0.5) /
                         static_cast<double>(matching_doc_count + 0.5)));
}

void
Bm25Executor::handle_bind_match_data(const fef::MatchData& match_data)
{
    for (auto& term : _terms) {
        term.tfmd = match_data.resolveTermField(term.handle);
    }
}

void
Bm25Executor::execute(uint32_t doc_id)
{
    feature_t score = 0;
    for (const auto& term : _terms) {
        if (term.tfmd->getDocId() == doc_id) {
            feature_t num_occs = term.tfmd->getNumOccs();
            feature_t norm_field_length = ((feature_t)term.tfmd->getFieldLength()) / _avg_field_length;

            feature_t numerator = term.inverse_doc_freq * num_occs * (_k1_param + 1);
            feature_t denominator = num_occs + (_k1_param * (1 - _b_param + (_b_param * norm_field_length)));

            score += numerator / denominator;
        }
    }
    outputs().set_number(0, score);
}


Bm25Blueprint::Bm25Blueprint()
    : Blueprint("bm25"),
      _field(nullptr)
{
}

void
Bm25Blueprint::visitDumpFeatures(const fef::IIndexEnvironment& env, fef::IDumpFeatureVisitor& visitor) const
{
    (void) env;
    (void) visitor;
    // TODO: Implement
}

fef::Blueprint::UP
Bm25Blueprint::createInstance() const
{
    return std::make_unique<Bm25Blueprint>();
}

bool
Bm25Blueprint::setup(const fef::IIndexEnvironment& env, const fef::ParameterList& params)
{
    const auto& field_name = params[0].getValue();
    _field = env.getFieldByName(field_name);

    describeOutput("score", "The bm25 score for all terms searching in the given index field");
    return (_field != nullptr);
}

namespace {

vespalib::string
make_avg_field_length_key(const vespalib::string& base_name, const vespalib::string& field_name)
{
    return base_name + ".afl." + field_name;
}

}

void
Bm25Blueprint::prepareSharedState(const fef::IQueryEnvironment& env, fef::IObjectStore& store) const
{
    vespalib::string key = make_avg_field_length_key(getBaseName(), _field->name());
    if (store.get(key) == nullptr) {
        store.add(key, std::make_unique<AnyWrapper<double>>(env.get_average_field_length(_field->name())));
    }
}

fef::FeatureExecutor&
Bm25Blueprint::createExecutor(const fef::IQueryEnvironment& env, vespalib::Stash& stash) const
{
    const auto* lookup_result = env.getObjectStore().get(make_avg_field_length_key(getBaseName(), _field->name()));
    double avg_field_length = lookup_result != nullptr ?
                              as_value<double>(*lookup_result) :
                              env.get_average_field_length(_field->name());
    return stash.create<Bm25Executor>(*_field, env, avg_field_length);
}

}
