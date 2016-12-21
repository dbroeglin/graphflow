package ca.waterloo.dsg.graphflow.query.planner;

import ca.waterloo.dsg.graphflow.graph.Graph.Direction;
import ca.waterloo.dsg.graphflow.graph.Graph.GraphVersion;
import ca.waterloo.dsg.graphflow.graph.TypeStore;
import ca.waterloo.dsg.graphflow.outputsink.OutputSink;
import ca.waterloo.dsg.graphflow.query.executors.GenericJoinIntersectionRule;
import ca.waterloo.dsg.graphflow.query.plans.ContinuousMatchQueryPlan;
import ca.waterloo.dsg.graphflow.query.plans.OneTimeMatchQueryPlan;
import ca.waterloo.dsg.graphflow.query.plans.QueryPlan;
import ca.waterloo.dsg.graphflow.query.utils.QueryEdge;
import ca.waterloo.dsg.graphflow.query.utils.StructuredQuery;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Creates a {@code ContinuousMatchQueryPlan} to continuously find changes to MATCH query
 * results specified in the limited Cypher language Graphflow supports.
 */
public class ContinuousMatchQueryPlanner extends OneTimeMatchQueryPlanner {

    private OutputSink outputSink;

    public ContinuousMatchQueryPlanner(StructuredQuery structuredQuery, OutputSink outputSink) {
        super(structuredQuery);
        this.outputSink = outputSink;
    }

    /**
     * Creates a continuous {@code MATCH} query plan for the given {@code structuredQuery}.
     *
     * @return A {@link QueryPlan} encapsulating a {@link ContinuousMatchQueryPlan}.
     */
    @Override
    public QueryPlan plan() {
        ContinuousMatchQueryPlan continuousMatchQueryPlan = new ContinuousMatchQueryPlan(
            outputSink);
        // We construct as many delta queries as there are relations in the query graph. Let n be
        // the number of relations in the query graph. Then we have dQ1, dQ2, ..., dQn. Delta query
        // dQi consists of the following: (1) i-1 relations that use the {@code MERGED} version
        // of the graph (newly added edges + the permanent edges); (2) one relation that use only
        // the {@code DIFF_PLUS} or {@code DIFF_MINUS} versions of the graph (the newly added or
        // deleted edges). We refer to this relation as the diffRelation below; (3) n-i relations
        // that use the {@code PERMANENT} version of the graph.
        Set<QueryEdge> mergedRelations = new HashSet<>();
        Set<QueryEdge> permanentRelations = new HashSet<>(structuredQuery.getQueryEdges());
        for (QueryEdge diffRelation : structuredQuery.getQueryEdges()) {
            // The first two variables considered in each round will be the variables from the
            // delta relation.
            permanentRelations.remove(diffRelation);
            List<String> orderedVariables = new ArrayList<>();
            orderedVariables.add(diffRelation.getFromQueryVariable().getVariableId());
            orderedVariables.add(diffRelation.getToQueryVariable().getVariableId());
            super.orderRemainingVariables(orderedVariables);
            // Create the query plan using the ordering determined above.
            continuousMatchQueryPlan.addOneTimeMatchQueryPlan(addSingleQueryPlan(
                GraphVersion.DIFF_PLUS, orderedVariables, diffRelation, permanentRelations,
                mergedRelations));
            continuousMatchQueryPlan.addOneTimeMatchQueryPlan(addSingleQueryPlan(
                GraphVersion.DIFF_MINUS, orderedVariables, diffRelation, permanentRelations,
                mergedRelations));
            mergedRelations.add(diffRelation);
        }
        return continuousMatchQueryPlan;
    }

