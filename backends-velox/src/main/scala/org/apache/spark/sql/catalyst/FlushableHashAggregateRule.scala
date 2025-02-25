/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.catalyst

import io.glutenproject.execution.{FlushableHashAggregateExecTransformer, ProjectExecTransformer, RegularHashAggregateExecTransformer}

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.aggregate.{Partial, PartialMerge}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.exchange.ShuffleExchangeLike

/**
 * To transform regular aggregation to intermediate aggregation that internally enables
 * optimizations such as flushing and abandoning.
 */
case class FlushableHashAggregateRule(session: SparkSession) extends Rule[SparkPlan] {
  override def apply(plan: SparkPlan): SparkPlan = plan.transformUp {
    case shuffle: ShuffleExchangeLike =>
      // If an exchange follows a hash aggregate in which all functions are in partial mode,
      // then it's safe to convert the hash aggregate to flushable hash aggregate.
      shuffle.child match {
        case HashAggPropagatedToShuffle(proj, agg) =>
          shuffle.withNewChildren(
            Seq(proj.withNewChildren(Seq(FlushableHashAggregateExecTransformer(
              agg.requiredChildDistributionExpressions,
              agg.groupingExpressions,
              agg.aggregateExpressions,
              agg.aggregateAttributes,
              agg.initialInputBufferOffset,
              agg.resultExpressions,
              agg.child
            )))))
        case HashAggWithShuffle(agg) =>
          shuffle.withNewChildren(
            Seq(FlushableHashAggregateExecTransformer(
              agg.requiredChildDistributionExpressions,
              agg.groupingExpressions,
              agg.aggregateExpressions,
              agg.aggregateAttributes,
              agg.initialInputBufferOffset,
              agg.resultExpressions,
              agg.child
            )))
        case _ =>
          shuffle
      }
  }
}

object HashAggPropagatedToShuffle {
  def unapply(
      plan: SparkPlan): Option[(ProjectExecTransformer, RegularHashAggregateExecTransformer)] = {
    if (!plan.isInstanceOf[ProjectExecTransformer]) {
      return None
    }
    val proj = plan.asInstanceOf[ProjectExecTransformer]
    val child = proj.child
    if (!child.isInstanceOf[RegularHashAggregateExecTransformer]) {
      return None
    }
    val agg = child.asInstanceOf[RegularHashAggregateExecTransformer]
    if (!agg.aggregateExpressions.forall(p => p.mode == Partial || p.mode == PartialMerge)) {
      return None
    }
    Some((proj, agg))
  }
}

object HashAggWithShuffle {
  def unapply(plan: SparkPlan): Option[RegularHashAggregateExecTransformer] = {
    if (!plan.isInstanceOf[RegularHashAggregateExecTransformer]) {
      return None
    }
    val agg = plan.asInstanceOf[RegularHashAggregateExecTransformer]
    if (!agg.aggregateExpressions.forall(p => p.mode == Partial || p.mode == PartialMerge)) {
      return None
    }
    Some(agg)
  }
}
