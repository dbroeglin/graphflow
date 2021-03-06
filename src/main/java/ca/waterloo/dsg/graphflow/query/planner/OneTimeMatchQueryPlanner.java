package ca.waterloo.dsg.graphflow.query.planner;

import ca.waterloo.dsg.graphflow.graph.Graph.Direction;
import ca.waterloo.dsg.graphflow.graph.TypeAndPropertyKeyStore;
import ca.waterloo.dsg.graphflow.query.operator.AbstractOperator;
import ca.waterloo.dsg.graphflow.query.operator.EdgeIdResolver;
import ca.waterloo.dsg.graphflow.query.operator.EdgeIdResolver.SourceDestinationIndexAndType;
import ca.waterloo.dsg.graphflow.query.operator.EdgeOrVertexPropertyDescriptor;
import ca.waterloo.dsg.graphflow.query.operator.EdgeOrVertexPropertyDescriptor.DescriptorType;
import ca.waterloo.dsg.graphflow.query.operator.Extend;
import ca.waterloo.dsg.graphflow.query.operator.Filter;
import ca.waterloo.dsg.graphflow.query.operator.GroupByAndAggregate;
import ca.waterloo.dsg.graphflow.query.operator.InMemoryOutputSink;
import ca.waterloo.dsg.graphflow.query.operator.Projection;
import ca.waterloo.dsg.graphflow.query.operator.PropertyResolver;
import ca.waterloo.dsg.graphflow.query.operator.Scan;
import ca.waterloo.dsg.graphflow.query.operator.aggregator.AbstractAggregator;
import ca.waterloo.dsg.graphflow.query.operator.aggregator.Average;
import ca.waterloo.dsg.graphflow.query.operator.aggregator.CountStar;
import ca.waterloo.dsg.graphflow.query.operator.aggregator.Max;
import ca.waterloo.dsg.graphflow.query.operator.aggregator.Min;
import ca.waterloo.dsg.graphflow.query.operator.aggregator.Sum;
import ca.waterloo.dsg.graphflow.query.operator.filter.FilterPredicateFactory;
import ca.waterloo.dsg.graphflow.query.operator.genericjoin.EdgeIntersectionRule;
import ca.waterloo.dsg.graphflow.query.operator.genericjoin.StageOperator;
import ca.waterloo.dsg.graphflow.query.operator.sinks.OutputSink;
import ca.waterloo.dsg.graphflow.query.plans.OneTimeMatchQueryPlan;
import ca.waterloo.dsg.graphflow.query.plans.QueryPlan;
import ca.waterloo.dsg.graphflow.query.structuredquery.QueryAggregation;
import ca.waterloo.dsg.graphflow.query.structuredquery.QueryGraph;
import ca.waterloo.dsg.graphflow.query.structuredquery.QueryPropertyPredicate;
import ca.waterloo.dsg.graphflow.query.structuredquery.QueryRelation;
import ca.waterloo.dsg.graphflow.query.structuredquery.StructuredQuery;
import ca.waterloo.dsg.graphflow.query.validators.MatchQueryValidator;
import org.antlr.v4.runtime.misc.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Create a {@code QueryPlan} for the MATCH operation.
 */
public class OneTimeMatchQueryPlanner extends AbstractQueryPlanner {

    private static final Logger logger = LogManager.getLogger(OneTimeMatchQueryPlanner.class);
    protected OutputSink outputSink;
    QueryGraph queryGraph = new QueryGraph();
    private TypeAndPropertyKeyStore typeAndPropertyKeyStore = TypeAndPropertyKeyStore.getInstance();

    /**
     * @param structuredQuery query to plan.
     * @param outputSink output sink to be added to the final operator in the plan.
     */
    public OneTimeMatchQueryPlanner(StructuredQuery structuredQuery, OutputSink outputSink) {
        super(structuredQuery);
        this.outputSink = outputSink;
        queryGraph = new MatchQueryValidator(structuredQuery).validateQueryAndGetQueryGraph();
    }

