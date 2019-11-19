// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

package com.yahoo.search.searchers;

import com.google.common.annotations.Beta;

import com.yahoo.container.QrSearchersConfig;
import com.yahoo.prelude.query.Item;
import com.yahoo.prelude.query.NearestNeighborItem;
import com.yahoo.prelude.query.ToolBox;
import com.yahoo.search.Query;
import com.yahoo.search.Result;
import com.yahoo.search.Searcher;
import com.yahoo.search.query.ranking.RankProperties;
import com.yahoo.search.result.ErrorMessage;
import com.yahoo.search.searchchain.Execution;
import com.yahoo.tensor.Tensor;
import com.yahoo.tensor.TensorType;
import com.yahoo.vespa.config.search.AttributesConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Validates any NearestNeighborItem query items.
 *
 * @author arnej
 */
@Beta
public class ValidateNearestNeighborSearcher extends Searcher {

    private Map<String, TensorType> validAttributes = new HashMap<>();

    public ValidateNearestNeighborSearcher(AttributesConfig attributesConfig) {
        for (AttributesConfig.Attribute a : attributesConfig.attribute()) {
            TensorType tt = null;
            if (a.datatype() == AttributesConfig.Attribute.Datatype.TENSOR) {
                tt = TensorType.fromSpec(a.tensortype());
            }
            validAttributes.put(a.name(), tt);
        }
    }

    @Override
    public Result search(Query query, Execution execution) {
        Optional<ErrorMessage> e = validate(query);
        return e.isEmpty() ? execution.search(query) : new Result(query, e.get());
    }

    private Optional<ErrorMessage> validate(Query query) {
        NNVisitor visitor = new NNVisitor(query.getRanking().getProperties(), validAttributes);
        ToolBox.visit(visitor, query.getModel().getQueryTree().getRoot());
        return visitor.errorMessage;
    }

    private static class NNVisitor extends ToolBox.QueryVisitor {

        public Optional<ErrorMessage> errorMessage = Optional.empty();

        private RankProperties rankProperties;
        private Map<String, TensorType> validAttributes;

        public NNVisitor(RankProperties rankProperties, Map<String, TensorType> validAttributes) {
            this.rankProperties = rankProperties;
            this.validAttributes = validAttributes;
        }

        @Override
        public boolean visit(Item item) {
            if (item instanceof NearestNeighborItem) {
                validate((NearestNeighborItem) item);
            }
            return true;
        }

        private void setError(String description) {
            errorMessage = Optional.of(ErrorMessage.createIllegalQuery(description));
        }

        private void validate(NearestNeighborItem item) {
            int target = item.getTargetNumHits();
            if (target < 1) {
                setError(item.toString() + " has invalid targetNumHits");
                return;
            }
            String qprop = item.getQueryTensorName();
            List<Object> rankPropValList = rankProperties.asMap().get(qprop);
            if (rankPropValList == null) {
                setError(item.toString() + " query property not found");
                return;
            }
            if (rankPropValList.size() != 1) {
                setError(item.toString() + " query property does not have a single value");
                return;
            }
            Object rankPropValue = rankPropValList.get(0);
            if (! (rankPropValue instanceof Tensor)) {
                setError(item.toString() + " query property should be a tensor, was: "+rankPropValue);
                return;
            }
            Tensor qTensor = (Tensor)rankPropValue;
            TensorType qTensorType = qTensor.type();

            String field = item.getIndexName();
            if (validAttributes.containsKey(field)) {
                TensorType fTensorType = validAttributes.get(field);
                if (fTensorType == null) {
                    setError(item.toString() + " field is not a tensor");
                    return;
                }
                if (! fTensorType.equals(qTensorType)) {
                    setError(item.toString() + " field type "+fTensorType+" does not match query property type "+qTensorType);
                    return;
                }
            } else {
                setError(item.toString() + " field is not an attribute");
                return;
            }
        }

        @Override
        public void onExit() {}

    }

}
