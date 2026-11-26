/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.build;

/** Raised when a cooperative cancellation request terminates the native-image process tree. */
public final class BuildCancelledException extends BuildFailedException {

    private static final long serialVersionUID = 1L;

    public BuildCancelledException(String message) {
        super(message);
    }
}