    /**
     * Selects the order of the rest of the variables, considering the following properties: 1)
     * Select the variable with highest number of connections to the already covered variables. 2)
     * Break tie from (1) by selecting the variable with highest degree. 3) Break tie from (2) by
     * selecting the variable with lowest lexicographical rank.
     */
    void orderRemainingVariables(List<String> orderedVariables) {
        int initialSize = orderedVariables.size();
        Set<String> visitedVariables = new HashSet<>();
        visitedVariables.addAll(orderedVariables);
        for (int i = 0; i < queryGraph.getTotalNumberOfVariables() - initialSize; i++) {
            String selectedVariable = "";
            int highestConnectionsCount = -1;
            int highestDegreeCount = -1;
            for (String coveredVariable : orderedVariables) {
                // Loop for all neighboring vertices of the already covered vertices.
                for (String neighborVariable : queryGraph.getAllNeighborVariables(
                    coveredVariable)) {
                    // Skip vertices which have already been covered.
                    if (visitedVariables.contains(neighborVariable)) {
                        continue;
                    }
                    int variableDegree = queryGraph.getNumberOfAdjacentRelations(neighborVariable);
                    // Calculate the number of connections of the new variable to the already
                    // covered vertices.
                    int connectionsCount = 0;
                    for (String alreadyCoveredVariable : orderedVariables) {
                        if (queryGraph.containsRelation(neighborVariable, alreadyCoveredVariable)) {
                            connectionsCount++;
                        }
                    }
                    // See if the new {@code neighbourVariable} should be chosen first.
                    if ((connectionsCount > highestConnectionsCount)) {
                        // Rule (1).
                        selectedVariable = neighborVariable;
                        highestDegreeCount = variableDegree;
                        highestConnectionsCount = connectionsCount;
                    } else if (connectionsCount == highestConnectionsCount) {
                        if (variableDegree > highestDegreeCount) {
                            // Rule (2).
                            selectedVariable = neighborVariable;
                            highestDegreeCount = variableDegree;
                            highestConnectionsCount = connectionsCount;
                        } else if ((variableDegree == highestDegreeCount) &&
                            (neighborVariable.compareTo(selectedVariable) < 0)) {
                            // Rule (3).
                            selectedVariable = neighborVariable;
                            highestDegreeCount = variableDegree;
                            highestConnectionsCount = connectionsCount;
                        }
                    }
                }
            }
            orderedVariables.add(selectedVariable);
            visitedVariables.add(selectedVariable);
        }
    }

