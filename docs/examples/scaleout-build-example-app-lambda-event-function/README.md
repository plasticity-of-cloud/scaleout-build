# Example: scaleout-build app on AWS Lambda Event Functions

This is the same small reflection-free application used by the ECS Fargate and Lambda MicroVM
examples. Its difference is placement: it explicitly requests `backendPreference=lambda`, which
makes the control plane asynchronously invoke an architecture-matched **AWS Lambda Event Function**
for each build cell.

The sample intentionally builds both `x86_64` and `arm64` remotely. It proves that one deployed
worker function is not reused across architectures: Lambda Event Functions have a fixed
architecture, so the control plane selects the configured x86_64 or arm64 function per cell.

## Prerequisites

The control plane must be deployed with Lambda Event Functions enabled. Before enabling its CDK
context, publish the two worker images to the stack's `AgentRepositoryUri` ECR repository:

```bash
./build-local.sh --lambda-event-function --push <AgentRepositoryUri> --platform linux/amd64
./build-local.sh --lambda-event-function --push <AgentRepositoryUri> --platform linux/arm64
cd scaleout-build-control-plane-infra
cdk deploy -c lambdaEventFunctionEnabled=true -c runtimeMode=jvm -c development=true
```

The stack creates two private worker functions, each with a 30-minute timeout. The Maven client
only needs the control-plane Function URL permissions listed below; it does not receive the worker
function ARNs or direct S3/ECR permissions.

You also need Java 21+, Maven, and AWS credentials with `lambda:InvokeFunctionUrl` and
`lambda:InvokeFunction` for the **control-plane** function.

## Run it

1. Copy the endpoint template and set the control-plane URL:

   ```bash
   cp deployment.properties.example deployment.properties
   ```

2. Build the application:

   ```bash
   mvn package
   ```

   `forceRemote=true` sends both cells through the control plane, including the architecture that
   matches the current host. `backendPreference=lambda` prevents the build from falling back to
   the unchanged `auto` selection policy.

3. Inspect the returned binaries:

   ```bash
   file target/scaleout-build/remote-artifacts/NATIVE-X86_64/hello-native
   file target/scaleout-build/remote-artifacts/NATIVE-ARM64/hello-native
   ```

## What happens on AWS

For each cell, the control plane stages inputs in S3, accepts an asynchronous Lambda event
invocation, and keeps the cell provisioning until the worker writes `status.json`. The worker
reuses the normal agent JAR's direct-S3 build path. It uses a conditional S3 claim to prevent a
duplicate asynchronous event delivery from publishing a second result.

Cancellation writes a durable marker that prevents queued workers from starting. AWS Lambda cannot
terminate an already-running named event invocation, so an in-progress `native-image` execution
continues until it finishes or reaches the worker's 30-minute timeout.

See [the Lambda Event Function backend design](../../design/control-plane/lambda-event-function-build-backend.md)
for fixed resource sizing, timeout behavior, pricing trade-offs, and production constraints.
