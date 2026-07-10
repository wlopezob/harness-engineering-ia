package com.gentleman;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import com.tngtech.archunit.core.importer.ImportOption;

@AnalyzeClasses(packages = "com.gentleman", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule el_dominio_no_depende_de_frameworks =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "jakarta..",
                            "io.quarkus..",
                            "org.hibernate..",
                            "..application..",
                            "..infrastructure.."
                    )
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule las_capas_respetan_la_direccion =
            layeredArchitecture().consideringOnlyDependenciesInLayers()
                    .optionalLayer("domain").definedBy("..domain..")
                    .optionalLayer("application").definedBy("..application..")
                    .optionalLayer("infrastructure").definedBy("..infrastructure..")
                    .whereLayer("infrastructure").mayNotBeAccessedByAnyLayer()
                    .whereLayer("application").mayOnlyBeAccessedByLayers("infrastructure")
                    .whereLayer("domain").mayOnlyBeAccessedByLayers("application", "infrastructure");

    @ArchTest
    static final ArchRule el_nucleo_es_inmutable =
            fields().that().areDeclaredInClassesThat()
                    .resideInAnyPackage("..domain..", "..application..")
                    .should().beFinal()
                    .allowEmptyShould(true);
}