package de.fizkarlsruhe.konclude.owlapi;

import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.BufferingMode;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.SimpleConfiguration;

/** Creates {@link KoncludeReasoner}s that launch Konclude as configured in {@link KoncludeSettings}. */
public class KoncludeReasonerFactory implements OWLReasonerFactory {

    private final KoncludeSettings settings;

    public KoncludeReasonerFactory() {
        this(KoncludeSettings.detect());
    }

    public KoncludeReasonerFactory(KoncludeSettings settings) {
        this.settings = settings;
    }

    @Override
    public String getReasonerName() {
        return "Konclude";
    }

    @Override
    public OWLReasoner createNonBufferingReasoner(OWLOntology ontology) {
        return createNonBufferingReasoner(ontology, new SimpleConfiguration());
    }

    @Override
    public OWLReasoner createReasoner(OWLOntology ontology) {
        return createReasoner(ontology, new SimpleConfiguration());
    }

    @Override
    public OWLReasoner createNonBufferingReasoner(OWLOntology ontology, OWLReasonerConfiguration config) {
        return new KoncludeReasoner(ontology, config, BufferingMode.NON_BUFFERING, settings);
    }

    @Override
    public OWLReasoner createReasoner(OWLOntology ontology, OWLReasonerConfiguration config) {
        return new KoncludeReasoner(ontology, config, BufferingMode.BUFFERING, settings);
    }

    public KoncludeSettings getSettings() {
        return settings;
    }
}
