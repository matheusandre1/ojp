package org.openjproxy.grpc.server;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.netty.NettyServerBuilder;
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.server.utils.DriverLoader;
import org.openjproxy.grpc.server.utils.DriverUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class GrpcServer {
    private static final Logger logger = LoggerFactory.getLogger(GrpcServer.class);

    public static void main(String[] args) throws IOException, InterruptedException {
        // Initialize health status manager
        OjpHealthManager.initialize();

        // Load configuration
        ServerConfiguration config = new ServerConfiguration();
        
        // Load external JDBC drivers from configured directory
        logger.info("Loading external JDBC drivers...");
        boolean driversLoaded = DriverLoader.loadDriversFromPath(config.getDriversPath());
        if (!driversLoaded) {
            logger.warn("Failed to load external libraries from path: {}", config.getDriversPath());
            logger.warn("Server will continue, but proprietary drivers may not be available");
        }
        
        // Register JDBC drivers (both built-in and externally loaded)
        DriverUtils.registerDrivers(config.getDriversPath());
        
        // Validate IP whitelist for server
        if (!IpWhitelistValidator.validateWhitelistRules(config.getAllowedIps())) {
            logger.error("Invalid IP whitelist configuration for server. Exiting.");
            System.exit(1);
        }

        // Initialize telemetry based on configuration
        OjpServerTelemetry ojpServerTelemetry = new OjpServerTelemetry();
        GrpcTelemetry grpcTelemetry;
        
        if (config.isOpenTelemetryEnabled()) {
            grpcTelemetry = ojpServerTelemetry.createGrpcTelemetry(
                config.getPrometheusPort(), 
                config.getPrometheusAllowedIps()
            );

            OjpHealthManager.setServiceStatus(OjpHealthManager.Services.OPENTELEMETRY_SERVICE,
                    HealthCheckResponse.ServingStatus.SERVING);
        } else {
            grpcTelemetry = ojpServerTelemetry.createNoOpGrpcTelemetry();
        }

        // Build server with configuration
        SessionManagerImpl sessionManager = new SessionManagerImpl();
        
        ServerBuilder<?> serverBuilder = NettyServerBuilder
                .forPort(config.getServerPort())
                .executor(Executors.newFixedThreadPool(config.getThreadPoolSize()))
                .maxInboundMessageSize(config.getMaxRequestSize())
                .keepAliveTime(config.getConnectionIdleTimeout(), TimeUnit.MILLISECONDS)
                .addService(new StatementServiceImpl(
                        sessionManager,
                        new CircuitBreaker(config.getCircuitBreakerTimeout(), config.getCircuitBreakerThreshold()),
                        config
                ))
                .addService(OjpHealthManager.getHealthStatusManager().getHealthService())
                .intercept(grpcTelemetry.newServerInterceptor());

        Server server = serverBuilder.build();

        logger.info("Starting OJP gRPC Server on port {}", config.getServerPort());
        logger.info("Server configuration applied successfully");
        
        // Initialize session cleanup task if enabled
        ScheduledExecutorService sessionCleanupExecutor = null;
        if (config.isSessionCleanupEnabled()) {
            logger.info("Initializing session cleanup task: timeout={}min, interval={}min", 
                    config.getSessionTimeoutMinutes(), config.getSessionCleanupIntervalMinutes());
            
            sessionCleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "ojp-session-cleanup");
                thread.setDaemon(true);
                return thread;
            });
            
            long timeoutMillis = config.getSessionTimeoutMinutes() * 60 * 1000;
            long intervalMillis = config.getSessionCleanupIntervalMinutes() * 60 * 1000;
            
            SessionCleanupTask cleanupTask = new SessionCleanupTask(sessionManager, timeoutMillis);
            sessionCleanupExecutor.scheduleAtFixedRate(
                    cleanupTask, 
                    intervalMillis, // Initial delay
                    intervalMillis, // Period
                    TimeUnit.MILLISECONDS
            );
            
            logger.info("Session cleanup task started successfully");
        } else {
            logger.info("Session cleanup is disabled");
        }
        
        server.start();
        OjpHealthManager.setServiceStatus(OjpHealthManager.Services.OJP_SERVER,
                HealthCheckResponse.ServingStatus.SERVING);
        
        // Add shutdown hook
        ScheduledExecutorService finalSessionCleanupExecutor = sessionCleanupExecutor;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down OJP gRPC Server...");
            
            // Shutdown session cleanup task first
            if (finalSessionCleanupExecutor != null) {
                logger.info("Shutting down session cleanup executor...");
                finalSessionCleanupExecutor.shutdown();
                try {
                    if (!finalSessionCleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        logger.warn("Session cleanup executor did not terminate gracefully");
                        finalSessionCleanupExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    logger.warn("Interrupted while waiting for session cleanup shutdown");
                    finalSessionCleanupExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            
            server.shutdown();

            try {
                if (!server.awaitTermination(30, TimeUnit.SECONDS)) {
                    logger.warn("Server did not terminate gracefully, forcing shutdown");
                    server.shutdownNow();
                }
            } catch (InterruptedException e) {
                logger.warn("Interrupted while waiting for server shutdown");
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
            
            logger.info("OJP gRPC Server shutdown complete");
        }));

        logger.info("OJP gRPC Server started successfully and awaiting termination");
        server.awaitTermination();
    }
}