    /**
     * Returns the query plan for a single delta query in the {@code ContinuousMatchQueryPlan}.
     *
     * @param orderedVariables The order in which variables will be covered in the plan.
     * @param diffRelation The relation which will use the diff graph for a single delta query in
     * the {@code ContinuousMatchQueryPlan}.
     * @param permanentRelations The set of relations that uses the {@link GraphVersion#PERMANENT}
     * version of the graph.
     * @param mergedRelations The set of relations that uses the {@link GraphVersion#MERGED}
     * version of the graph.
     * @return OneTimeMatchQueryPlan A set of stages representing a single generic join query plan.
     */
    private OneTimeMatchQueryPlan addSingleQueryPlan(GraphVersion graphVersion,
        List<String> orderedVariables, QueryEdge diffRelation, Set<QueryEdge> permanentRelations,
        Set<QueryEdge> mergedRelations) {
        OneTimeMatchQueryPlan oneTimeMatchQueryPlan = new OneTimeMatchQueryPlan();
        List<GenericJoinIntersectionRule> stage;
        // Add the first stage. The first stage always starts with extending the diffRelation's
        // {@code fromVariable} to {@code toVariable} with the type on the relation.
        stage = new ArrayList<>();
        // {@code TypeStore#getShortIdOrAnyTypeIfNull()} will throw a {@code NoSuchElementException}
        // if the relation type {@code String} of {@code diffRelation} does not already exist in the
        // {@code TypeStore}.
        stage.add(new GenericJoinIntersectionRule(0, Direction.FORWARD, graphVersion,
            TypeStore.getInstance().getShortIdOrAnyTypeIfNull(diffRelation.getEdgeType())));
        oneTimeMatchQueryPlan.addStage(stage);
        // Add the other relations that are present between the diffRelation's
        // {@code fromVariable} to {@code toVariable}.
        for (QueryEdge queryEdge : queryGraph.getAdjacentEdges(orderedVariables.get(0),
            orderedVariables.get(1))) {
            if (QueryEdge.isSameAs(diffRelation, queryEdge)) {
                // This relation has been added as the {@code diffRelation}.
                continue;
            }
            addGenericJoinIntersectionRule(0, queryEdge.getDirection(), queryEdge,
                stage, permanentRelations, mergedRelations);
        }
        // Add the rest of the stages.
        for (int i = 2; i < orderedVariables.size(); i++) {
            String nextVariable = orderedVariables.get(i);
            // We add a new stage that consists of the following intersection rules. For each
            // relation that is between the {@code nextVariable} and one of the previously
            // {@code coveredVariable}, we add a new intersection rule. The direction of the
            // intersection rule is {@code FORWARD} if the relation is from {@code coveredVariable}
            // to {@code nextVariable), otherwise the direction is {@code BACKWARD}. This
            // is because we essentially extend prefixes from the {@code coveredVariable}s to the
            // {@code nextVariable}s. The type of the intersection rule is the type on the relation.
            stage = new ArrayList<>();
            for (int j = 0; j < i; j++) {
                String coveredVariable = orderedVariables.get(j);
                if (queryGraph.containsEdge(coveredVariable, nextVariable)) {
                    for (QueryEdge queryEdge : queryGraph.getAdjacentEdges(coveredVariable,
                        nextVariable)) {
                        addGenericJoinIntersectionRule(j, queryEdge.getDirection(), queryEdge,
                            stage, permanentRelations, mergedRelations);
                    }
                }
            }
            oneTimeMatchQueryPlan.addStage(stage);
        }
        return oneTimeMatchQueryPlan;
    }

    /**
     * Adds a {@code GenericJoinIntersectionRule} to the given stage with the given
     * {@code prefixIndex}, {@code direction} and the relation and variable type IDs, if the
     * {@code newRelation} exists in either {@code permanentRelations} or {@code mergedRelations}.
     *
     * @param prefixIndex Prefix index of the {@code GenericJoinIntersectionRule} to be created.
     * @param direction Direction from the covered variable to the variable under
     * consideration.
     * @param newRelation The relation for which the rule is being added.
     * @param stage The generic join stage to which the intersection rule will be added.
     * @param permanentRelations The set of relations that uses the {@link GraphVersion#PERMANENT}
     * version of the graph.
     * @param mergedRelations The set of relations that uses the {@link GraphVersion#MERGED}
     * version of the graph.
     */
    private void addGenericJoinIntersectionRule(int prefixIndex, Direction direction, QueryEdge
        newRelation,
        List<GenericJoinIntersectionRule> stage, Set<QueryEdge> permanentRelations,
        Set<QueryEdge> mergedRelations) {
        // Select the appropriate {@code GraphVersion} by checking for the existence of
        // {@code newRelation} in either {@code mergedRelations} or {@code mergedRelations}.
        // Because these sets contain relations in only the {@code FORWARD} direction, if the
        // direction of {@code newRelation} is {@code BACKWARD}, {@code fromVariable} and
        // {@code toVariable} needs to be reversed.
        GraphVersion version = null;
        String fromVariable;
        String toVariable;
        if (Direction.FORWARD == newRelation.getDirection()) {
            fromVariable = newRelation.getFromQueryVariable().getVariableId();
            toVariable = newRelation.getToQueryVariable().getVariableId();
        } else {
            fromVariable = newRelation.getToQueryVariable().getVariableId();
            toVariable = newRelation.getFromQueryVariable().getVariableId();
        }
        if (isRelationPresentInSet(fromVariable, toVariable, mergedRelations)) {
            version = GraphVersion.MERGED;
        } else if (isRelationPresentInSet(fromVariable, toVariable, permanentRelations)) {
            version = GraphVersion.PERMANENT;
        } else {
            throw new IllegalStateException("The new relation is not present in either " +
                "mergedRelations or permanentRelations");
        }
        // {@code TypeStore#getShortIdOrAnyTypeIfNull()} will throw a {@code NoSuchElementException}
        // if the relation type {@code String} of {@code newRelation} does not already exist in the
        // {@code TypeStore}.
        stage.add(new GenericJoinIntersectionRule(prefixIndex, direction, version,
            TypeStore.getInstance().getShortIdOrAnyTypeIfNull(newRelation.getEdgeType())));
    }

    /**
     * @param fromVariable The from variable.
     * @param toVariable The to variable.
     * @param queryRelations A set of {@link QueryEdge}s.
     * @return {@code true} if {@code fromVariable} and {@code toVariable} match the
     * corresponding values of any of the {@link QueryEdge} present in {@code queryRelations}.
     */
    private boolean isRelationPresentInSet(String fromVariable, String toVariable,
        Set<QueryEdge> queryRelations) {
        for (QueryEdge queryEdge : queryRelations) {
            if (Objects.equals(queryEdge.getFromQueryVariable().getVariableId(), fromVariable) &&
                Objects.equals(queryEdge.getToQueryVariable().getVariableId(), toVariable)) {
                return true;
            }
        }
        return false;
    }
}
