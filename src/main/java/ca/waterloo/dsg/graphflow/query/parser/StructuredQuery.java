package ca.waterloo.dsg.graphflow.query.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * This class encapsulates the query structure formed from parse tree traversal.
 */
public class StructuredQuery {

    private List<Edge> edges;
    private Operation operation;

    public enum Operation {
        CREATE,
        MATCH,
        DELETE
    }

    public StructuredQuery() {
        this.edges = new ArrayList<>();
    }

    public List<Edge> getEdges() {
        return edges;
    }

    public void addEdge(Edge edge) {
        this.edges.add(edge);
    }

    public void addEdge(int pos, Edge edge) {
        this.edges.add(pos, edge);
    }

    public Operation getOperation() {
        return operation;
    }

    public void setOperation(Operation operation) {
        this.operation = operation;
    }
}