    /**
     * Creates a one time {@code MATCH} query plan for the given {@code structuredQuery}.
     *
     * @return A {@link QueryPlan} encapsulating an {@link OneTimeMatchQueryPlan}.
     */
    public QueryPlan plan() {
        OneTimeMatchQueryPlan plan = new OneTimeMatchQueryPlan();
        List<String> orderedVariables = new ArrayList<>();
        /*
          Find the first variable, considering the following properties:
          1) Select the variable with the highest degree.
          2) Break tie from (1) by selecting the variable with the lowest lexicographical rank.
         */
        int highestDegreeCount = -1;
        String variableWithHighestDegree = "";
        for (String variable : queryGraph.getAllVariableNames()) {
            int variableDegree = queryGraph.getNumberOfAdjacentRelations(variable);
            if (variableDegree > highestDegreeCount) {
                // Rule (1).
                highestDegreeCount = variableDegree;
                variableWithHighestDegree = variable;
            } else if ((variableDegree == highestDegreeCount) &&
                (variable.compareTo(variableWithHighestDegree) < 0)) {
                // Rule (2).
                highestDegreeCount = variableDegree;
                variableWithHighestDegree = variable;
            }
        }
        orderedVariables.add(variableWithHighestDegree);
        // Order the rest of the variables.
        orderRemainingVariables(orderedVariables);
        // Store variable ordering in {@link OneTimeMatchQueryPlan}
        plan.setOrderedVariables(orderedVariables);
        // Finally, create the plan.
        StageOperator previousStageOperator;
        StageOperator currentStageOperator = null;
        String fromVertexTypeFilter = null;
        String toVertexTypeFilter = null;
        // Start from the second variable to create the first stage.
        for (int i = 1; i < orderedVariables.size(); i++) {
            String variableForCurrentStage = orderedVariables.get(i);
            List<EdgeIntersectionRule> stage = new ArrayList<>();
            // Loop across all variables covered in the previous stages.
            for (int j = 0; j < i; j++) {
                String variableFromPreviousStage = orderedVariables.get(j);
                if (queryGraph.containsRelation(variableFromPreviousStage,
                    variableForCurrentStage)) {
                    for (QueryRelation queryRelation : queryGraph.getAdjacentRelations(
                        variableFromPreviousStage, variableForCurrentStage)) {
                        Direction direction = queryRelation.getFromQueryVariable().
                            getVariableName().equals(variableFromPreviousStage) ?
                            Direction.FORWARD : Direction.BACKWARD;
                        fromVertexTypeFilter = queryRelation.getFromQueryVariable().
                            getVariableType();
                        toVertexTypeFilter = queryRelation.getToQueryVariable().getVariableType();
                        stage.add(new EdgeIntersectionRule(j, direction, TypeAndPropertyKeyStore.
                            getInstance().mapStringTypeToShort(queryRelation.getRelationType())));
                    }
                }
            }
            if (1 == i) {
                currentStageOperator = new Scan(stage, TypeAndPropertyKeyStore.getInstance().
                    mapStringTypeToShort(fromVertexTypeFilter), TypeAndPropertyKeyStore.
                    getInstance().mapStringTypeToShort(toVertexTypeFilter));
                plan.setFirstOperator(currentStageOperator);
            } else {
                previousStageOperator = currentStageOperator;
                currentStageOperator = new Extend(stage, TypeAndPropertyKeyStore.getInstance().
                    mapStringTypeToShort(toVertexTypeFilter));
                previousStageOperator.nextOperator = currentStageOperator;
            }
        }
        currentStageOperator.setMatchQueryOutput(plan.getFirstOperator().getMatchQueryResultType(),
            getVariableIndicesMap(orderedVariables));
        currentStageOperator.nextOperator = getNextOperator(orderedVariables);

        logger.info("**********Printing OneTimeMatchQueryPlan**********");
        logger.info("Plan: \n" + plan.getHumanReadablePlan());
        return plan;
    }

    Map<String, Integer> getVariableIndicesMap(List<String> orderedVariables) {
        Map<String, Integer> variableIndicesMap = new HashMap<>();
        for (int i = 0; i < orderedVariables.size(); ++i) {
            variableIndicesMap.put(orderedVariables.get(i), i);
        }
        return variableIndicesMap;
    }

