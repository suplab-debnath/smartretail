package com.smartretail.re.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {

    private static final String DOMAIN_PACKAGES = "com.smartretail.re.domain..";
    private static final String PORT_PACKAGES   = "com.smartretail.re.port..";

    private final JavaClasses classes = new ClassFileImporter()
            .importPackages("com.smartretail.re");

    @Test
    void domainMustNotDependOnAwsSdk() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(DOMAIN_PACKAGES)
                .should().dependOnClassesThat().resideInAPackage("software.amazon..");
        rule.check(classes);
    }

    @Test
    void portsMustNotDependOnAwsSdk() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(PORT_PACKAGES)
                .should().dependOnClassesThat().resideInAPackage("software.amazon..");
        rule.check(classes);
    }

    @Test
    void domainMustNotDependOnSpringWeb() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(DOMAIN_PACKAGES)
                .and().haveSimpleNameNotContaining("UseCase")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.web..");
        rule.check(classes);
    }

    @Test
    void inboundAdaptersMustNotDependOnOutboundAdapters() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.smartretail.re.adapter.inbound..")
                .should().dependOnClassesThat().resideInAPackage("com.smartretail.re.adapter.outbound..");
        rule.check(classes);
    }
}
