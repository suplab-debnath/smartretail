---
applyTo: "**/pom.xml"
---

# Maven Instructions -- SmartRetail

## Multi-module layout
```
backend/
  pom.xml                  <- parent POM (groupId: com.smartretail)
  services/
    sis/ ims/ re/ ars/ dfs/ sup/ pps/
      pom.xml              <- service module, inherits parent
  migrations/
    pom.xml                <- Flyway migrations module
  adapters/
    batch-post-processor/
    ml-trigger/
  coverage/
    pom.xml                <- JaCoCo aggregate report module
```

## Versions to use
- Java: 21
- Spring Boot: 3.3.x (via spring-boot-starter-parent or BOM)
- openapi-generator-maven-plugin: 7.x
- jacoco-maven-plugin: 0.8.x
- testcontainers: 1.20.x
- archunit: 1.3.x

## openapi-generator plugin (required in every service POM)
```xml
<plugin>
  <groupId>org.openapitools</groupId>
  <artifactId>openapi-generator-maven-plugin</artifactId>
  <executions>
    <execution>
      <goals><goal>generate</goal></goals>
      <configuration>
        <inputSpec>${project.basedir}/src/main/resources/{service}-api.yaml</inputSpec>
        <generatorName>spring</generatorName>
        <apiPackage>com.smartretail.{service}.adapter.inbound.rest.generated</apiPackage>
        <modelPackage>com.smartretail.{service}.adapter.inbound.rest.generated.model</modelPackage>
        <configOptions>
          <useSpringBoot3>true</useSpringBoot3>
          <interfaceOnly>true</interfaceOnly>
          <useTags>true</useTags>
          <dateLibrary>java8</dateLibrary>
        </configOptions>
      </configuration>
    </execution>
  </executions>
</plugin>
```

## JaCoCo (in every service + aggregate in coverage/)
Service POM: instrument with `prepare-agent`, report with `report`
Aggregate: uses `report-aggregate` goal in `backend/coverage/pom.xml`

## Build order
`mvn generate-sources` before any compilation (generates OpenAPI stubs)
`mvn clean verify` runs tests + generates JaCoCo report
`mvn spring-boot:run -Dspring-boot.run.profiles=local` for local dev

## Flyway
Flyway plugin is in the `backend/migrations` module only.
Services have `flyway.enabled: false` -- migrations run explicitly, not at startup.