    /**
     * Adds to {@link OneTimeMatchQueryPlan} the next plan which can be one of the following. The
     * op1->op2 below indicates that operator op1 appends results to operator op2.
     * {@link EdgeIdResolver} is only added when the WHERE or RETURN clauses contain edge variables.
     * <ul>
     * <li> {@link PropertyResolver}->{@link #outputSink}.
     * <li> {@link Filter}->{@link PropertyResolver}->{@link #outputSink}.
     * <li> {@link Projection}->({@link PropertyResolver} OR {@link GroupByAndAggregate})->
     * {@link #outputSink}.
     * <li> {@link EdgeIdResolver}->{@link Projection}->({@link PropertyResolver} OR
     * {@link GroupByAndAggregate})->{@link #outputSink}.
     * <li> ({@link EdgeIdResolver}->){@link Filter}->{@link Projection}->({@link PropertyResolver}
     * OR {@link GroupByAndAggregate})->{@link #outputSink}.
     * </ul>
     */
    AbstractOperator getNextOperator(
        List<String> orderedVertexVariablesBeforeProjection) {
        AbstractOperator nextOperator;
        List<String> orderedEdgeVariablesAfterProjection = new ArrayList<>();
        Map<String, Integer> orderedVariableIndexMapBeforeProjection =
            getOrderedVariableIndexMap(orderedVertexVariablesBeforeProjection);
        List<String> orderedEdgeVariablesForFiltersAndProjection = new ArrayList<>();
        // If there is no RETURN clause specified, we append a PropertyResolver->OutputSink to
        // GJExecutor. The PropertyResolver only returns the ID of each vertex matched.
        if (structuredQuery.getReturnVariables().isEmpty() &&
            structuredQuery.getReturnVariablePropertyPairs().isEmpty() &&
            structuredQuery.getQueryAggregations().isEmpty()) {
            nextOperator = getIdentityPropertyResolver(orderedVertexVariablesBeforeProjection);
            if (outputSink instanceof InMemoryOutputSink) {
                ((InMemoryOutputSink) outputSink).setHeader(orderedVertexVariablesBeforeProjection.
                    toString());
            }
        } else {
            // Otherwise we first project onto the set of attributes mentioned in the RETURN
            // clause. And then resolve the variables in the RETURN clause with properties
            // (if any).

            // First compute the set of variables that will be in the output after the projection.
            // Note: We do order the variables to return implicitly below. Specifically, below we
            // disregard the order in which the user listed the variables and variableProperties
            // in the RETURN statement and first order the return variables and then
            // variableProperties.
            Pair<List<String>, List<String>> orderedVertexAndEdgeVariables =
                getOrderedVertexVariablesAfterProjectionAndOrderedEdgeVariables();
            List<String> orderedVertexVariablesAfterProjection = orderedVertexAndEdgeVariables.a;
            orderedEdgeVariablesAfterProjection = orderedVertexAndEdgeVariables.b;

            // First construct the PropertyResolver or GroupByAndAggregate which will be the
            // operator following Projection
            AbstractOperator projectionsNextOperator;
            Map<String, Integer> vertexVariableOrderIndexMapAfterProjection =
                getOrderedVariableIndexMap(orderedVertexVariablesAfterProjection);
            Map<String, Integer> edgeVariableOrderIndexMap = getOrderedVariableIndexMap(
                orderedEdgeVariablesAfterProjection);
            orderedEdgeVariablesForFiltersAndProjection = orderedEdgeVariablesAfterProjection;
            if (structuredQuery.getQueryAggregations().isEmpty()) {
                projectionsNextOperator = new PropertyResolver(outputSink,
                    constructEdgeOrVertexPropertyDescriptorList(
                        vertexVariableOrderIndexMapAfterProjection, edgeVariableOrderIndexMap));
            } else {
                projectionsNextOperator = constructGroupByAndAggregate(
                    vertexVariableOrderIndexMapAfterProjection, edgeVariableOrderIndexMap);
            }

            // Then construct the Projection.
            logger.info("Appending Projection->PropertyResolver->OutputSink.");
            List<Integer> vertexIndicesToProject = new ArrayList<>();
            for (String returnVariable : orderedVertexVariablesAfterProjection) {
                vertexIndicesToProject.add(orderedVariableIndexMapBeforeProjection.get(
                    returnVariable));
            }
            nextOperator = new Projection(projectionsNextOperator, vertexIndicesToProject);
        }

        // Construct the {@code Filter} operator if needed.
        if (!structuredQuery.getQueryPropertyPredicates().isEmpty()) {
            orderedEdgeVariablesForFiltersAndProjection =
                getOrderedEdgeVariablesInFiltersAndProjections(orderedEdgeVariablesAfterProjection);
            Map<String, Integer> orderedEdgeIndexMap = getOrderedVariableIndexMap(
                orderedEdgeVariablesForFiltersAndProjection);
            nextOperator = constructFilter(orderedVariableIndexMapBeforeProjection,
                orderedEdgeIndexMap, nextOperator);
        }

        // Finally construct the EdgeIdResolver if needed. Uses the ordered edgeId variables used
        // by {@code Filter} and/or {@code Projection}/{@code GroupByAndAggregate}.
        if (orderedEdgeVariablesForFiltersAndProjection.isEmpty()) {
            return nextOperator;
        } else {
            return constructEdgeIdResolver(orderedEdgeVariablesForFiltersAndProjection,
                orderedVariableIndexMapBeforeProjection, nextOperator);
        }
    }

