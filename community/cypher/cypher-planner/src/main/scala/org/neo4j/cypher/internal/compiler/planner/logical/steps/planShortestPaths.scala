/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.planner.logical.steps

import org.neo4j.cypher.internal.compiler.ExhaustiveShortestPathForbiddenNotification
import org.neo4j.cypher.internal.compiler.planner.logical.LogicalPlanningContext
import org.neo4j.cypher.internal.compiler.planner.logical.idp.expandSolverStep
import org.neo4j.cypher.internal.ir.{Predicate, ShortestPathPattern, _}
import org.neo4j.cypher.internal.logical.plans.{Ascending, DoNotIncludeTies, IncludeTies, LogicalPlan}
import org.neo4j.cypher.internal.v4_0.expressions._
import org.neo4j.cypher.internal.v4_0.expressions.functions.Length
import org.neo4j.cypher.internal.v4_0.rewriting.rewriters.projectNamedPaths
import org.neo4j.cypher.internal.v4_0.util.FreshIdNameGenerator
import org.neo4j.exceptions.{ExhaustiveShortestPathForbiddenException, InternalException}

case object planShortestPaths {

  def apply(inner: LogicalPlan,
            queryGraph: QueryGraph,
            shortestPaths: ShortestPathPattern,
            interestingOrder: InterestingOrder,
            context: LogicalPlanningContext): LogicalPlan = {

    val variables = Set(shortestPaths.name, Some(shortestPaths.rel.name)).flatten
    def predicateAppliesToShortestPath(p: Predicate) =
      // only select predicates related to this pattern (this is code in common with normal MATCH Pattern clauses)
      p.hasDependenciesMet(variables ++ inner.availableSymbols) &&
        // And filter with predicates that explicitly depend on shortestPath variables
        (p.dependencies intersect variables).nonEmpty

    val predicates = queryGraph.selections.predicates.collect {
      case p@Predicate(_, expr) if predicateAppliesToShortestPath(p) => expr
    }.toIndexedSeq

    def doesNotDependOnFullPath(predicate: Expression): Boolean = {
      (predicate.dependencies.map(_.name) intersect variables).isEmpty
    }

    val (safePredicates, needFallbackPredicates) = predicates.partition {
      case NoneIterablePredicate(FilterScope(_, Some(innerPredicate)), _) if doesNotDependOnFullPath(innerPredicate) => true
      case AllIterablePredicate(FilterScope(_, Some(innerPredicate)), _) if doesNotDependOnFullPath(innerPredicate) => true
      case _ => false
    }

    if (needFallbackPredicates.nonEmpty) {
      planShortestPathsWithFallback(inner, shortestPaths, predicates, safePredicates, needFallbackPredicates, queryGraph, interestingOrder, context)
    }
    else {
      context.logicalPlanProducer.planShortestPath(inner, shortestPaths, predicates, withFallBack = false,
                                                   disallowSameNode = context.errorIfShortestPathHasCommonNodesAtRuntime, interestingOrder, context = context)
    }
  }

  private def createPathExpression(pattern: PatternElement): PathExpression = {
    val pos = pattern.position
    val path = EveryPath(pattern)
    val step: PathStep = projectNamedPaths.patternPartPathExpression(path)
    PathExpression(step)(pos)
  }

  private def planShortestPathsWithFallback(inner: LogicalPlan,
                                            shortestPath: ShortestPathPattern,
                                            predicates: Seq[Expression],
                                            safePredicates: Seq[Expression],
                                            unsafePredicates: Seq[Expression],
                                            queryGraph: QueryGraph,
                                            interestingOrder: InterestingOrder,
                                            context: LogicalPlanningContext) = {
    // create warning for planning a shortest path fallback
    context.notificationLogger.log(ExhaustiveShortestPathForbiddenNotification(shortestPath.expr.position))

    val lpp = context.logicalPlanProducer

    // Plan FindShortestPaths within an Apply with an Optional so we get null rows when
    // the graph algorithm does not find anything (left-hand-side)
    val lhsArgument = lpp.planArgumentFrom(inner, context)
    val lhsSp = lpp.planShortestPath(lhsArgument, shortestPath, predicates, withFallBack = true,
                                     disallowSameNode = context.errorIfShortestPathHasCommonNodesAtRuntime, interestingOrder, context = context)
    val lhsOption = lpp.planOptional(lhsSp, lhsArgument.availableSymbols, context)
    val lhs = lpp.planApply(inner, lhsOption, context)

    val rhsArgument = lpp.planArgumentFrom(lhs, context)

    val rhs = if (context.errorIfShortestPathFallbackUsedAtRuntime) {
      lpp.planError(rhsArgument, new ExhaustiveShortestPathForbiddenException, context)
    } else {
      buildPlanShortestPathsFallbackPlans(shortestPath, rhsArgument, predicates, queryGraph, context)
    }

    // We have to force the plan to solve what we actually solve
    val solved = context.planningAttributes.solveds.get(inner.id).asSinglePlannerQuery.amendQueryGraph(_.addShortestPath(shortestPath).addPredicates(predicates: _*))

    lpp.planAntiConditionalApply(lhs, rhs, Seq(shortestPath.name.get), context, Some(solved))
  }

  private def buildPlanShortestPathsFallbackPlans(shortestPath: ShortestPathPattern,
                                                  rhsArgument: LogicalPlan,
                                                  predicates: Seq[Expression],
                                                  queryGraph: QueryGraph,
                                                  context: LogicalPlanningContext): LogicalPlan = {
    // TODO: Decide the best from and to based on degree (generate two alternative plans and let planner decide)
    // (or do bidirectional var length expand)
    val pattern = shortestPath.rel
    val from = pattern.left
    val lpp = context.logicalPlanProducer

    // We assume there is always a path name (either explicit or auto-generated)
    val pathName = shortestPath.name.get

    // Plan a fallback branch using VarExpand(Into) (right-hand-side)
    val rhsVarExpand = expandSolverStep.planSinglePatternSide(queryGraph, pattern, rhsArgument, from, InterestingOrder.empty, context)
      .getOrElse(throw new InternalException("Expected the nodes needed for this expansion to exist"))

    // Projection with path
    val map = Map(pathName -> createPathExpression(shortestPath.expr.element))
    val rhsProjection = lpp.planRegularProjection(rhsVarExpand, map, map, context)

    // Filter using predicates
    val rhsFiltered = context.logicalPlanProducer.planSelection(rhsProjection, predicates, InterestingOrder.empty, context)

    // Plan Sort and Limit
    val pos = shortestPath.expr.position
    val pathVariable = Variable(pathName)(pos)
    val lengthOfPath = FunctionInvocation(FunctionName(Length.name)(pos), pathVariable)(pos)
    val columnName = FreshIdNameGenerator.name(pos)

    val rhsProjMap = Map(columnName -> lengthOfPath)
    val rhsProjected = lpp.planRegularProjection(rhsFiltered, rhsProjMap, rhsProjMap, context)
    val sortDescription = Seq(Ascending(columnName))
    val sorted = lpp.planSort(rhsProjected, sortDescription, ProvidedOrder.empty, InterestingOrder.empty, context)
    val ties = if (shortestPath.single) DoNotIncludeTies else IncludeTies
    val one = SignedDecimalIntegerLiteral("1")(pos)
    lpp.planLimit(sorted, one, one, ties, context)
  }
}
