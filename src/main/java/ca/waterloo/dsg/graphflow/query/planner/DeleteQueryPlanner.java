package ca.waterloo.dsg.graphflow.query.planner;

import ca.waterloo.dsg.graphflow.query.plans.DeleteQueryPlan;
import ca.waterloo.dsg.graphflow.query.plans.QueryPlan;
import ca.waterloo.dsg.graphflow.query.utils.StructuredQuery;

/**
 * Create an {@code QueryPlan} for the DELETE operation.
 */
public class DeleteQueryPlanner extends AbstractQueryPlanner {

    public DeleteQueryPlanner(StructuredQuery structuredQuery) {
        super(structuredQuery);
    }

    @Override
    public QueryPlan plan() {
        return new DeleteQueryPlan(structuredQuery);
    }
}