    AbstractOperator constructFilter(Map<String, Integer>
        vertexVariableOrderIndexMapBeforeProjection, Map<String, Integer>
        edgeVariableOrderIndexMap, AbstractOperator nextOperator) {
        List<EdgeOrVertexPropertyDescriptor> edgeOrVertexPropertyDescriptors = new ArrayList<>();
        // The {@code descriptorIndexMap} holds the position of the descriptor for a given
        // variable in {@code edgeOrVertexPropertyDescriptors} list. A map is used to prevent
        // duplicate descriptors in the list.
        Map<String, Integer> descriptorIndexMap = new HashMap<>();
        Predicate<String[]> predicate = null;
        for (QueryPropertyPredicate queryPropertyPredicate : structuredQuery.
            getQueryPropertyPredicates()) {
            Pair<String, String> variable1 = queryPropertyPredicate.getLeftOperand();
            if (null == descriptorIndexMap.get(variable1.a + '.' + variable1.b)) {
                descriptorIndexMap.put(variable1.a + '.' + variable1.b,
                    edgeOrVertexPropertyDescriptors.size());
                edgeOrVertexPropertyDescriptors.add(getEdgeOrVertexPropertyDescriptor(
                    vertexVariableOrderIndexMapBeforeProjection, edgeVariableOrderIndexMap,
                    variable1.a, typeAndPropertyKeyStore.mapStringPropertyKeyToShort(variable1.b)));
            }
            Pair<String, String> variable2 = queryPropertyPredicate.getRightOperand();
            if (null != variable2 && null == descriptorIndexMap.get(variable2.a + '.' +
                variable2.b)) {
                descriptorIndexMap.put(variable2.a + '.' + variable2.b,
                    edgeOrVertexPropertyDescriptors.size());
                edgeOrVertexPropertyDescriptors.add(getEdgeOrVertexPropertyDescriptor(
                    vertexVariableOrderIndexMapBeforeProjection, edgeVariableOrderIndexMap,
                    variable2.a, typeAndPropertyKeyStore.mapStringPropertyKeyToShort(variable2.b)));
            }
            int variable2Index = (variable2 != null) ? descriptorIndexMap.get(variable2.a + '.' +
                variable2.b) : -1;
            if (null == predicate) {
                // Assign the first predicate to {@code Predicate}.
                predicate = FilterPredicateFactory.getFilterPredicate(queryPropertyPredicate,
                    descriptorIndexMap.get(variable1.a + '.' + variable1.b), variable2Index);
            } else {
                // Create a composite {@link Predicate} with subsequent predicates by calling
                // {@code and} on the existing {@code predicate}.
                predicate = predicate.and(FilterPredicateFactory.getFilterPredicate(
                    queryPropertyPredicate, descriptorIndexMap.get(variable1.a + '.' + variable1.b),
                    variable2Index));
            }
        }
        return new Filter(nextOperator, predicate, new ArrayList<>(edgeOrVertexPropertyDescriptors),
            structuredQuery.getQueryPropertyPredicates());
    }

