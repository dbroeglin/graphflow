package ca.waterloo.dsg.graphflow.cli;

import ca.waterloo.dsg.graphflow.server.GraphflowServerQueryGrpc;
import ca.waterloo.dsg.graphflow.server.ServerQueryResult;
import ca.waterloo.dsg.graphflow.server.ServerQueryString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client side command line interface implementation.
 * It uses gRPC to communicate with the server.
 */
public class GraphflowCli {

    /**
     * Stores the gRPC channel to the server.
     */
    private final ManagedChannel channel;

    /**
     * Used to perform blocking gRPC calls.
     */
    private final GraphflowServerQueryGrpc.GraphflowServerQueryBlockingStub blockingStub;

    /**
     * Construct a client connecting to a server at {@code host:port}.
     */
    public GraphflowCli(String host, int port) {
        // Turn off logs to suppress debug messages from netty.
        Logger.getLogger("io.grpc.internal").setLevel(Level.OFF);

        channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext(true).build();
        blockingStub = GraphflowServerQueryGrpc.newBlockingStub(channel);
    }

    /**
     * Start the CLI interface.
     */
    public void startCLI(Scanner cliInput) throws FileNotFoundException {
        while (true) {
            System.out.print("graphflow> ");
            if (!cliInput.hasNext()) {
                break;
            }
            String query = cliInput.nextLine().trim();
            if (query.isEmpty()) {
                continue;
            }
            if (query.charAt(query.length() - 1) != ';') {
                System.out.println("ERROR: Needs a semicolon at the end.");
                continue;
            }
            if (query == "exit;") {
                break;
            }
            System.out.println("Your query: " + query);
            String result = queryServer(query);
            System.out.println("Result:\n" + result);
        }
        System.out.println("May the flow be with you!");
    }

    /**
     * Send the input {@code query} string to the server.
     */
    public String queryServer(String query) {
        ServerQueryString request = ServerQueryString.newBuilder().setMessage(query).build();
        ServerQueryResult result;
        try {
            result = blockingStub.executeQuery(request);
        } catch (StatusRuntimeException e) {
            return "ERROR: " + e.getMessage();
        }
        return result.getMessage();
    }

    /**
     * Safely terminate the connection to the server.
     */
    public void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    public static void main(String[] args) throws Exception {
        GraphflowCli cli = new GraphflowCli("localhost", 8080);
        try {
            Scanner cliInput;
            if (args.length > 0) {
                // If an argument is provided, treat it as a filename to read commands from.
                // This is used for automated testing.
                File file = new File(args[0]);
                cliInput = new Scanner(file);
            } else {
                // Read from stdin.
                cliInput = new Scanner(System.in);
            }
            cli.startCLI(cliInput);
        } finally {
            cli.shutdown();
        }
    }
}
