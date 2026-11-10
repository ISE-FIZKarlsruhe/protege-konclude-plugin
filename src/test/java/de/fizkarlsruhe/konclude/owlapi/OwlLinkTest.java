package de.fizkarlsruhe.konclude.owlapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

class OwlLinkTest {

    static final String NS = KoncludeReasonerTest.NS;

    @Test
    void containerCommandGetsPortMappingAndName() {
        List<String> base = Arrays.asList("docker", "run", "--rm", "-v", "/d:/d", "konclude/konclude");
        assertTrue(OwlLinkServer.isContainerCommand(base));
        assertEquals(Arrays.asList("docker", "run", "-p", "127.0.0.1:9001:9001", "--name", "kp", "--rm", "-v", "/d:/d",
                "konclude/konclude", "owllinkserver", "-p", "9001", "-w", "AUTO"),
                OwlLinkServer.serverCommand(base, "AUTO", 9001, "kp"));
        assertTrue(OwlLinkServer.isContainerCommand(Arrays.asList("/usr/local/bin/podman", "run", "img")));
        assertFalse(OwlLinkServer.isContainerCommand(Arrays.asList("/opt/Konclude")));
        assertEquals(Arrays.asList("/opt/Konclude", "owllinkserver", "-p", "9001", "-w", "2"),
                OwlLinkServer.serverCommand(Arrays.asList("/opt/Konclude"), "2", 9001, null));
    }

