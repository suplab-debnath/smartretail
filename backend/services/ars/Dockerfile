FROM eclipse-temurin:21-jre-alpine

RUN addgroup -g 1001 -S spring && \
    adduser -S -u 1001 -G spring spring

WORKDIR /app
COPY target/*.jar app.jar
RUN chown -R spring:spring /app
USER spring

EXPOSE 8083

ENV JAVA_OPTS="\
    -XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75 \
    -XX:+UseG1GC \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/tmp"

HEALTHCHECK --interval=30s --timeout=5s --retries=3 --start-period=40s \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8083/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
