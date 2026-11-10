package de.fizkarlsruhe.konclude.owlapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.InconsistentOntologyException;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.Node;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.ReasonerInternalException;

class KoncludeReasonerTest {

    static final String NS = "http://example.org/features#";

    OWLOntologyManager manager;
    OWLDataFactory df;
    OWLOntology ontology;
    OWLReasoner reasoner;

    static KoncludeSettings settings() {
        KoncludeSettings s = KoncludeSettings.detect();
        Assumptions.assumeFalse(s.getCommand().isEmpty(), "Konclude not found (set -Dkonclude.path)");
        return s;
    }

    @BeforeEach
    void setUp() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        df = manager.getOWLDataFactory();
        try (InputStream in = getClass().getResourceAsStream("/features.ofn")) {
            ontology = manager.loadOntologyFromOntologyDocument(in);
        }
        reasoner = new KoncludeReasonerFactory(settings()).createReasoner(ontology);
        reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY);
    }

    @AfterEach
    void tearDown() {
        if (reasoner != null) {
            reasoner.dispose();
        }
    }

    OWLClass c(String name) {
        return df.getOWLClass(IRI.create(NS + name));
    }

    OWLNamedIndividual i(String name) {
        return df.getOWLNamedIndividual(IRI.create(NS + name));
    }

    OWLObjectProperty op(String name) {
        return df.getOWLObjectProperty(IRI.create(NS + name));
    }

    OWLDataProperty dp(String name) {
        return df.getOWLDataProperty(IRI.create(NS + name));
    }

    static <E extends OWLEntity> Set<String> names(NodeSet<E> nodes) {
        Set<String> out = new HashSet<>();
        for (E e : nodes.getFlattened()) {
            out.add(e.getIRI().getShortForm().replace(NS, ""));
        }
        return out;
    }

    static <E extends OWLEntity> Set<String> names(Node<E> node) {
        Set<String> out = new HashSet<>();
        for (E e : node.getEntities()) {
            out.add(e.getIRI().getShortForm());
        }
        return out;
    }

    static Set<String> propNames(NodeSet<org.semanticweb.owlapi.model.OWLObjectPropertyExpression> nodes) {
        Set<String> out = new HashSet<>();
        for (org.semanticweb.owlapi.model.OWLObjectPropertyExpression p : nodes.getFlattened()) {
            if (!p.isAnonymous()) {
                out.add(p.asOWLObjectProperty().getIRI().getShortForm());
            }
        }
        return out;
    }

    static Set<String> set(String... s) {
        return new HashSet<>(java.util.Arrays.asList(s));
    }

    @Test
    void consistencyAndUnsatisfiableClasses() {
        assertTrue(reasoner.isConsistent());
        assertEquals(set("Nothing", "Impossible"), names(reasoner.getUnsatisfiableClasses()));
        assertFalse(reasoner.isSatisfiable(c("Impossible")));
        assertTrue(reasoner.isSatisfiable(c("Mother")));
    }

    @Test
    void classHierarchy() {
        assertEquals(set("Person", "Human"), names(reasoner.getEquivalentClasses(c("Person"))));
        assertEquals(set("Person", "Human"), names(reasoner.getSuperClasses(c("Woman"), true)));
        assertEquals(set("Woman", "Parent"), names(reasoner.getSuperClasses(c("Mother"), true)));
        assertTrue(names(reasoner.getSuperClasses(c("Mother"), false)).containsAll(set("Thing", "Person", "Parent")));
        assertTrue(names(reasoner.getSubClasses(c("Person"), true)).containsAll(set("Woman", "Man", "Parent")));
        // the bottom node contains owl:Nothing and the unsatisfiable class
        assertEquals(set("Nothing", "Impossible"), names(reasoner.getSubClasses(c("Mother"), true)));
        assertTrue(names(reasoner.getSubClasses(df.getOWLThing(), true)).contains("Person"));
    }

    @Test
    void realization() {
        assertTrue(names(reasoner.getTypes(i("anna"), true))
                .containsAll(set("Mother", "Grandparent", "GermanResident", "Adult")));
        assertTrue(names(reasoner.getTypes(i("bob"), true)).contains("Father"));
        assertTrue(names(reasoner.getTypes(i("dora"), false)).contains("Person"));
        assertFalse(names(reasoner.getTypes(i("carl"), false)).contains("Adult"));
        assertEquals(set("germany", "france"), names(reasoner.getInstances(c("EUCountry"), false)));
        assertTrue(names(reasoner.getInstances(c("Person"), false)).containsAll(set("anna", "bob", "robert", "carl", "dora")));
        assertFalse(names(reasoner.getInstances(c("Person"), true)).contains("anna"));
    }

    @Test
    void propertyValuesAndSameIndividuals() {
        assertEquals(set("bob", "robert", "carl", "dora"), names(reasoner.getObjectPropertyValues(i("anna"), op("hasDescendant"))));
        assertEquals(set("carl", "dora"), names(reasoner.getObjectPropertyValues(i("anna"), op("hasGrandchild"))));
        assertEquals(set("germany"), names(reasoner.getObjectPropertyValues(i("karlsruhe"), op("locatedIn"))));
        assertEquals(set("anna"), names(reasoner.getObjectPropertyValues(i("dora"), op("knows"))));
        assertEquals(set("bob", "robert"),
                names(reasoner.getObjectPropertyValues(i("carl"), df.getOWLObjectInverseOf(op("hasChild")))));
        assertEquals(set("bob", "robert"), names(reasoner.getSameIndividuals(i("bob"))));
        assertTrue(reasoner.getDataPropertyValues(i("anna"), dp("yearsLived")).contains(df.getOWLLiteral(70)));
    }

    @Test
    void propertyHierarchy() {
        assertTrue(propNames(reasoner.getSuperObjectProperties(op("hasChild"), true)).contains("hasDescendant"));
        assertTrue(propNames(reasoner.getSubObjectProperties(op("locatedIn"), false)).contains("partOf"));
        assertTrue(reasoner.getInverseObjectProperties(op("hasChild")).contains(op("hasParent")));
        assertEquals(set("yearsLived"), names(reasoner.getSuperDataProperties(dp("age"), true)));
    }

    @Test
    void classExpressionQueries() {
        // DL query: Person and hasChild some Man
        assertEquals(set("anna"), names(reasoner.getInstances(
                df.getOWLObjectIntersectionOf(c("Person"), df.getOWLObjectSomeValuesFrom(op("hasChild"), c("Man"))), false)));
        assertEquals(set("Parent"), names(reasoner.getEquivalentClasses(
                df.getOWLObjectIntersectionOf(c("Person"), df.getOWLObjectSomeValuesFrom(op("hasChild"), c("Human"))))));
        assertFalse(reasoner.isSatisfiable(df.getOWLObjectIntersectionOf(c("Mother"), c("Father"))));
        assertTrue(names(reasoner.getDisjointClasses(c("Woman"))).containsAll(set("Man", "Father", "Impossible")));
        assertTrue(names(reasoner.getObjectPropertyDomains(op("hasChild"), true)).contains("Parent"));
        assertTrue(names(reasoner.getObjectPropertyRanges(op("hasChild"), false)).contains("Person"));
    }

    @Test
    void entailment() {
        assertTrue(reasoner.isEntailed(df.getOWLSubClassOfAxiom(c("Mother"), c("Person"))));
        assertFalse(reasoner.isEntailed(df.getOWLSubClassOfAxiom(c("Person"), c("Mother"))));
        assertTrue(reasoner.isEntailed(df.getOWLSubClassOfAxiom(c("Mother"),
                df.getOWLObjectSomeValuesFrom(op("hasDescendant"), c("Person")))));
        assertTrue(reasoner.isEntailed(df.getOWLClassAssertionAxiom(c("GermanResident"), i("anna"))));
        assertTrue(reasoner.isEntailed(df.getOWLObjectPropertyAssertionAxiom(op("hasChild"), i("robert"), i("dora"))));
        assertTrue(reasoner.isEntailed(df.getOWLDisjointClassesAxiom(c("Mother"), c("Father"))));
        assertTrue(reasoner.isEntailed(df.getOWLEquivalentClassesAxiom(c("Human"), c("Person"))));
    }

    @Test
    void bufferedChangesApplyAfterFlush() {
        manager.addAxiom(ontology, df.getOWLClassAssertionAxiom(c("Man"), i("carl")));
        assertFalse(names(reasoner.getTypes(i("carl"), false)).contains("Man"), "buffered change visible before flush");
        reasoner.flush();
        assertTrue(names(reasoner.getTypes(i("carl"), false)).contains("Man"));
        manager.addAxiom(ontology, df.getOWLClassAssertionAxiom(c("Woman"), i("carl")));
        reasoner.flush();
        assertFalse(reasoner.isConsistent());
        assertThrows(InconsistentOntologyException.class, () -> reasoner.getTypes(i("carl"), false));
    }

    @Test
    void reportsMissingExecutable() {
        KoncludeSettings broken = new KoncludeSettings(java.util.Collections.singletonList("/nonexistent/Konclude"),
                "1", null, false);
        OWLReasoner r = new KoncludeReasonerFactory(broken).createReasoner(ontology);
        ReasonerInternalException e = assertThrows(ReasonerInternalException.class, r::isConsistent);
        assertTrue(e.getMessage().contains("Cannot start Konclude"), e.getMessage());
        r.dispose();
    }

    /** A command that repeats "Konclude" (as with a Docker image whose entrypoint is Konclude). */
    static KoncludeSettings repeatedKonclude() {
        java.util.List<String> cmd = new java.util.ArrayList<>(settings().getCommand());
        cmd.add("Konclude");
        return new KoncludeSettings(cmd, "AUTO", null, false);
    }

    @Test
    void explainsUsageOutput() {
        OWLReasoner r = new KoncludeReasonerFactory(repeatedKonclude()).createReasoner(ontology);
        ReasonerInternalException e = assertThrows(ReasonerInternalException.class, r::isConsistent);
        assertTrue(e.getMessage().contains("usage help"), e.getMessage());
        assertTrue(e.getMessage().contains("do not add 'Konclude'"), e.getMessage());
        r.dispose();
    }

    @Test
    void selfTest() {
        assertTrue(KoncludeSelfTest.run(settings()).startsWith("test reasoning passed"));
        RuntimeException e = assertThrows(RuntimeException.class, () -> KoncludeSelfTest.run(repeatedKonclude()));
        assertTrue(e.getMessage().contains("usage help"), e.getMessage());
    }

    @Test
    void tokenizer() {
        assertEquals(java.util.Arrays.asList("/opt/my konclude/Konclude", "-x", "a b"),
                KoncludeSettings.tokenize("\"/opt/my konclude/Konclude\" -x 'a b'"));
    }
}
