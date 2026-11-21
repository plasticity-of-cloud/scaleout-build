# Standard AWS Lambda build backend

## Purpose

`backendPreference=lambda` launches each build cell as an asynchronous event to a standard AWS Lambda container-image function. This is separate from the Lambda MicroVM backend. A MicroVM is created through the Lambda MicroVM API and can be terminated by identifier. The standard Lambda backend invokes an already-deployed `AWS::Lambda::Function` with `InvocationType.EVENT`.

The control plane never waits for the worker invocation. Lambda returns HTTP 202 when it accepts the event, which means only that delivery was accepted. The worker uses the existing direct-S3 agent path and writes `status.json` under its cell staging prefix. `ClassicLambdaCellLauncher.poll()` reads that durable object, so the build is not reported as successful until the agent has completed and uploaded its result.

## Placement and configuration

`auto` remains unchanged and selects Fargate or the configured MicroVM default. Regular Lambda is deliberately explicit through `backendPreference=lambda`. This avoids silently changing existing placement, and it avoids falsely treating Lambda as an equivalent replacement for Fargate.

The service accepts an explicit Lambda request only when all requested architectures have a configured worker:

- `scaleout.classic-lambda.enabled=true`
- `scaleout.classic-lambda.x86-64-function-arn` for `x86_64` cells
- `scaleout.classic-lambda.arm64-function-arn` for `arm64` cells
- `scaleout.ecs.agent-uses-direct-s3-io=true`

A standard Lambda function has one fixed CPU architecture. The CDK stack therefore creates two optional image functions when deployed with `-c classicLambdaEnabled=true`. Their image tags are `lambda-amd64` and `lambda-arm64` in the existing agent ECR repository. Before enabling the context, publish each architecture from a native runner with:

```bash
./build-local.sh --classic-lambda --push <AgentRepositoryUri> --platform linux/amd64
./build-local.sh --classic-lambda --push <AgentRepositoryUri> --platform linux/arm64
```

The local build command uses ECR because Lambda image functions cannot load from GHCR.

## Worker image

The worker is `AgentLambdaHandler`, a `RequestStreamHandler` run through AWS's Java Runtime Interface Client. The image retains the Mandrel builder base because it must execute `native-image`; a normal managed Java Lambda image provides the Runtime API but not the native-image toolchain. The handler parses the same JSON environment map used for MicroVM hook payloads, calls `AgentMain.runWithDirectS3Io`, and reuses the existing `AgentHookServer.writeStatusJson` writer. The result schema is identical across MicroVM and standard Lambda workers.

The CDK worker role has read/write access only to the staging bucket plus Lambda basic logging. The service role has `lambda:InvokeFunction` only for the two worker ARNs. The worker functions do not have Function URLs and no public invoke permission is created.

## Constraints that callers must see

Regular Lambda has different constraints from ECS and MicroVMs:

- Lambda function timeout is capped at 900 seconds. The provided functions use that maximum. The control plane caps the worker's inner `native-image` timeout at 14 minutes, reserving one minute for artifact and status publication. Builds whose honest budget exceeds 15 minutes must use Fargate or MicroVM.
- Memory, CPU allocation, and `/tmp` storage are fixed function deployment settings. The standard worker is provisioned with 10,240 MiB memory and 10,240 MiB ephemeral storage. Per-build Fargate CPU and ephemeral-storage requests do not resize it.
- Asynchronous event delivery is at-least-once. Each worker acquires an `If-None-Match: *` S3 claim before compiling and the Lambda event-invoke configuration disables retries, so duplicate deliveries cannot race to overwrite canonical artifacts or `status.json`. A claim whose worker is killed before reporting is deliberately treated as failed after the fixed deadline, rather than retried into the canonical prefix.
- Standard Lambda has no API to terminate one named in-flight asynchronous invocation. `cancel` and the reaper write a durable cancellation marker so queued workers exit before claiming work, then mark the build terminal. A worker already inside `native-image` can continue until it returns or reaches its 900-second timeout.
- If a worker never writes status, the control plane marks its cell failed after the 900-second execution budget plus the 60-second event-delivery limit. It cannot remain RUNNING indefinitely.

These constraints are deliberately documented rather than hidden behind a Fargate-shaped resource API. The backend is useful for bounded, bursty builds that fit Lambda's deployment-fixed worker shape. It is not a transparent substitute for long-running or dynamically sized ECS tasks.

## Verification

The unit tests cover architecture-specific asynchronous invocation, durable status polling, explicit backend validation, and backward-compatible persisted handles. `scripts/verify-synth.sh` synthesizes both normal deployment modes and the optional Lambda-worker deployment, asserting worker architecture, image package type, memory, timeout, ephemeral storage, no Function URL, and least-privilege invocation permission.

A real AWS build remains an operator-controlled end-to-end check because it consumes Lambda execution and native-image capacity. It should run both architectures, then verify the worker log group, `status.json`, artifacts, cancellation behavior, and the 15-minute timeout path.