    private List<String> getOrderedEdgeVariablesInFiltersAndProjections(List<String>
        orderedEdgesFromProjection) {
        Set<String> resolvedEdges = new HashSet<>();
        List<String> orderedEdgeVariablesInFiltersAndProjections = new ArrayList<>(
            orderedEdgesFromProjection);
        resolvedEdges.addAll(orderedEdgesFromProjection);
        for (QueryPropertyPredicate queryPropertyPredicate : structuredQuery.
            getQueryPropertyPredicates()) {
            String operandVariable1 = queryPropertyPredicate.getLeftOperand().a;
            String operandVariable2 = queryPropertyPredicate.getRightOperand() != null ?
                queryPropertyPredicate.getRightOperand().a : null;
            checkIfEdgeVariableNeedsToBeResolved(operandVariable1, resolvedEdges,
                orderedEdgeVariablesInFiltersAndProjections);
            checkIfEdgeVariableNeedsToBeResolved(operandVariable2, resolvedEdges,
                orderedEdgeVariablesInFiltersAndProjections);
        }
        return orderedEdgeVariablesInFiltersAndProjections;
    }

    private void checkIfEdgeVariableNeedsToBeResolved(String operandVariable, Set<String>
        resolvedEdges, List<String> orderedEdgeVariablesInFiltersAndProjections) {
        if (null != operandVariable && queryGraph.getAllRelationNames().contains(operandVariable) &&
            !resolvedEdges.contains(operandVariable)) {
            resolvedEdges.add(operandVariable);
            orderedEdgeVariablesInFiltersAndProjections.add(operandVariable);
        }
    }

    private AbstractOperator constructGroupByAndAggregate(
        Map<String, Integer> vertexVariableOrderIndexMapAfterProjection,
        Map<String, Integer> edgeVariableOrderIndexMap) {
        List<EdgeOrVertexPropertyDescriptor> valuesToGroupBy =
            constructEdgeOrVertexPropertyDescriptorList(vertexVariableOrderIndexMapAfterProjection,
                edgeVariableOrderIndexMap);
        List<Pair<EdgeOrVertexPropertyDescriptor, AbstractAggregator>> valueAggregatorPairs =
            new ArrayList<>();
        for (QueryAggregation queryAggregation : structuredQuery.getQueryAggregations()) {
            AbstractAggregator aggregator;
            switch (queryAggregation.getAggregationFunction()) {
                case AVG:
                    aggregator = new Average();
                    break;
                case COUNT_STAR:
                    aggregator = new CountStar();
                    break;
                case MAX:
                    aggregator = new Max();
                    break;
                case MIN:
                    aggregator = new Min();
                    break;
                case SUM:
                    aggregator = new Sum();
                    break;
                default:
                    logger.warn("Unknown aggregation function:" + queryAggregation.
                        getAggregationFunction() + ". This should have been caught and handled " +
                        "when parsing the query.");
                    throw new IllegalArgumentException("Unknown aggregation function:"
                        + queryAggregation.getAggregationFunction());
            }

            // For the COUNT_STAR aggregator, that does not have variable or variablePropertyPair we
            // give it a dummy EdgeOrVertexPropertyDescriptor.
            EdgeOrVertexPropertyDescriptor descriptor =
                EdgeOrVertexPropertyDescriptor.COUNTSTAR_DUMMY_DESCRIPTOR;
            if (null != queryAggregation.getVariable()) {
                getEdgeOrVertexPropertyDescriptor(vertexVariableOrderIndexMapAfterProjection,
                    edgeVariableOrderIndexMap, queryAggregation.getVariable(),
                    (short) -1 /* No property key. Use the vertex or edge ID. */);
            } else if (null != queryAggregation.getVariablePropertyPair()) {
                Pair<String, String> variablePropertyPair =
                    queryAggregation.getVariablePropertyPair();
                descriptor = getEdgeOrVertexPropertyDescriptor(
                    vertexVariableOrderIndexMapAfterProjection, edgeVariableOrderIndexMap,
                    variablePropertyPair.a, typeAndPropertyKeyStore.mapStringPropertyKeyToShort(
                        variablePropertyPair.b));
            }
            valueAggregatorPairs.add(new Pair<>(descriptor, aggregator));
        }
        return new GroupByAndAggregate(outputSink, valuesToGroupBy, valueAggregatorPairs);
    }

