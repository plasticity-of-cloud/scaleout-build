# Regular AWS Lambda container image for the asynchronous build worker.
#
# This retains the Mandrel builder base because the worker runs native-image itself. A managed Java
# Lambda base image would provide the Runtime API but not native-image. The Runtime Interface Client
# is shaded into agent.jar and adapts this custom image to Lambda's Runtime API.
ARG BASE_IMAGE=quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25
FROM ${BASE_IMAGE}

USER 1001:1001
COPY --chown=1001:1001 agent.jar /home/quarkus/agent.jar
ENV SCALEOUT_BUILD_TEMP_DIR=/tmp

# The runtime interface client polls AWS_LAMBDA_RUNTIME_API and invokes this RequestStreamHandler.
# `timeout` is deliberately not used here: Lambda's configured 900-second function timeout is the
# outer backstop and guarantees the platform records a timed-out invocation correctly.
ENTRYPOINT ["java", "-cp", "/home/quarkus/agent.jar", "com.amazonaws.services.lambda.runtime.api.client.AWSLambda"]
CMD ["ai.codriverlabs.scaleoutbuild.build.agent.AgentLambdaHandler::handleRequest"]
