/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.build;

/**
 * Cooperative cancellation boundary for a running build process.
 *
 * <p>The default probe never cancels, preserving existing local, Fargate, and MicroVM behavior.
 * Event Function workers supply a probe backed by their durable cancellation marker and attempt
 * lease. Implementations should be cheap enough to call once per second.
 */
@FunctionalInterface
public interface BuildCancellationProbe {

    BuildCancellationProbe NEVER = () -> false;

    /** @return true when the current process must stop before publishing artifacts */
    boolean isCancellationRequested();
}
