FROM public.ecr.aws/lambda/java:21-arm64
# FROM public.ecr.aws/lambda/java:21

WORKDIR ${LAMBDA_TASK_ROOT}

COPY target/*.jar ${LAMBDA_TASK_ROOT}/

ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=80 -XX:+UseG1GC"

CMD ["com.smartretail.lambda.batchpostprocessor.BatchPostProcessorHandler::handleRequest"]
