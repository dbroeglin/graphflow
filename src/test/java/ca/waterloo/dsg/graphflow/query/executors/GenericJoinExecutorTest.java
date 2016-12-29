package ca.waterloo.dsg.graphflow.query.executors;

import ca.waterloo.dsg.graphflow.TestUtils;
import ca.waterloo.dsg.graphflow.graph.Graph;
import ca.waterloo.dsg.graphflow.graph.TypeAndPropertyKeyStore;
import ca.waterloo.dsg.graphflow.outputsink.InMemoryOutputSink;
import ca.waterloo.dsg.graphflow.query.parser.StructuredQueryParser;
import ca.waterloo.dsg.graphflow.query.planner.OneTimeMatchQueryPlanner;
import ca.waterloo.dsg.graphflow.query.plans.OneTimeMatchQueryPlan;
import ca.waterloo.dsg.graphflow.query.structuredquery.StructuredQuery;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests {@code GenericJoinExecutor}.
 */
public class GenericJoinExecutorTest {

    /**
     * Tests the execution of a triangle MATCH query with no types.
     */
    @Test
    public void testProcessSimpleTriangleQuery() throws Exception {
        // Create a one time MATCH query plan for a simple triangle query with no types.
        StructuredQuery triangleStructuredQuery = new StructuredQueryParser().parse("MATCH " +
            "(a)->(b),(b)->(c),(c)->(a)");
        OneTimeMatchQueryPlan actualOneTimeMatchQueryPlan = (OneTimeMatchQueryPlan)
            new OneTimeMatchQueryPlanner(triangleStructuredQuery).plan();

        int[][] expectedMotifsAfterAdditions = {{0, 1, 3}, {1, 3, 0}, {1, 3, 4}, {3, 0, 1},
            {3, 4, 1}, {4, 1, 3}};
        int[][] expectedMotifsAfterDeletion = {{0, 1, 3}, {1, 3, 0}, {3, 0, 1}};

        assertSimpleMatchQueryOutput(actualOneTimeMatchQueryPlan, expectedMotifsAfterAdditions,
            expectedMotifsAfterDeletion);
    }

    /**
     * Tests the execution of a triangle MATCH query with types.
     */
    @Test
    public void testProcessTriangleQueryWithTypes() throws Exception {
        // Initialize the {@code TypeStore} with types used in the MATCH query.
        TypeAndPropertyKeyStore.getInstance().getTypeAsShortOrInsertIfDoesNotExist("FOLLOWS");
        TypeAndPropertyKeyStore.getInstance().getTypeAsShortOrInsertIfDoesNotExist("LIKES");
        // Create a one time MATCH query plan for a complex triangle query with multiple
        // relations between variable having different edge types.
        StructuredQuery triangleStructuredQuery = new StructuredQueryParser().parse("MATCH " +
            "(a)-[:FOLLOWS]->(b),(a)-[:LIKES]->(b),(b)-[:LIKES]->(a),(b)->(c),(c)->(b)," +
            "(c)-[:FOLLOWS]->(a)");
        OneTimeMatchQueryPlan actualOneTimeMatchQueryPlan = (OneTimeMatchQueryPlan)
            new OneTimeMatchQueryPlanner(triangleStructuredQuery).plan();

        int[][] expectedMotifsAfterAdditions = {{1, 0, 3}, {1, 4, 3}};
        int[][] expectedMotifsAfterDeletion = {{1, 4, 3}};

        assertComplexMatchQueryOutput(actualOneTimeMatchQueryPlan, expectedMotifsAfterAdditions,
            expectedMotifsAfterDeletion);
    }

    /**
     * Tests the execution of a square MATCH query with no types.
     */
    @Test
    public void testProcessSquareQuery() throws Exception {
        // Create a one time MATCH query plan for a simple square query with no types.
        StructuredQuery triangleStructuredQuery = new StructuredQueryParser().parse("MATCH " +
            "(a)->(b),(b)->(c),(c)->(d),(d)->(a)");
        OneTimeMatchQueryPlan actualOneTimeMatchQueryPlan = (OneTimeMatchQueryPlan)
            new OneTimeMatchQueryPlanner(triangleStructuredQuery).plan();

        int[][] expectedMotifsAfterAdditions = {{0, 1, 2, 3}, {1, 2, 3, 0}, {1, 2, 3, 4},
            {2, 3, 0, 1}, {2, 3, 4, 1}, {3, 0, 1, 2}, {3, 4, 1, 2}, {4, 1, 2, 3}};
        int[][] expectedMotifsAfterDeletion = {{0, 1, 2, 3}, {1, 2, 3, 0}, {2, 3, 0, 1},
            {3, 0, 1, 2}};

        assertSimpleMatchQueryOutput(actualOneTimeMatchQueryPlan, expectedMotifsAfterAdditions,
            expectedMotifsAfterDeletion);
    }