    AbstractOperator constructEdgeIdResolver(List<String> orderedEdgeVariables,
        Map<String, Integer> orderedVariableIndexMapBeforeProjection,
        AbstractOperator nextOperator) {
        List<SourceDestinationIndexAndType> srcDstVertexIndicesAndTypes = new ArrayList<>();
        for (String orderedEdgeVariable : orderedEdgeVariables) {
            QueryRelation queryRelation = queryGraph.getRelationFromRelationName(
                orderedEdgeVariable);
            if (null == queryRelation) {
                logger.warn("QueryRelation with given edgeVariableToResolve is null: "
                    + orderedEdgeVariable + ". This should never happen. Sanity checks"
                    + " should have caught this.");
                continue;
            }
            int sourceIndex = orderedVariableIndexMapBeforeProjection.get(
                queryRelation.getFromQueryVariable().getVariableName());
            int destinationIndex = orderedVariableIndexMapBeforeProjection.get(
                queryRelation.getToQueryVariable().getVariableName());
            srcDstVertexIndicesAndTypes.add(new SourceDestinationIndexAndType(sourceIndex,
                destinationIndex, typeAndPropertyKeyStore.mapStringTypeToShort(queryRelation.
                getRelationType())));
        }
        return new EdgeIdResolver(nextOperator, srcDstVertexIndicesAndTypes);
    }

    private List<EdgeOrVertexPropertyDescriptor> constructEdgeOrVertexPropertyDescriptorList(
        Map<String, Integer> vertexVariableOrderIndexMapAfterProjection,
        Map<String, Integer> edgeVariableOrderIndexMap) {
        List<EdgeOrVertexPropertyDescriptor> edgeOrVertexPropertyIndices = new ArrayList<>();
        for (String returnVariable : structuredQuery.getReturnVariables()) {
            edgeOrVertexPropertyIndices.add(getEdgeOrVertexPropertyDescriptor(
                vertexVariableOrderIndexMapAfterProjection, edgeVariableOrderIndexMap,
                returnVariable, (short) -1 /* No property key. Use the vertex or edge ID. */));
        }
        for (Pair<String, String> returnVariablePropertyPair :
            structuredQuery.getReturnVariablePropertyPairs()) {
            edgeOrVertexPropertyIndices.add(getEdgeOrVertexPropertyDescriptor(
                vertexVariableOrderIndexMapAfterProjection, edgeVariableOrderIndexMap,
                returnVariablePropertyPair.a, typeAndPropertyKeyStore.mapStringPropertyKeyToShort(
                    returnVariablePropertyPair.b)));
        }
        return edgeOrVertexPropertyIndices;
    }

    private EdgeOrVertexPropertyDescriptor getEdgeOrVertexPropertyDescriptor(
        Map<String, Integer> vertexVariableOrderIndexMapAfterProjection,
        Map<String, Integer> edgeVariableOrderIndexMap, String returnVariable,
        short shortPropertyKey) {
        if (vertexVariableOrderIndexMapAfterProjection.containsKey(returnVariable)) {
            return new EdgeOrVertexPropertyDescriptor(
                shortPropertyKey >= 0 ? DescriptorType.VERTEX_PROPERTY : DescriptorType.VERTEX_ID,
                vertexVariableOrderIndexMapAfterProjection.get(returnVariable), shortPropertyKey);
        } else if (edgeVariableOrderIndexMap.containsKey(returnVariable)) {
            return new EdgeOrVertexPropertyDescriptor(
                shortPropertyKey >= 0 ? DescriptorType.EDGE_PROPERTY : DescriptorType.EDGE_ID,
                edgeVariableOrderIndexMap.get(returnVariable), shortPropertyKey);
        } else {
            logger.warn("ERROR: The return variable in variablePropertyPair always has to "
                + "exist either in vertexVariableOrderIndexMapAfterProjection or "
                + "edgeOrVertexPropertyIndices.");
            return null;
        }
    }

