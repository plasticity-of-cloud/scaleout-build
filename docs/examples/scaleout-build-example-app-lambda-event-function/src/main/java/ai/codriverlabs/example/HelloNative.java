package ai.codriverlabs.example;

import java.time.Instant;
import java.util.Locale;

/**
 * Minimal reflection-free application used to verify the AWS Lambda Event Function build backend.
 * The application is intentionally the same shape as the ECS and MicroVM examples so backend
 * behavior, rather than application complexity, is what changes between examples.
 */
public final class HelloNative {
    private HelloNative() {
    }

    public static void main(String[] args) {
        String arch = System.getProperty("os.arch", "unknown");
        String greeting = args.length > 0 ? args[0] : "world";
        System.out.printf(Locale.ROOT, "Hello, %s! Compiled natively for %s at %s.%n",
                greeting, arch, Instant.now());
    }
}
