package com.sismics.docs.architecture;

import com.sismics.docs.infrastructure.persistence.JpaTransactionRunner;
import com.sismics.docs.rest.resource.BaseResource;
import com.sismics.docs.rest.util.DocumentResourceHelper;
import com.sismics.rest.util.ValidationUtil;
import com.sismics.util.context.ThreadLocalContext;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Executable enforcement of the Phase G layering contract. The document slice's application layer is
 * framework-free, its REST edge depends only on the application + a small allow-list, only the
 * transaction runner touches {@code ThreadLocalContext}, no new code bypasses the non-flushing
 * entity-manager accessor, DAO construction is confined to the persistence adapter, and the legacy
 * {@code rest.resource -> core.dao} web is frozen so it can only shrink as resources migrate.
 *
 * <p>Test classes are excluded from the analyzed universe ({@link ImportOption.DoNotIncludeTests}).</p>
 */
@AnalyzeClasses(packages = "com.sismics", importOptions = ImportOption.DoNotIncludeTests.class)
public class DocumentSliceArchitectureTest {

    private static final String[] NEW_PACKAGES = {
            "com.sismics.docs.application..",
            "com.sismics.docs.infrastructure..",
            "com.sismics.docs.bootstrap..",
            "com.sismics.docs.rest.document.."
    };

    private static boolean inAny(JavaClass clazz, String... packageGlobs) {
        return JavaClass.Predicates.resideInAnyPackage(packageGlobs).test(clazz);
    }

    // Rule 1 — REST edge allow-list (positive form): rest.document may depend ONLY on the enumerated
    // packages/classes (its own package, the application, the composition root, the JDK, JAX-RS/JSON,
    // the inherited authentication context, the error taxonomy, the two sanctioned helpers, the domain
    // constants, and logging).
    @ArchTest
    static final ArchRule rest_edge_allow_list = classes()
            .that().resideInAPackage("..rest.document..")
            .should().onlyDependOnClassesThat(
                    DescribedPredicate.describe("the Phase G REST-edge allow-list",
                            (JavaClass clazz) -> inAny(clazz,
                                            "java..",
                                            "com.sismics.docs.rest.document..",
                                            "com.sismics.docs.application..",
                                            "com.sismics.docs.bootstrap..",
                                            "com.sismics.docs.core.constant..",
                                            "com.sismics.security..",
                                            "com.sismics.rest.exception..",
                                            "jakarta.ws.rs..",
                                            "jakarta.json..",
                                            "jakarta.servlet..",
                                            "org.slf4j..",
                                            "com.google.common..")
                                    || clazz.isEquivalentTo(BaseResource.class)
                                    || clazz.isEquivalentTo(ValidationUtil.class)
                                    || clazz.isEquivalentTo(DocumentResourceHelper.class)));

    // Rule 1b — member-level restriction: the ONLY DocumentResourceHelper member reachable from
    // rest.document is sanitizeDescription; its DAO-touching methods are unreachable from the edge.
    @ArchTest
    static final ArchRule rest_edge_helper_member = noClasses()
            .that().resideInAPackage("..rest.document..")
            .should().callMethodWhere(DescribedPredicate.describe(
                    "call a DocumentResourceHelper method other than sanitizeDescription",
                    (JavaMethodCall call) -> call.getTargetOwner().isEquivalentTo(DocumentResourceHelper.class)
                            && !call.getTarget().getName().equals("sanitizeDescription")))
            .as("rest.document may only call DocumentResourceHelper.sanitizeDescription");