    private void assertSimpleMatchQueryOutput(OneTimeMatchQueryPlan actualOneTimeMatchQueryPlan,
        int[][] expectedMotifsAfterAdditions, int[][] expectedMotifsAfterDeletion) {

        InMemoryOutputSink outputSink;

        // Initialize a graph.
        Graph graph = new Graph();
        TestUtils.createEdgesPermanently(graph, "CREATE (0:Person)-[:FOLLOWS]->" +
            "(1:Person),(1:Person)-[:FOLLOWS]->(2:Person), (1:Person)-[:FOLLOWS]->(3:Person)," +
            "(2:Person)-[:FOLLOWS]->(3:Person), (3:Person)-[:FOLLOWS]->(4:Person)," +
            "(3:Person)-[:FOLLOWS]->(0:Person), (4:Person)-[:FOLLOWS]->(1:Person);");

        // Execute the query and test.
        outputSink = new InMemoryOutputSink();
        actualOneTimeMatchQueryPlan.execute(graph, outputSink);
        Assert.assertTrue(InMemoryOutputSink.isSameAs(outputSink, getInMemoryOutputSinkForMotifs(
            expectedMotifsAfterAdditions)));

        // Delete one of the edges.
        TestUtils.deleteEdgesPermanently(graph, "DELETE (4)->(1);");

        // Execute the query again and test.
        outputSink = new InMemoryOutputSink();
        actualOneTimeMatchQueryPlan.execute(graph, outputSink);
        Assert.assertTrue(InMemoryOutputSink.isSameAs(outputSink, getInMemoryOutputSinkForMotifs(
            expectedMotifsAfterDeletion)));
    }

    private void assertComplexMatchQueryOutput(OneTimeMatchQueryPlan actualOneTimeMatchQueryPlan,
        int[][] expectedMotifsAfterAdditions, int[][] expectedMotifsAfterDeletion) {

        InMemoryOutputSink outputSink;

        Graph graph = new Graph();
        TestUtils.createEdgesPermanently(graph, "CREATE (0:Person)-[:FOLLOWS]->" +
            "(1:Person),(0:Person)-[:LIKES]->(1:Person),(1:Person)-[:LIKES]->(0:Person)," +
            "(1:Person)-[:TAGGED]->(3:Person),(3:Person)-[:LIKES]->(1:Person)," +
            "(3:Person)-[:FOLLOWS]->(0:Person),(4:Person)-[:FOLLOWS]->(1:Person)," +
            "(4:Person)-[:LIKES]->(1:Person),(1:Person)-[:LIKES]->(4:Person)," +
            "(3:Person)-[:FOLLOWS]->(4:Person);");

        // Execute the query and test.
        outputSink = new InMemoryOutputSink();
        actualOneTimeMatchQueryPlan.execute(graph, outputSink);
        Assert.assertTrue(InMemoryOutputSink.isSameAs(outputSink, getInMemoryOutputSinkForMotifs(
            expectedMotifsAfterAdditions)));

        // Delete one of the edges.
        TestUtils.deleteEdgesPermanently(graph, "DELETE (0)-[:FOLLOWS]->(1);");

        // Execute the query again and test.
        outputSink = new InMemoryOutputSink();
        actualOneTimeMatchQueryPlan.execute(graph, outputSink);
        Assert.assertTrue(InMemoryOutputSink.isSameAs(outputSink, getInMemoryOutputSinkForMotifs(
            expectedMotifsAfterDeletion)));
    }

    private InMemoryOutputSink getInMemoryOutputSinkForMotifs(int[][] motifs) {
        InMemoryOutputSink inMemoryOutputSink = new InMemoryOutputSink();
        for (int[] motif : motifs) {
            inMemoryOutputSink.append(GenericJoinExecutor.getStringOutput(motif,
                MatchQueryResultType.MATCHED));
        }
        return inMemoryOutputSink;
    }
}
