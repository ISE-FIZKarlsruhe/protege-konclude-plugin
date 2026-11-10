package de.fizkarlsruhe.konclude.owlapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.Node;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

/**
 * Compares the Konclude reasoner with HermiT on the bundled fixture and on the ontologies listed in
 * {@code -Dcompare.ontologies=path1,path2}.
 */
class HermitComparisonTest {

    @Test
    void sameInferencesAsHermit() throws Exception {
        KoncludeSettings settings = KoncludeReasonerTest.settings();
        List<String> sources = new ArrayList<>();
        sources.add(new File(getClass().getResource("/features.ofn").toURI()).getAbsolutePath());
        String extra = System.getProperty("compare.ontologies", "");
        for (String s : extra.split(",")) {
            if (!s.trim().isEmpty()) {
                sources.add(s.trim());
            }
        }
        int total = 0;
        for (String source : sources) {
            total += compare(new File(source), settings);
        }
        assertEquals(0, total, "mismatches between Konclude and HermiT (see output)");
    }

    private int compare(File file, KoncludeSettings settings) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(file);
        // Konclude ignores SWRL rules, HermiT applies them: compare on the rule-free ontology
        int rules = 0;
        for (OWLOntology o : ontology.getImportsClosure()) {
            Set<org.semanticweb.owlapi.model.SWRLRule> r = o.getAxioms(org.semanticweb.owlapi.model.AxiomType.SWRL_RULE);
            rules += r.size();
            manager.removeAxioms(o, r);
        }
        long t0 = System.currentTimeMillis();
        OWLReasoner konclude = new KoncludeReasonerFactory(settings).createReasoner(ontology);
        konclude.precomputeInferences(InferenceType.CLASS_HIERARCHY);
        long t1 = System.currentTimeMillis();
        OWLReasoner hermit;
        try {
            hermit = new org.semanticweb.HermiT.ReasonerFactory().createReasoner(ontology);
        } catch (IllegalArgumentException e) {
            // e.g. ontologies outside OWL 2 DL, which HermiT rejects
            System.out.printf("%s: Konclude %d ms, HermiT rejected the ontology: %s%n", file.getName(), t1 - t0,
                    e.getMessage());
            konclude.dispose();
            return 0;
        }
        hermit.precomputeInferences(InferenceType.CLASS_HIERARCHY, InferenceType.CLASS_ASSERTIONS,
                InferenceType.OBJECT_PROPERTY_HIERARCHY, InferenceType.DATA_PROPERTY_HIERARCHY);
        long t2 = System.currentTimeMillis();
        List<String> diffs = new ArrayList<>();
        int checks = 0;
        try {
            check(diffs, "consistent", hermit.isConsistent(), konclude.isConsistent());
            checks++;
            if (hermit.isConsistent()) {
                check(diffs, "unsatisfiable", flat(hermit.getUnsatisfiableClasses()),
                        flat(konclude.getUnsatisfiableClasses()));
                for (OWLClass c : ontology.getClassesInSignature(Imports.INCLUDED)) {
                    check(diffs, "equivalent " + c, flat(hermit.getEquivalentClasses(c)), flat(konclude.getEquivalentClasses(c)));
                    check(diffs, "direct supers " + c, nodes(hermit.getSuperClasses(c, true)), nodes(konclude.getSuperClasses(c, true)));
                    check(diffs, "direct subs " + c, nodes(hermit.getSubClasses(c, true)), nodes(konclude.getSubClasses(c, true)));
                    checks += 3;
                }
                Set<OWLObjectProperty> props = ontology.getObjectPropertiesInSignature(Imports.INCLUDED);
                Set<OWLDataProperty> dataProps = ontology.getDataPropertiesInSignature(Imports.INCLUDED);
                for (OWLObjectProperty p : props) {
                    check(diffs, "super properties " + p, namedNodes(hermit.getSuperObjectProperties(p, false)),
                            namedNodes(konclude.getSuperObjectProperties(p, false)));
                    checks++;
                }
                for (OWLNamedIndividual i : ontology.getIndividualsInSignature(Imports.INCLUDED)) {
                    check(diffs, "direct types " + i, nodes(hermit.getTypes(i, true)), nodes(konclude.getTypes(i, true)));
                    check(diffs, "types " + i, nodes(hermit.getTypes(i, false)), nodes(konclude.getTypes(i, false)));
                    checks += 2;
                    for (OWLObjectProperty p : props) {
                        check(diffs, "values " + i + " " + p, flat(hermit.getObjectPropertyValues(i, p)),
                                flat(konclude.getObjectPropertyValues(i, p)));
                        checks++;
                    }
                    for (OWLDataProperty p : dataProps) {
                        check(diffs, "data values " + i + " " + p, literals(hermit.getDataPropertyValues(i, p)),
                                literals(konclude.getDataPropertyValues(i, p)));
                        checks++;
                    }
                }
            }
        } finally {
            konclude.dispose();
            hermit.dispose();
        }
        System.out.printf("%s: Konclude %d ms, HermiT %d ms, %d checks, %d mismatches%s%n", file.getName(), t1 - t0,
                t2 - t1, checks, diffs.size(), rules > 0 ? " (" + rules + " SWRL rules removed)" : "");
        for (String d : diffs.subList(0, Math.min(25, diffs.size()))) {
            System.out.println("  " + d);
        }
        assertTrue(checks > 0);
        return diffs.size();
    }

    private static void check(List<String> diffs, String what, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            diffs.add(what + ": HermiT=" + expected + " Konclude=" + actual);
        }
    }

    private static <E extends OWLObject> Set<String> flat(NodeSet<E> nodes) {
        Set<String> out = new TreeSet<>();
        for (Node<E> n : nodes) {
            out.addAll(flat(n));
        }
        return out;
    }

    private static <E extends OWLObject> Set<String> flat(Node<E> node) {
        Set<String> out = new TreeSet<>();
        for (E e : node.getEntities()) {
            out.add(e.toString());
        }
        return out;
    }

    private static <E extends OWLObject> Set<Set<String>> nodes(NodeSet<E> nodes) {
        Set<Set<String>> out = new HashSet<>();
        for (Node<E> n : nodes) {
            out.add(flat(n));
        }
        return out;
    }

    /** Named members of property nodes (HermiT also lists inverse expressions in the nodes). */
    private static Set<Set<String>> namedNodes(NodeSet<OWLObjectPropertyExpression> nodes) {
        Set<Set<String>> out = new HashSet<>();
        for (Node<OWLObjectPropertyExpression> n : nodes) {
            Set<String> named = new TreeSet<>();
            for (OWLObjectPropertyExpression p : n.getEntities()) {
                if (!p.isAnonymous()) {
                    named.add(p.toString());
                }
            }
            if (!named.isEmpty()) {
                out.add(named);
            }
        }
        return out;
    }

    private static Set<String> literals(Set<OWLLiteral> literals) {
        Set<String> out = new TreeSet<>();
        for (OWLLiteral l : literals) {
            out.add(l.getLiteral() + (l.hasLang() ? "@" + l.getLang() : ""));
        }
        return out;
    }

    @SuppressWarnings("unused")
    private static String name(OWLEntity e) {
        return e.getIRI().toString();
    }
}
