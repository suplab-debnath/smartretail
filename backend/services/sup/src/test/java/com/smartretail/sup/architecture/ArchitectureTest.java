package com.smartretail.sup.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {

    private static final String DOMAIN_PACKAGES = "com.smartretail.sup.domain..";
    private static final String PORT_PACKAGES   = "com.smartretail.sup.port..";

    private final JavaClasses classes = new ClassFileImporter()
            .importPackages("com.smartretail.sup");

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
                .that().resideInAPackage("com.smartretail.sup.adapter.inbound..")
                .should().dependOnClassesThat().resideInAPackage("com.smartretail.sup.adapter.outbound..");
        rule.check(classes);
    }
}
