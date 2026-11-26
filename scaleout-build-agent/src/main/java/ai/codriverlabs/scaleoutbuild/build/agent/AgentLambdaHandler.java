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
            if (cancelled(s3, config)) {
                LOG.info("Skipping cancelled Lambda build {}", config.buildId());
                return;
            }
            if (!claim(s3, config)) {
                LOG.info("Skipping duplicate Lambda delivery for build {}", config.buildId());
                return;
            }
            int exitCode = AgentMain.runWithDirectS3Io(config,
                    ai.codriverlabs.scaleoutbuild.build.BuildLog.defaultLog(), s3);
            // Reuse the exact status wire writer used by the MicroVM hook server. This avoids
            // backend-specific result schemas and keeps LambdaEventFunctionCellLauncher's S3 polling
            // compatible with existing artifact/status tooling.
            new AgentHookServer(s3).writeStatusJson(config, exitCode);
            output.write(("{\"buildId\":\"" + config.buildId() + "\",\"exitCode\":" + exitCode
                    + "}").getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException("Failed to write Lambda build status for " + config.buildId(), e);
        }
    }

    private static boolean cancelled(S3Client s3, AgentConfig config) {
        try {
            s3.headObject(b -> b.bucket(config.s3Bucket())
                    .key(joinKey(config.stagingRelativePath(), "cancelled")));
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    /**
     * Claims canonical output publication before compilation. Async Lambda delivery is at-least-once;
     * a conditional S3 create ensures only the first accepted delivery can write the cell's shared
     * output and status paths. Event retries are disabled in the infrastructure, so a failed first
     * attempt is reported by the control-plane deadline rather than a second worker racing it.
     */
    private static boolean claim(S3Client s3, AgentConfig config) {
        try {
            s3.putObject(b -> b.bucket(config.s3Bucket())
                    .key(joinKey(config.stagingRelativePath(), "lambda-claim"))
                    .ifNoneMatch("*"), software.amazon.awssdk.core.sync.RequestBody
                    .fromBytes(new byte[0]));
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 412) {
                return false;
            }
            throw e;
        }
    }

    private static String joinKey(String prefix, String name) {
        return prefix.endsWith("/") ? prefix + name : prefix + "/" + name;
    }
}
