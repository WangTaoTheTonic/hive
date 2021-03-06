/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.io.parquet.read;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.ql.io.parquet.FilterPredicateLeafBuilder;
import org.apache.hadoop.hive.ql.io.parquet.LeafFilterFactory;
import org.apache.hadoop.hive.ql.io.sarg.ExpressionTree;
import org.apache.hadoop.hive.ql.io.sarg.PredicateLeaf;
import org.apache.hadoop.hive.ql.io.sarg.SearchArgument;
import org.apache.parquet.filter2.predicate.FilterApi;
import org.apache.parquet.filter2.predicate.FilterPredicate;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.Type;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ParquetFilterPredicateConverter {
  private static final Log LOG = LogFactory.getLog(ParquetFilterPredicateConverter.class);

  /**
   * Translate the search argument to the filter predicate parquet uses. It includes
   * only the columns from the passed schema.
   * @return translate the sarg into a filter predicate
   */
  public static FilterPredicate toFilterPredicate(SearchArgument sarg, MessageType schema) {
    Set<String> columns = null;
    if (schema != null) {
      columns = new HashSet<String>();
      for (Type field : schema.getFields()) {
        columns.add(field.getName());
      }
    }

    return translate(sarg.getExpression(), sarg.getLeaves(), columns, schema);
  }

  private static FilterPredicate translate(ExpressionTree root,
                                           List<PredicateLeaf> leaves,
                                           Set<String> columns,
                                           MessageType schema) {
    FilterPredicate p = null;
    switch (root.getOperator()) {
      case OR:
        for(ExpressionTree child: root.getChildren()) {
          if (p == null) {
            p = translate(child, leaves, columns, schema);
          } else {
            FilterPredicate right = translate(child, leaves, columns, schema);
            // constant means no filter, ignore it when it is null
            if(right != null){
              p = FilterApi.or(p, right);
            }
          }
        }
        return p;
      case AND:
        for(ExpressionTree child: root.getChildren()) {
          if (p == null) {
            p = translate(child, leaves, columns, schema);
          } else {
            FilterPredicate right = translate(child, leaves, columns, schema);
            // constant means no filter, ignore it when it is null
            if(right != null){
              p = FilterApi.and(p, right);
            }
          }
        }
        return p;
      case NOT:
        FilterPredicate op = translate(root.getChildren().get(0), leaves,
            columns, schema);
        if (op != null) {
          return FilterApi.not(op);
        } else {
          return null;
        }
      case LEAF:
        PredicateLeaf leaf = leaves.get(root.getLeaf());

        // If columns is null, then we need to create the leaf
        if (columns.contains(leaf.getColumnName())) {
          Type parquetType = schema.getType(leaf.getColumnName());
          return buildFilterPredicateFromPredicateLeaf(leaf, parquetType);
        } else {
          // Do not create predicate if the leaf is not on the passed schema.
          return null;
        }
      case CONSTANT:
        return null;// no filter will be executed for constant
      default:
        throw new IllegalStateException("Unknown operator: " +
            root.getOperator());
    }
  }

  private static FilterPredicate buildFilterPredicateFromPredicateLeaf
      (PredicateLeaf leaf, Type parquetType) {
    LeafFilterFactory leafFilterFactory = new LeafFilterFactory();
    FilterPredicateLeafBuilder builder;
    try {
      builder = leafFilterFactory
          .getLeafFilterBuilderByType(leaf.getType(), parquetType);
      if (builder == null) {
        return null;
      }
      if (isMultiLiteralsOperator(leaf.getOperator())) {
        return builder.buildPredicate(leaf.getOperator(),
            leaf.getLiteralList(),
            leaf.getColumnName());
      } else {
        return builder
            .buildPredict(leaf.getOperator(),
                leaf.getLiteral(),
                leaf.getColumnName());
      }
    } catch (Exception e) {
      LOG.error("fail to build predicate filter leaf with errors" + e, e);
      return null;
    }
  }

  private static boolean isMultiLiteralsOperator(PredicateLeaf.Operator op) {
    return (op == PredicateLeaf.Operator.IN) ||
        (op == PredicateLeaf.Operator.BETWEEN);
  }
}
