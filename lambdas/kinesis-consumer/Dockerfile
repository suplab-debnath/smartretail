FROM public.ecr.aws/lambda/java:21
# Copy the fat JAR produced by mvn clean package into the Lambda task root
COPY target/*.jar ${LAMBDA_TASK_ROOT}/
CMD ["com.smartretail.lambda.kinesis.KinesisConsumerHandler::handleRequest"]
