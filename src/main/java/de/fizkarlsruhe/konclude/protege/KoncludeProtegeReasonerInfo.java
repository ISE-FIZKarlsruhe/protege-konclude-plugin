package de.fizkarlsruhe.konclude.protege;

import org.protege.editor.owl.model.inference.AbstractProtegeOWLReasonerInfo;
import org.semanticweb.owlapi.reasoner.BufferingMode;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

import de.fizkarlsruhe.konclude.owlapi.KoncludeReasonerFactory;

/** Registers Konclude in Protégé's Reasoner menu. */
public class KoncludeProtegeReasonerInfo extends AbstractProtegeOWLReasonerInfo {

    @Override
    public BufferingMode getRecommendedBuffering() {
        // every reasoner run is a full Konclude run, so batch edits until "Synchronize reasoner"
        return BufferingMode.BUFFERING;
    }

    @Override
    public OWLReasonerFactory getReasonerFactory() {
        // read the preferences each time so changes apply to the next reasoner instance
        return new KoncludeReasonerFactory(KoncludePreferences.load());
    }
}
