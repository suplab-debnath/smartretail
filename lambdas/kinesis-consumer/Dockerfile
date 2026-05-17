# Use the official AWS Lambda Java 21 base image with ARM64 support
FROM public.ecr.aws/lambda/java:21-arm64
# FROM public.ecr.aws/lambda/java:21

# Optional: Set working directory (good practice)
WORKDIR ${LAMBDA_TASK_ROOT}

# Copy the fat JAR produced by mvn clean package into the Lambda task root
COPY target/*.jar ${LAMBDA_TASK_ROOT}/

# Optional but recommended: Environment variables for better performance
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=80 -XX:+UseG1GC"

# Handler pointing to your class (method reference)
CMD ["com.smartretail.lambda.kinesis.KinesisConsumerHandler::handleRequest"]