    private Pair<List<String>, List<String>>
    getOrderedVertexVariablesAfterProjectionAndOrderedEdgeVariables() {
        List<String> orderedVertexVariablesAfterProjection = new ArrayList<>();
        Set<String> variablesToProjectSet = new HashSet<>();
        List<String> orderedEdgeVariables = new ArrayList<>();
        Set<String> edgeVariablesToResolve = new HashSet<>();
        for (String returnVariable : structuredQuery.getReturnVariables()) {
            addVariableToOrderedVertexOrEdgeVariableList(orderedVertexVariablesAfterProjection,
                variablesToProjectSet, orderedEdgeVariables, edgeVariablesToResolve,
                returnVariable);
        }
        for (Pair<String, String> returnVariablePropertyPair :
            structuredQuery.getReturnVariablePropertyPairs()) {
            addVariableToOrderedVertexOrEdgeVariableList(orderedVertexVariablesAfterProjection,
                variablesToProjectSet, orderedEdgeVariables, edgeVariablesToResolve,
                returnVariablePropertyPair.a);
        }

        for (QueryAggregation queryAggregation : structuredQuery.getQueryAggregations()) {
            if (null != queryAggregation.getVariable()) {
                addVariableToOrderedVertexOrEdgeVariableList(orderedVertexVariablesAfterProjection,
                    variablesToProjectSet, orderedEdgeVariables, edgeVariablesToResolve,
                    queryAggregation.getVariable());
            } else if (null != queryAggregation.getVariablePropertyPair()) {
                addVariableToOrderedVertexOrEdgeVariableList(orderedVertexVariablesAfterProjection,
                    variablesToProjectSet, orderedEdgeVariables, edgeVariablesToResolve,
                    queryAggregation.getVariablePropertyPair().a);
            }
        }

        return new Pair<>(orderedVertexVariablesAfterProjection, orderedEdgeVariables);
    }

    private void addVariableToOrderedVertexOrEdgeVariableList(
        List<String> orderedVertexVariablesAfterProjection, Set<String> variablesToProjectSet,
        List<String> orderedEdgeVariables, Set<String> edgeVariablesToResolve,
        String returnVariable) {
        if (queryGraph.getAllVariableNames().contains(returnVariable)) {
            // returnVariable is a vertex variable
            if (!variablesToProjectSet.contains(returnVariable)) {
                variablesToProjectSet.add(returnVariable);
                orderedVertexVariablesAfterProjection.add(returnVariable);
            }
        } else {
            // returnVariable is an edge variable
            if (!edgeVariablesToResolve.contains(returnVariable)) {
                edgeVariablesToResolve.add(returnVariable);
                orderedEdgeVariables.add(returnVariable);
            }
        }
    }

    private PropertyResolver getIdentityPropertyResolver(
        List<String> orderedVertexVariablesBeforeProjection) {
        List<EdgeOrVertexPropertyDescriptor> edgeOrVertexPropertyIndices = new ArrayList<>();
        for (int i = 0; i < orderedVertexVariablesBeforeProjection.size(); ++i) {
            // Since the vertex Ids in the prefixes will be ordered we just pass in i as the index
            // below.
            edgeOrVertexPropertyIndices.add(new EdgeOrVertexPropertyDescriptor(
                DescriptorType.VERTEX_ID, i, (short) -1 /* No type. just return the vertex ID. */));
        }
        logger.info("Appending PropertyResolver->OutputSink.");
        return new PropertyResolver(outputSink, edgeOrVertexPropertyIndices);
    }

    Map<String, Integer> getOrderedVariableIndexMap(List<String> orderedVariables) {
        Map<String, Integer> variableOrderIndexMapBeforeProjection = new HashMap<>();
        for (int i = 0; i < orderedVariables.size(); ++i) {
            variableOrderIndexMapBeforeProjection.put(orderedVariables.get(i), i);
        }
        return variableOrderIndexMapBeforeProjection;
    }
}
