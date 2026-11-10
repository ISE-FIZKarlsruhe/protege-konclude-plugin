package de.fizkarlsruhe.konclude.owlapi;

import java.util.ArrayList;
import java.util.List;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;

/**
 * Runs a tiny reasoning job through the configured command and temp directory, exactly as the
 * reasoner does (so Docker command or volume mistakes show up here, not only when reasoning).
 */
public final class KoncludeSelfTest {

    private KoncludeSelfTest() {
    }

    /**
     * @return a short success description
     * @throws RuntimeException with an explanatory message if Konclude cannot be run or gives a wrong result
     */
    public static String run(KoncludeSettings settings) {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        String ns = "urn:konclude-protege:selftest#";
        OWLClass a = df.getOWLClass(IRI.create(ns + "A"));
        OWLClass b = df.getOWLClass(IRI.create(ns + "B"));
        OWLNamedIndividual x = df.getOWLNamedIndividual(IRI.create(ns + "x"));
        List<OWLAxiom> axioms = new ArrayList<>();
        axioms.add(df.getOWLDeclarationAxiom(a));
        axioms.add(df.getOWLDeclarationAxiom(b));
        axioms.add(df.getOWLDeclarationAxiom(x));
        axioms.add(df.getOWLSubClassOfAxiom(a, b));
        axioms.add(df.getOWLClassAssertionAxiom(a, x));
        KoncludeRunner runner = new KoncludeRunner(settings, df);
        long start = System.currentTimeMillis();
        try {
            Materialization m = runner.materialize(axioms, 120000);
            if (m.inconsistent || !m.typesOf(x.getIRI()).contains(b.getIRI())) {
                throw new IllegalStateException("Konclude ran, but returned an unexpected result for the test ontology");
            }
        } finally {
            runner.dispose();
        }
        return "test reasoning passed (" + (System.currentTimeMillis() - start) + " ms)";
    }

    /**
     * Starts (or contacts) the OWLlink server used for explanations and runs one entailment check.
     *
     * @return a short success description, or null if OWLlink is switched off
     */
    public static String runOwlLink(KoncludeSettings settings) throws java.io.IOException {
        if (settings.getOwlLinkMode() == KoncludeSettings.OwlLinkMode.OFF) {
            return null;
        }
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        String ns = "urn:konclude-protege:selftest#";
        OWLClass a = df.getOWLClass(IRI.create(ns + "A"));
        OWLClass b = df.getOWLClass(IRI.create(ns + "B"));
        long start = System.currentTimeMillis();
        OwlLinkClient client = new OwlLinkClient(OwlLinkServer.endpoint(settings), df);
        long started = System.currentTimeMillis();
        boolean[] r = client.check(java.util.Collections.<OWLAxiom>singletonList(df.getOWLSubClassOfAxiom(a, b)),
                java.util.Collections.<org.semanticweb.owlapi.model.OWLClassExpression>singletonList(
                        df.getOWLObjectIntersectionOf(a, df.getOWLObjectComplementOf(b))), 60000);
        if (!r[0] || r[1]) {
            throw new IllegalStateException("The Konclude OWLlink server returned an unexpected result");
        }
        return "OWLlink server OK (" + (started - start) + " ms to start/contact, "
                + (System.currentTimeMillis() - started) + " ms per check)";
    }
}
