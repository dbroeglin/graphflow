package ca.waterloo.dsg.graphflow.util;

import ca.waterloo.dsg.graphflow.query.executors.MatchQueryResultType;
import ca.waterloo.dsg.graphflow.query.operator.FileOutputSink;

/**
 * Currently only contains the {@link #getStringMatchQueryOutput(int[], MatchQueryResultType)}
 * method that is shared between {@link FileOutputSink} and some tests.
 * 
 * TODO: This class is not justified and the {@link #getStringMatchQueryOutput(int[],
 * MatchQueryResultType)} method should be moved somewhere else.
 */
public class QueryOutputUtils {

    /**
     * @param vertexIds the IDs of vertices in a MATCH or CONTINUOUS MATCH query output.
     * @param matchQueryResultType type of the query output.
     * @return String human readable representation of the query output.
     */
    public static String getStringMatchQueryOutput(int[] vertexIds,
        MatchQueryResultType matchQueryResultType) {
        StringBuilder stringBuilder = new StringBuilder();
        for (int id : vertexIds) {
            stringBuilder.append(" " + id);
        }
        stringBuilder.append(" " + matchQueryResultType.name());
        return stringBuilder.toString();
    }
}
