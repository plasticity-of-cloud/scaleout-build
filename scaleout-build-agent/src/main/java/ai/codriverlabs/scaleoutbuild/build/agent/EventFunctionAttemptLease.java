/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.build.agent;

import ai.codriverlabs.scaleoutbuild.build.BuildCancellationProbe;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Renewable, fenced S3 lease for one asynchronous Event Function attempt.
 *
 * <p>A permanent create-only claim prevents duplicate work but also prevents recovery after a hard
 * Lambda timeout. This lease permits one later delivery to take over after expiry. Every renewal is
 * guarded by the previous ETag, so a stale attempt detects that it lost ownership and cooperatively
 * terminates its native-image process before publishing artifacts.
 */
final class EventFunctionAttemptLease implements BuildCancellationProbe {
    private static final Logger LOG = LoggerFactory.getLogger(EventFunctionAttemptLease.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    private static final Duration RENEW_INTERVAL = Duration.ofSeconds(5);
    private static final Duration CANCELLATION_POLL_INTERVAL = Duration.ofSeconds(2);

    private final S3Client s3;
    private final String bucket;
    private final String leaseKey;
    private final String cancellationKey;
    private final Clock clock;
    private final String attemptId;
    private final String leaseToken;

    private String eTag;
    private Instant leaseUntil;
    private Instant nextRenewal;
    private Instant nextCancellationCheck = Instant.EPOCH;
    private boolean cancellationObserved;
    private boolean ownershipLost;

    private EventFunctionAttemptLease(S3Client s3, String bucket, String stagingPrefix, Clock clock,
                                      String attemptId, String leaseToken, String eTag,
                                      Instant leaseUntil) {
        this.s3 = s3;
        this.bucket = bucket;
        this.leaseKey = joinKey(stagingPrefix, "lambda-lease.json");
        this.cancellationKey = joinKey(stagingPrefix, "cancelled");
        this.clock = clock;
        this.attemptId = attemptId;
        this.leaseToken = leaseToken;
        this.eTag = eTag;
        this.leaseUntil = leaseUntil;
        this.nextRenewal = clock.instant().plus(RENEW_INTERVAL);
    }

    static Optional<EventFunctionAttemptLease> acquire(S3Client s3, String bucket,
                                                        String stagingPrefix) throws java.io.IOException {
        return acquire(s3, bucket, stagingPrefix, Clock.systemUTC());
    }

    static Optional<EventFunctionAttemptLease> acquire(S3Client s3, String bucket,
                                                        String stagingPrefix, Clock clock)
            throws java.io.IOException {
        Objects.requireNonNull(s3, "s3");
        String key = joinKey(stagingPrefix, "lambda-lease.json");
        String attemptId = UUID.randomUUID().toString();
        String token = UUID.randomUUID().toString();
        Instant now = clock.instant();
        Instant until = now.plus(LEASE_DURATION);
        LeaseDocument candidate = new LeaseDocument(1, attemptId, token,
                now.toEpochMilli(), until.toEpochMilli());
        byte[] body = MAPPER.writeValueAsBytes(candidate);
        try {
            var response = s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                    .contentType("application/json").ifNoneMatch("*").build(),
                    RequestBody.fromBytes(body));
            return Optional.of(new EventFunctionAttemptLease(s3, bucket, stagingPrefix, clock,
                    attemptId, token, response.eTag(), until));
        } catch (S3Exception e) {
            if (e.statusCode() != 412) {
                throw new java.io.IOException("Failed to create Event Function attempt lease", e);
            }
        }

        var existingBytes = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build());
        LeaseDocument existing = MAPPER.readValue(existingBytes.asByteArray(), LeaseDocument.class);
        if (existing.leaseUntilEpochMilli() > now.toEpochMilli()) {
            return Optional.empty();
        }
        try {
            var response = s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                    .contentType("application/json").ifMatch(existingBytes.response().eTag()).build(),
                    RequestBody.fromBytes(body));
            return Optional.of(new EventFunctionAttemptLease(s3, bucket, stagingPrefix, clock,
                    attemptId, token, response.eTag(), until));
        } catch (S3Exception e) {
            if (e.statusCode() == 412) {
                return Optional.empty();
            }
            throw new java.io.IOException("Failed to take over expired Event Function lease", e);
        }
    }

    String attemptId() {
        return attemptId;
    }

    String outputPrefix(String stagingPrefix) {
        return joinKey(stagingPrefix, "attempts/" + attemptId + "/output");
    }

    @Override
    public synchronized boolean isCancellationRequested() {
        if (ownershipLost || cancellationObserved) {
            return true;
        }
        Instant now = clock.instant();
        if (!now.isBefore(nextCancellationCheck)) {
            cancellationObserved = cancellationMarkerExists();
            nextCancellationCheck = now.plus(CANCELLATION_POLL_INTERVAL);
            if (cancellationObserved) {
                return true;
            }
        }
        if (!now.isBefore(nextRenewal)) {
            renew(now);
        }
        if (!now.isBefore(leaseUntil)) {
            ownershipLost = true;
        }
        return ownershipLost;
    }

    synchronized boolean fenceForCommit() {
        if (isCancellationRequested()) {
            return false;
        }
        return renewUntil(clock.instant(), LEASE_DURATION, true);
    }

    private void renew(Instant now) {
        renewUntil(now, LEASE_DURATION, false);
    }

    private boolean renewUntil(Instant now, Duration duration, boolean strict) {
        Instant renewedUntil = now.plus(duration);
        LeaseDocument renewed = new LeaseDocument(1, attemptId, leaseToken,
                now.toEpochMilli(), renewedUntil.toEpochMilli());
        try {
            byte[] body = MAPPER.writeValueAsBytes(renewed);
            var response = s3.putObject(PutObjectRequest.builder().bucket(bucket).key(leaseKey)
                    .contentType("application/json").ifMatch(eTag).build(),
                    RequestBody.fromBytes(body));
            eTag = response.eTag();
            leaseUntil = renewedUntil;
            nextRenewal = now.plus(RENEW_INTERVAL);
            return true;
        } catch (SdkException e) {
            if (e instanceof S3Exception s3Exception && s3Exception.statusCode() == 412) {
                ownershipLost = true;
            } else {
                if (strict) {
                    throw new IllegalStateException(
                            "Transient S3 failure while fencing attempt " + attemptId, e);
                }
                LOG.warn("Lease renewal failed for attempt {}: {}", attemptId, e.getMessage());
                nextRenewal = now.plusSeconds(1);
            }
            return false;
        } catch (Exception e) {
            if (strict) {
                throw new IllegalStateException(
                        "Failed to serialize or fence attempt " + attemptId, e);
            }
            LOG.warn("Lease renewal failed for attempt {}: {}", attemptId, e.getMessage());
            nextRenewal = now.plusSeconds(1);
            return false;
        }
    }

    private boolean cancellationMarkerExists() {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(cancellationKey).build());
            return true;
        } catch (SdkException e) {
            if (e instanceof S3Exception s3Exception && s3Exception.statusCode() == 404) {
                return false;
            }
            LOG.warn("Cancellation-marker check failed for attempt {}: {}", attemptId, e.getMessage());
            return false;
        }
    }

    private static String joinKey(String prefix, String name) {
        return prefix.endsWith("/") ? prefix + name : prefix + "/" + name;
    }

    record LeaseDocument(int schemaVersion, String attemptId, String leaseToken,
                         long acquiredAtEpochMilli, long leaseUntilEpochMilli) {
    }
}
