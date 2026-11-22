/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.ecs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codriverlabs.scaleoutbuild.build.Architecture;
import ai.codriverlabs.scaleoutbuild.build.BuildKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@ExtendWith(MockitoExtension.class)
class ClassicLambdaCellLauncherTest {
    private static final String X86_FUNCTION = "arn:aws:lambda:eu-west-1:123:function:worker-x86";
    private static final String ARM_FUNCTION = "arn:aws:lambda:eu-west-1:123:function:worker-arm";
    private static final String STAGING_PATH = "builds/example/native/arm64";

    @Mock LambdaClient lambda;
    @Mock S3Client s3;
    private ClassicLambdaCellLauncher launcher;

    @BeforeEach
    void setUp() {
        launcher = new ClassicLambdaCellLauncher(lambda, s3,
                new ClassicLambdaSettings(X86_FUNCTION, ARM_FUNCTION), "staging");
    }

    @Test
    void launch_invokesArchitectureSpecificFunctionAsAnAsyncEvent() throws Exception {
        when(lambda.invoke(any(InvokeRequest.class))).thenReturn(InvokeResponse.builder()
                .statusCode(202).build());
        ArgumentCaptor<InvokeRequest> captured = ArgumentCaptor.forClass(InvokeRequest.class);

        CellHandle handle = launcher.launch(request(Architecture.ARM64));

        verify(lambda).invoke(captured.capture());
        InvokeRequest invoke = captured.getValue();
        assertThat(invoke.functionName()).isEqualTo(ARM_FUNCTION);
        assertThat(invoke.invocationType()).isEqualTo(InvocationType.EVENT);
        Map<String, String> event = new ObjectMapper().readValue(invoke.payload().asByteArray(),
                new com.fasterxml.jackson.core.type.TypeReference<>() { });
        assertThat(event).containsEntry("SCALEOUT_BUILD_ID", "example");
        assertThat(handle).isEqualTo(new CellHandle.ClassicLambdaHandle(ARM_FUNCTION, null, STAGING_PATH));
    }

    @Test
    void poll_readsDurableWorkerStatusInsteadOfCallingLambda() {
        when(s3.getObjectAsBytes(any(java.util.function.Consumer.class))).thenReturn(
                ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), ("""
                {"buildId":"example","cell":"NATIVE/ARM64","state":"SUCCEEDED","exitCode":0,
                 "artifacts":[],"completedAt":"2026-11-21T00:00:00Z"}
                """).getBytes(StandardCharsets.UTF_8)));

        CellPollResult result = launcher.poll(new CellHandle.ClassicLambdaHandle(ARM_FUNCTION, null,
                STAGING_PATH));

        assertThat(result.state()).isEqualTo(CellLaunchState.SUCCEEDED);
        verify(lambda, never()).invoke(any(InvokeRequest.class));
    }

    @Test
    void poll_withoutStatusRemainsRunning() {
        when(s3.getObjectAsBytes(any(java.util.function.Consumer.class)))
                .thenThrow(NoSuchKeyException.builder().build());

        assertThat(launcher.poll(new CellHandle.ClassicLambdaHandle(X86_FUNCTION, null, STAGING_PATH)).state())
                .isEqualTo(CellLaunchState.RUNNING);
    }

    @Test
    void stop_writesACancellationMarkerForQueuedEvents() {
        launcher.stop(new CellHandle.ClassicLambdaHandle(ARM_FUNCTION, null, STAGING_PATH), "cancelled");

        ArgumentCaptor<software.amazon.awssdk.services.s3.model.PutObjectRequest> request =
                ArgumentCaptor.forClass(software.amazon.awssdk.services.s3.model.PutObjectRequest.class);
        verify(s3).putObject(request.capture(), any(software.amazon.awssdk.core.sync.RequestBody.class));
        assertThat(request.getValue().key()).isEqualTo(STAGING_PATH + "/cancelled");
    }

    private static CellLaunchRequest request(Architecture architecture) {
        return new CellLaunchRequest(new EcsClusterSettings(EcsLaunchType.FARGATE,
                "arn:aws:ecs:eu-west-1:123:cluster/test", java.util.List.of("subnet"),
                java.util.List.of("sg"), true, "exec", "task", null, null, null, null, null,
                "logs", "eu-west-1", true), new AgentContainerSettings("image", "4096", "8192", 0),
                "example", BuildKind.NATIVE, architecture, STAGING_PATH,
                Map.of("SCALEOUT_BUILD_ID", "example", "SCALEOUT_BUILD_ARCH", architecture.name()));
    }
}
