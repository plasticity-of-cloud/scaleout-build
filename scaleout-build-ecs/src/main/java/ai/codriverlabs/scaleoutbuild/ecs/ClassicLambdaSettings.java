/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.ecs;

import ai.codriverlabs.scaleoutbuild.build.Architecture;
import java.util.Objects;

/**
 * Deployment-owned worker function identities for the standard asynchronous Lambda backend.
 * Lambda functions have a fixed architecture, so a mixed build matrix needs one function per
 * architecture; callers never supply these ARNs.
 */
public record ClassicLambdaSettings(String x86_64FunctionArn, String arm64FunctionArn) {
    public ClassicLambdaSettings {
        requireNonBlank(x86_64FunctionArn, "x86_64FunctionArn");
        requireNonBlank(arm64FunctionArn, "arm64FunctionArn");
    }

    public String functionArnFor(Architecture architecture) {
        return switch (architecture) {
            case X86_64 -> x86_64FunctionArn;
            case ARM64 -> arm64FunctionArn;
        };
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
