/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.ecs;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Launches one cell as an asynchronous invocation of an AWS Lambda Event Function.
 *
 * <p>The invocation event is the existing {@link CellLaunchRequest#agentEnvironment()} map. The
 * worker writes the same {@code status.json} contract used by the MicroVM backend, so an async
 * Lambda acceptance response is never mistaken for a completed compile. Lambda Event Functions do not
 * expose a per-invocation stop operation; {@link #stop(CellHandle, String)} records this limitation
 * in logs while the function timeout remains the hard upper bound.
 */
public final class LambdaEventFunctionCellLauncher implements CellLauncher {
    private static final Logger LOG = LoggerFactory.getLogger(LambdaEventFunctionCellLauncher.class);

    private final LambdaClient lambda;
    private final S3Client s3Client;
    private final LambdaEventFunctionSettings settings;
    private final String stagingBucket;
    private final ObjectMapper mapper = new ObjectMapper();

    public LambdaEventFunctionCellLauncher(LambdaClient lambda, S3Client s3Client,
                                     LambdaEventFunctionSettings settings, String stagingBucket) {
        this.lambda = Objects.requireNonNull(lambda, "lambda");
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.stagingBucket = requireNonBlank(stagingBucket, "stagingBucket");
    }

    @Override
    public CellHandle launch(CellLaunchRequest request) throws CellLaunchException {
        String functionArn = settings.functionArnFor(request.architecture());
        try {
            InvokeResponse response = lambda.invoke(InvokeRequest.builder()
                    .functionName(functionArn)
                    .invocationType(InvocationType.EVENT)
                    .payload(SdkBytes.fromByteArray(mapper.writeValueAsBytes(request.agentEnvironment())))
                    .build());
            if (response.statusCode() != 202) {
                throw new CellLaunchException("asynchronous Lambda invoke returned HTTP "
                        + response.statusCode());
            }
            // An asynchronous Event Function response carries no invocation identifier. Keep
            // the nullable field in the durable handle for diagnostics should AWS expose one later.
            String requestId = null;
            LOG.info("Accepted Event Function invocation for cell {} on {}", request.cellId(), functionArn);
            return new CellHandle.LambdaEventFunctionHandle(functionArn, requestId,
                    request.stagingRelativePath());
        } catch (CellLaunchException e) {
            throw e;
        } catch (SdkException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new CellLaunchException("asynchronous Lambda invoke failed: " + e.getMessage(), e);
        }
    }

    @Override
    public CellPollResult poll(CellHandle handle) {
        CellHandle.LambdaEventFunctionHandle h = (CellHandle.LambdaEventFunctionHandle) handle;
        String key = joinKey(h.stagingRelativePath(), "status.json");
        try {
            byte[] bytes = s3Client.getObjectAsBytes(b -> b.bucket(stagingBucket).key(key)).asByteArray();
            StatusReport report = mapper.readValue(bytes, StatusReport.class);
            return "SUCCEEDED".equals(report.state())
                    ? CellPollResult.succeeded(report.exitCode(), report.attemptId(),
                            report.artifacts().stream().map(ArtifactReport::key)
                                    .filter(Objects::nonNull).toList())
                    : CellPollResult.failed(report.exitCode(), "Lambda build reported failure (exitCode="
                            + report.exitCode() + ")");
        } catch (NoSuchKeyException e) {
            return CellPollResult.running();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return CellPollResult.running();
            }
            LOG.warn("Failed to read status.json for Lambda {}: {}", h.functionArn(), e.getMessage());
            return CellPollResult.running();
        } catch (Exception e) {
            LOG.warn("Malformed status.json for Lambda {}: {}", h.functionArn(), e.getMessage());
            return CellPollResult.running();
        }
    }

    @Override
    public void stop(CellHandle handle, String reason) {
        CellHandle.LambdaEventFunctionHandle h = (CellHandle.LambdaEventFunctionHandle) handle;
        if (h.stagingRelativePath() == null) {
            LOG.warn("Cannot write cancellation marker for Lambda {} without its staging path", h.functionArn());
            return;
        }
        try {
            s3Client.putObject(software.amazon.awssdk.services.s3.model.PutObjectRequest.builder()
                    .bucket(stagingBucket)
                    .key(joinKey(h.stagingRelativePath(), "cancelled"))
                    .contentType("text/plain")
                    .build(), software.amazon.awssdk.core.sync.RequestBody.fromString(reason));
            // Lambda has no per-invocation terminate API. The worker observes this marker before
            // taking its S3 claim, preventing queued events from beginning a compile. An invocation
            // already in native-image remains bounded by the function's configured timeout.
            LOG.info("Wrote cancellation marker for Event Function {}: {}", h.functionArn(), reason);
        } catch (SdkException e) {
            LOG.warn("Failed to write cancellation marker for Lambda {}: {}", h.functionArn(),
                    e.getMessage());
        }
    }

    private static String joinKey(String prefix, String relativePath) {
        return prefix.endsWith("/") ? prefix + relativePath : prefix + "/" + relativePath;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /** The status wire shape written by AgentLambdaHandler and AgentHookServer. */
    public record StatusReport(String buildId, String cell, String state, int exitCode,
                               List<ArtifactReport> artifacts, String completedAt, String attemptId) {
        public StatusReport {
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        }
    }

    /** One immutable attempt artifact selected by the canonical status manifest. */
    public record ArtifactReport(String path, String key, String sha256, long sizeBytes) {
    }
}
