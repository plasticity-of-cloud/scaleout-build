/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.build.agent;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Lambda Event Function event handler for one asynchronous build cell.
 *
 * <p>Its input is the JSON encoding of the same {@code SCALEOUT_BUILD_*} map passed as task
 * environment variables to ECS. It intentionally uses direct S3 I/O: a Lambda Event Function has no
 * ECS mount, so the worker downloads staged inputs and writes both artifacts and {@code status.json}
 * to the shared staging bucket. Completion therefore remains durable and pull-based, rather than
 * treating an asynchronous Invoke acceptance as build completion.
 */
public final class AgentLambdaHandler implements RequestStreamHandler {
    private static final Logger LOG = LoggerFactory.getLogger(AgentLambdaHandler.class);

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        byte[] payload = input.readAllBytes();
        AgentConfig config;
        try {
            config = AgentConfig.fromHookPayload(payload);
        } catch (RuntimeException e) {
            // Invalid events cannot name a safe status location. Fail the invocation so Lambda's
            // configured on-failure destination records it instead of claiming an unknown build ran.
            throw new IOException("Invalid Lambda Event Function build event: " + e.getMessage(), e);
        }

        LOG.info("Starting build agent (Lambda Event Function event) with {}", config);
        try (S3Client s3 = S3Client.create()) {
            if (statusExists(s3, config)) {
                LOG.info("Skipping delivery for completed Lambda build {}", config.buildId());
                return;
            }
            var acquired = acquireLeaseWithShortWait(s3, config);
            if (acquired.isEmpty()) {
                LOG.info("Skipping duplicate Lambda delivery for build {}; another attempt owns the lease",
                        config.buildId());
                return;
            }
            EventFunctionAttemptLease lease = acquired.get();
            String outputPrefix = lease.outputPrefix(config.stagingRelativePath());
            int exitCode = AgentMain.runWithDirectS3Io(config,
                    ai.codriverlabs.scaleoutbuild.build.BuildLog.defaultLog(), s3, lease, outputPrefix);
            if (exitCode == AgentMain.EXIT_CANCELLED || lease.isCancellationRequested()) {
                LOG.info("Attempt {} stopped before canonical publication", lease.attemptId());
                return;
            }
            // status.json is the conditional commit point. Artifacts remain immutable under the
            // winning attempt prefix, so an expired stale worker cannot overwrite them.
            boolean committed = new AgentHookServer(s3).writeStatusJson(config, exitCode,
                    outputPrefix, lease.attemptId(), true, lease::fenceForCommit);
            LOG.info("Attempt {} canonical status committed={}", lease.attemptId(), committed);
            output.write(("{\"buildId\":\"" + config.buildId() + "\",\"attemptId\":\""
                    + lease.attemptId() + "\",\"exitCode\":" + exitCode + "}")
                    .getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException("Failed to run Lambda build for " + config.buildId(), e);
        }
    }

    /**
     * A retry that arrives during the previous attempt's final lease window waits briefly instead
     * of consuming the only redrive. If the owner crashed, this invocation can take over after the
     * 30-second lease expires; a genuine concurrent duplicate still exits after the bounded wait.
     */
    private static java.util.Optional<EventFunctionAttemptLease> acquireLeaseWithShortWait(
            S3Client s3, AgentConfig config) throws IOException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(40);
        do {
            var lease = EventFunctionAttemptLease.acquire(s3, config.s3Bucket(),
                    config.stagingRelativePath());
            if (lease.isPresent() || statusExists(s3, config)) {
                return lease;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for Event Function attempt lease", e);
            }
        } while (System.nanoTime() < deadline);
        return java.util.Optional.empty();
    }

    private static boolean statusExists(S3Client s3, AgentConfig config) {
        try {
            s3.headObject(b -> b.bucket(config.s3Bucket())
                    .key(joinKey(config.stagingRelativePath(), "status.json")));
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    private static String joinKey(String prefix, String name) {
        return prefix.endsWith("/") ? prefix + name : prefix + "/" + name;
    }
}
