package de.fizkarlsruhe.konclude.owlapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.semanticweb.owl.explanation.api.Explanation;
import org.semanticweb.owl.explanation.api.ExplanationGenerator;
import org.semanticweb.owl.explanation.api.ExplanationGeneratorFactory;
import org.semanticweb.owl.explanation.api.ExplanationManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * Explanations as Protégé's explanation workbench computes them (owlexplanation, black box), with Konclude
 * and with HermiT. Extra ontology: {@code -Dexplain.ontology=path}.
 */
class ExplanationTest {

    static final String NS = KoncludeReasonerTest.NS;

    @Test
    void sameJustificationsAsHermit() throws Exception {
        KoncludeSettings settings = KoncludeReasonerTest.settings();
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology;
        try (InputStream in = getClass().getResourceAsStream("/features.ofn")) {
            ontology = manager.loadOntologyFromOntologyDocument(in);
        }
        List<OWLAxiom> entailments = new ArrayList<>();
        entailments.add(df.getOWLSubClassOfAxiom(c(df, "Mother"), c(df, "Person")));
        entailments.add(df.getOWLClassAssertionAxiom(c(df, "Grandparent"), df.getOWLNamedIndividual(IRI.create(NS + "anna"))));
        entailments.add(df.getOWLSubClassOfAxiom(c(df, "Impossible"), df.getOWLNothing()));
        entailments.add(df.getOWLObjectPropertyAssertionAxiom(df.getOWLObjectProperty(IRI.create(NS + "hasGrandchild")),
                df.getOWLNamedIndividual(IRI.create(NS + "anna")), df.getOWLNamedIndividual(IRI.create(NS + "dora"))));
        int mismatches = explainAll("features.ofn", ontology, entailments, settings);

        String extra = System.getProperty("explain.ontology", "");
        if (!extra.isEmpty()) {
            OWLOntology o = OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(new File(extra));
            mismatches += explainAll(new File(extra).getName(), o, inferredSubsumptions(o, settings, 2), settings);
        }
        assertEquals(0, mismatches);
    }

    private static OWLClass c(OWLDataFactory df, String name) {
        return df.getOWLClass(IRI.create(NS + name));
    }

    /** A few inferred, not asserted, subsumptions between named classes. */
    private static List<OWLAxiom> inferredSubsumptions(OWLOntology o, KoncludeSettings settings, int max) {
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLReasoner r = new KoncludeReasonerFactory(settings).createReasoner(o);
        List<OWLAxiom> out = new ArrayList<>();
        for (OWLClass c : o.getClassesInSignature(Imports.INCLUDED)) {
            for (OWLClass sup : r.getSuperClasses(c, true).getFlattened()) {
                OWLAxiom ax = df.getOWLSubClassOfAxiom(c, sup);
                if (!sup.isOWLThing() && !o.containsAxiom(ax, Imports.INCLUDED, org.semanticweb.owlapi.model.parameters.AxiomAnnotations.IGNORE_AXIOM_ANNOTATIONS)) {
                    out.add(ax);
                    if (out.size() >= max) {
                        r.dispose();
                        return out;
                    }
                }
            }
        }
        r.dispose();
        return out;
    }

    private int explainAll(String name, OWLOntology ontology, List<OWLAxiom> entailments, KoncludeSettings settings)
            throws Exception {
        int mismatches = 0;
        for (OWLAxiom entailment : entailments) {
            long runs0 = KoncludeRunner.TOTAL_RUNS.get();
            long req0 = OwlLinkClient.TOTAL_REQUESTS.get();
            long t0 = System.currentTimeMillis();
            Set<Set<OWLAxiom>> konclude = justifications(new KoncludeReasonerFactory(settings), ontology, entailment);
            long t1 = System.currentTimeMillis();
            long runs = KoncludeRunner.TOTAL_RUNS.get() - runs0;
            long requests = OwlLinkClient.TOTAL_REQUESTS.get() - req0;
            Set<Set<OWLAxiom>> hermit = justifications(new org.semanticweb.HermiT.ReasonerFactory(), ontology, entailment);
            long t2 = System.currentTimeMillis();
            boolean same = konclude.equals(hermit);
            if (!same) {
                mismatches++;
            }
            System.out.printf("%s | %s%n  Konclude (%s): %d justification(s), %d Konclude runs, %d OWLlink requests, %d ms"
                    + " | HermiT: %d, %d ms | %s%n", name, entailment, settings.getOwlLinkMode(), konclude.size(), runs,
                    requests, t1 - t0, hermit.size(), t2 - t1, same ? "same" : "DIFFERENT");
            if (settings.getOwlLinkMode() != KoncludeSettings.OwlLinkMode.OFF) {
                assertEquals(0, runs, "explanations should not start Konclude processes in OWLlink mode");
            }
            assertFalse(konclude.isEmpty(), "no justification found by Konclude for " + entailment);
        }
        return mismatches;
    }

    private static Set<Set<OWLAxiom>> justifications(OWLReasonerFactory rf, OWLOntology ontology, OWLAxiom entailment)
            throws Exception {
        ExplanationGeneratorFactory<OWLAxiom> factory = ExplanationManager.createExplanationGeneratorFactory(rf);
        ExplanationGenerator<OWLAxiom> generator = factory.createExplanationGenerator(ontology);
        Set<Set<OWLAxiom>> out = new HashSet<>();
        for (Explanation<OWLAxiom> e : generator.getExplanations(entailment)) {
            out.add(e.getAxioms());
        }
        return out;
    }
}
