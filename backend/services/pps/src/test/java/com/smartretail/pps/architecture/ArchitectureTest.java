package com.smartretail.pps.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {

    private static final String DOMAIN_PACKAGES = "com.smartretail.pps.domain..";
    private static final String PORT_PACKAGES   = "com.smartretail.pps.port..";

    private final JavaClasses classes = new ClassFileImporter()
            .importPackages("com.smartretail.pps");

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
    void domainMustNotDependOnSpringFramework() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(DOMAIN_PACKAGES)
                .and().haveSimpleNameNotContaining("UseCase")
                .should().dependOnClassesThat().resideInAPackage("org.springframework..");
        rule.check(classes);
    }

    @Test
    void adaptersMustNotDependOnEachOther() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.smartretail.pps.adapter.inbound..")
                .should().dependOnClassesThat().resideInAPackage("com.smartretail.pps.adapter.outbound..");
        rule.check(classes);
    }
}
