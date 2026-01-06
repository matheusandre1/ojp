package org.openjproxy.grpc.server.action;

import io.grpc.stub.StreamObserver;

/**
 * Base interface for all action classes in the refactored StatementServiceImpl.
 * Each action encapsulates the logic for a specific gRPC operation.
 * 
 * @param <TRequest> The gRPC request type
 * @param <TResponse> The gRPC response type
 */
@FunctionalInterface
public interface Action<TRequest, TResponse> {
    /**
     * Execute the action with the given request and response observer.
     * 
     * @param request The gRPC request
     * @param responseObserver The gRPC response observer for sending responses
     */
    void execute(TRequest request, StreamObserver<TResponse> responseObserver);
}
