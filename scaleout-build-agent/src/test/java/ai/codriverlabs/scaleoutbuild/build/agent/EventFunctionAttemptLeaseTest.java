/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.build.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

class EventFunctionAttemptLeaseTest {
    private static final String BUCKET = "staging";
    private static final String PREFIX = "builds/one/native/arm64";

    @Test
    void firstDeliveryAcquiresCreateOnlyLease() throws Exception {
        S3Client s3 = mock(S3Client.class);
        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        when(s3.putObject(request.capture(), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("etag-1").build());

        var lease = EventFunctionAttemptLease.acquire(s3, BUCKET, PREFIX,
                Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneId.of("UTC")));

        assertThat(lease).isPresent();
        assertThat(request.getValue().ifNoneMatch()).isEqualTo("*");
        assertThat(lease.get().attemptId()).isNotBlank();
        assertThat(lease.get().outputPrefix(PREFIX)).contains("/attempts/")
                .endsWith("/output");
    }

    @Test
    void duplicateDeliveryDoesNotTakeAnUnexpiredLease() throws Exception {
        S3Client s3 = mock(S3Client.class);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(preconditionFailed());
        var document = new EventFunctionAttemptLease.LeaseDocument(1, "attempt", "token",
                Instant.parse("2026-11-01T00:00:00Z").toEpochMilli(),
                Instant.parse("2026-11-01T00:01:00Z").toEpochMilli());
        byte[] json = new ObjectMapper().writeValueAsBytes(document);
        when(s3.getObjectAsBytes(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(
                        GetObjectResponse.builder().eTag("etag-existing").build(), json));

        var lease = EventFunctionAttemptLease.acquire(s3, BUCKET, PREFIX,
                Clock.fixed(Instant.parse("2026-11-01T00:00:10Z"), ZoneId.of("UTC")));

        assertThat(lease).isEmpty();
    }

    @Test
    void expiredLeaseCanBeTakenOverByOneNewAttempt() throws Exception {
        S3Client s3 = mock(S3Client.class);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(preconditionFailed())
                .thenReturn(PutObjectResponse.builder().eTag("etag-new").build());
        var document = new EventFunctionAttemptLease.LeaseDocument(1, "old-attempt", "old-token",
                Instant.parse("2026-11-01T00:00:00Z").toEpochMilli(),
                Instant.parse("2026-11-01T00:01:00Z").toEpochMilli());
        byte[] json = new ObjectMapper().writeValueAsBytes(document);
        when(s3.getObjectAsBytes(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(
                        GetObjectResponse.builder().eTag("etag-existing").build(), json));

        var lease = EventFunctionAttemptLease.acquire(s3, BUCKET, PREFIX,
                Clock.fixed(Instant.parse("2026-11-01T00:01:10Z"), ZoneId.of("UTC")));

        assertThat(lease).isPresent();
        assertThat(lease.get().attemptId()).isNotEqualTo("old-attempt");
    }

    @Test
    void transientCommitFenceFailureIsPropagatedForLambdaRedrive() throws Exception {
        S3Client s3 = mock(S3Client.class);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("etag-1").build())
                .thenThrow(software.amazon.awssdk.core.exception.SdkClientException
                        .create("temporary network failure"));
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(notFound());
        var lease = EventFunctionAttemptLease.acquire(s3, BUCKET, PREFIX,
                Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneId.of("UTC"))).orElseThrow();

        assertThatThrownBy(lease::fenceForCommit)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fencing attempt");
    }

    @Test
    void cancellationMarkerCancelsTheOwningAttempt() throws Exception {
        S3Client s3 = mock(S3Client.class);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("etag-1").build());
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());
        var lease = EventFunctionAttemptLease.acquire(s3, BUCKET, PREFIX,
                Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneId.of("UTC"))).orElseThrow();

        assertThat(lease.isCancellationRequested()).isTrue();
    }

    private static S3Exception notFound() {
        return (S3Exception) S3Exception.builder().statusCode(404).message("not found").build();
    }

    private static S3Exception preconditionFailed() {
        return (S3Exception) S3Exception.builder().statusCode(412)
                .message("precondition failed").build();
    }
}