    // Rule 2 — application purity: no framework/persistence/JSON/AppContext/ThreadLocalContext/infra/rest.
    @ArchTest
    static final ArchRule application_is_pure = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.glassfish.jersey..",
                    "jakarta.ws.rs..",
                    "jakarta.servlet..",
                    "jakarta.persistence..",
                    "org.hibernate..",
                    "jakarta.json..",
                    "com.sismics.docs.core.model.context..",
                    "com.sismics.util.context..",
                    "com.sismics.docs.infrastructure..",
                    "com.sismics.docs.rest..");

    // Rule 2b — application ports expose no DAO classes and no JPA entity types.
    @ArchTest
    static final ArchRule application_no_dao_or_entities = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat(DescribedPredicate.describe(
                    "a core DAO class or a JPA entity type",
                    (JavaClass clazz) -> clazz.getPackageName().equals("com.sismics.docs.core.dao")
                            || inAny(clazz, "com.sismics.docs.core.model.jpa..")));

    // Rule 3 — AppContext is captured by no new-package class.
    @ArchTest
    static final ArchRule no_appcontext_in_new_packages = noClasses()
            .that().resideInAnyPackage(NEW_PACKAGES)
            .should().dependOnClassesThat().resideInAPackage("com.sismics.docs.core.model.context..");

    // Rule 4 — the flushing getEntityManager accessor is called by no new-package class.
    @ArchTest
    static final ArchRule no_get_entity_manager = noClasses()
            .that().resideInAnyPackage(NEW_PACKAGES)
            .should().callMethodWhere(DescribedPredicate.describe(
                    "ThreadLocalContext.getEntityManager()",
                    (JavaMethodCall call) -> call.getTargetOwner().isEquivalentTo(ThreadLocalContext.class)
                            && call.getTarget().getName().equals("getEntityManager")));

    // Rule 5 — ThreadLocalContext ownership: only JpaTransactionRunner may depend on it.
    @ArchTest
    static final ArchRule single_threadlocalcontext_owner = noClasses()
            .that().resideInAnyPackage(NEW_PACKAGES)
            .and().doNotHaveFullyQualifiedName(JpaTransactionRunner.class.getName())
            .should().dependOnClassesThat().resideInAPackage("com.sismics.util.context..");

    // Rule 6 — DAO construction is prohibited in EVERY new package except the persistence adapter
    // (the contract confines DAO construction to infrastructure.persistence alone).
    @ArchTest
    static final ArchRule no_dao_construction_outside_persistence = noClasses()
            .that().resideInAnyPackage(
                    "com.sismics.docs.application..",
                    "com.sismics.docs.rest.document..",
                    "com.sismics.docs.bootstrap..",
                    "com.sismics.docs.infrastructure.runtime..")
            .should().callConstructorWhere(DescribedPredicate.describe(
                    "construct a core DAO",
                    (JavaConstructorCall call) -> call.getTargetOwner().getPackageName()
                            .equals("com.sismics.docs.core.dao")));

    // Rule 7 — the legacy rest.resource -> core.dao web is FROZEN: it may only shrink (the ratchet); any
    // NEW resource->DAO edge fails. The committed store is the baseline.
    @ArchTest
    static final ArchRule legacy_resource_dao_frozen = FreezingArchRule.freeze(
            noClasses()
                    .that().resideInAPackage("com.sismics.docs.rest.resource..")
                    .should().dependOnClassesThat().resideInAPackage("com.sismics.docs.core.dao"));

    // Rule 8 — legacy direct ThreadLocalContext dependencies are FROZEN: existing sites are
    // grandfathered in the committed store, and any NEW ThreadLocalContext use in a legacy resource
    // goes red — new transactional coordination must come through the slice's UnitOfWork instead.
    @ArchTest
    static final ArchRule legacy_resource_threadlocalcontext_frozen = FreezingArchRule.freeze(
            noClasses()
                    .that().resideInAPackage("com.sismics.docs.rest.resource..")
                    .should().dependOnClassesThat().resideInAPackage("com.sismics.util.context.."));

    // Direction pin (vacuously green today): a future domain package must not depend on the outer layers.
    @ArchTest
    static final ArchRule domain_depends_on_nothing_outer = noClasses()
            .that().resideInAPackage("com.sismics.docs.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.sismics.docs.application..",
                    "com.sismics.docs.infrastructure..",
                    "com.sismics.docs.rest..")
            // No com.sismics.docs.domain package exists yet; this rule pins the direction for a future
            // slice, so it is vacuously satisfied today.
            .allowEmptyShould(true);
}