    private OWLOntology fixture(OWLOntologyManager m) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/features.ofn")) {
            return m.loadOntologyFromOntologyDocument(in);
        }
    }

    /** Entailment questions of all supported types, entailed and not entailed. */
    private static List<OWLAxiom> questions(OWLDataFactory df) {
        OWLClass mother = df.getOWLClass(IRI.create(NS + "Mother"));
        OWLClass father = df.getOWLClass(IRI.create(NS + "Father"));
        OWLClass person = df.getOWLClass(IRI.create(NS + "Person"));
        OWLClass human = df.getOWLClass(IRI.create(NS + "Human"));
        OWLClass parent = df.getOWLClass(IRI.create(NS + "Parent"));
        OWLClass grandparent = df.getOWLClass(IRI.create(NS + "Grandparent"));
        OWLObjectProperty hasChild = df.getOWLObjectProperty(IRI.create(NS + "hasChild"));
        OWLObjectProperty hasParent = df.getOWLObjectProperty(IRI.create(NS + "hasParent"));
        OWLObjectProperty hasDescendant = df.getOWLObjectProperty(IRI.create(NS + "hasDescendant"));
        OWLObjectProperty partOf = df.getOWLObjectProperty(IRI.create(NS + "partOf"));
        OWLNamedIndividual anna = df.getOWLNamedIndividual(IRI.create(NS + "anna"));
        OWLNamedIndividual bob = df.getOWLNamedIndividual(IRI.create(NS + "bob"));
        OWLNamedIndividual robert = df.getOWLNamedIndividual(IRI.create(NS + "robert"));
        OWLNamedIndividual dora = df.getOWLNamedIndividual(IRI.create(NS + "dora"));
        List<OWLAxiom> q = new ArrayList<>();
        q.add(df.getOWLSubClassOfAxiom(mother, person));
        q.add(df.getOWLSubClassOfAxiom(person, mother));
        q.add(df.getOWLSubClassOfAxiom(grandparent, df.getOWLObjectSomeValuesFrom(hasDescendant, parent)));
        q.add(df.getOWLEquivalentClassesAxiom(person, human));
        q.add(df.getOWLEquivalentClassesAxiom(person, parent));
        q.add(df.getOWLDisjointClassesAxiom(mother, father));
        q.add(df.getOWLDisjointClassesAxiom(mother, grandparent));
        q.add(df.getOWLClassAssertionAxiom(grandparent, anna));
        q.add(df.getOWLClassAssertionAxiom(grandparent, dora));
        q.add(df.getOWLObjectPropertyAssertionAxiom(hasDescendant, anna, dora));
        q.add(df.getOWLObjectPropertyAssertionAxiom(hasDescendant, dora, anna));
        q.add(df.getOWLDataPropertyAssertionAxiom(df.getOWLDataProperty(IRI.create(NS + "yearsLived")), anna,
                df.getOWLLiteral(70)));
        q.add(df.getOWLSameIndividualAxiom(bob, robert));
        q.add(df.getOWLSameIndividualAxiom(bob, anna));
        q.add(df.getOWLDifferentIndividualsAxiom(anna, bob));
        q.add(df.getOWLSubObjectPropertyOfAxiom(hasChild, hasDescendant));
        q.add(df.getOWLSubObjectPropertyOfAxiom(hasDescendant, hasChild));
        q.add(df.getOWLEquivalentObjectPropertiesAxiom(hasChild, df.getOWLObjectInverseOf(hasParent)));
        q.add(df.getOWLInverseObjectPropertiesAxiom(hasChild, hasParent));
        q.add(df.getOWLInverseObjectPropertiesAxiom(hasChild, partOf));
        q.add(df.getOWLObjectPropertyDomainAxiom(hasChild, parent));
        q.add(df.getOWLObjectPropertyRangeAxiom(hasChild, person));
        q.add(df.getOWLObjectPropertyRangeAxiom(hasChild, parent));
        q.add(df.getOWLDataPropertyDomainAxiom(df.getOWLDataProperty(IRI.create(NS + "age")), person));
        return q;
    }

    @Test
    void owlLinkAnswersMatchMaterialization() throws Exception {
        KoncludeSettings base = KoncludeReasonerTest.settings();
        KoncludeSettings off = new KoncludeSettings(base.getCommand(), "AUTO", null, false,
                KoncludeSettings.OwlLinkMode.OFF, null);
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = fixture(m);
        for (OWLAxiom q : questions(m.getOWLDataFactory())) {
            // fresh reasoners, as the explanation workbench creates them
            OWLReasoner viaOwlLink = new KoncludeReasonerFactory(base).createNonBufferingReasoner(o);
            OWLReasoner viaProcess = new KoncludeReasonerFactory(off).createNonBufferingReasoner(o);
            long runs0 = KoncludeRunner.TOTAL_RUNS.get();
            boolean owlLinkAnswer = viaOwlLink.isEntailed(q);
            assertEquals(0, KoncludeRunner.TOTAL_RUNS.get() - runs0, "OWLlink check started a Konclude process: " + q);
            assertEquals(viaProcess.isEntailed(q), owlLinkAnswer, q.toString());
            viaOwlLink.dispose();
            viaProcess.dispose();
        }
    }

    @Test
    void urlModeAndFallback() throws Exception {
        KoncludeSettings auto = KoncludeReasonerTest.settings();
        URL url = OwlLinkServer.endpoint(auto);
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = fixture(m);
        OWLAxiom q = questions(m.getOWLDataFactory()).get(0);

        KoncludeSettings viaUrl = new KoncludeSettings(auto.getCommand(), "AUTO", null, false,
                KoncludeSettings.OwlLinkMode.URL, url.toString());
        long runs0 = KoncludeRunner.TOTAL_RUNS.get();
        OWLReasoner r = new KoncludeReasonerFactory(viaUrl).createNonBufferingReasoner(o);
        assertTrue(r.isConsistent());
        assertTrue(r.isEntailed(q));
        assertEquals(0, KoncludeRunner.TOTAL_RUNS.get() - runs0);
        r.dispose();

        // unreachable server: falls back to a Konclude run and still answers correctly
        KoncludeSettings broken = new KoncludeSettings(auto.getCommand(), "AUTO", null, false,
                KoncludeSettings.OwlLinkMode.URL, "http://127.0.0.1:1/");
        OWLReasoner fallback = new KoncludeReasonerFactory(broken).createNonBufferingReasoner(o);
        assertTrue(fallback.isEntailed(q));
        assertEquals(1, KoncludeRunner.TOTAL_RUNS.get() - runs0);
        fallback.dispose();
    }

    @Test
    void selfTestChecksOwlLink() throws Exception {
        String s = KoncludeSelfTest.runOwlLink(KoncludeReasonerTest.settings());
        assertTrue(s.startsWith("OWLlink server OK"), s);
    }
}
