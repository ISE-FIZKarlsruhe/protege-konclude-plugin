package de.fizkarlsruhe.konclude.owlapi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyExpression;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLDisjointClassesAxiom;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLEquivalentDataPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLEquivalentObjectPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLInverseObjectPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLObjectPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSameIndividualAxiom;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.OWLSubDataPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom;
import org.semanticweb.owlapi.reasoner.BufferingMode;
import org.semanticweb.owlapi.reasoner.FreshEntitiesException;
import org.semanticweb.owlapi.reasoner.FreshEntityPolicy;
import org.semanticweb.owlapi.reasoner.InconsistentOntologyException;
import org.semanticweb.owlapi.reasoner.IndividualNodeSetPolicy;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.Node;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration;
import org.semanticweb.owlapi.reasoner.ReasonerProgressMonitor;
import org.semanticweb.owlapi.reasoner.UnsupportedEntailmentTypeException;
import org.semanticweb.owlapi.reasoner.impl.OWLClassNode;
import org.semanticweb.owlapi.reasoner.impl.OWLClassNodeSet;
import org.semanticweb.owlapi.reasoner.impl.OWLDataPropertyNode;
import org.semanticweb.owlapi.reasoner.impl.OWLDataPropertyNodeSet;
import org.semanticweb.owlapi.reasoner.impl.OWLNamedIndividualNode;
import org.semanticweb.owlapi.reasoner.impl.OWLNamedIndividualNodeSet;
import org.semanticweb.owlapi.reasoner.impl.OWLObjectPropertyNode;
import org.semanticweb.owlapi.reasoner.impl.OWLObjectPropertyNodeSet;
import org.semanticweb.owlapi.reasoner.impl.OWLReasonerBase;
import org.semanticweb.owlapi.util.Version;

/**
 * OWL API reasoner backed by the Konclude command line tool.
 *
 * <p>The imports closure is written to a temporary functional-syntax file and processed with
 * {@code Konclude materialize}. Hierarchies, types and property values are answered from that
 * result. Queries about complex class expressions add a fresh class {@code Q ≡ C} and run Konclude
 * again; their results are cached until the ontology changes.
 */
public class KoncludeReasoner extends OWLReasonerBase {

    private static final Set<InferenceType> PRECOMPUTABLE = Collections.unmodifiableSet(EnumSet.of(
            InferenceType.CLASS_HIERARCHY, InferenceType.CLASS_ASSERTIONS, InferenceType.OBJECT_PROPERTY_HIERARCHY,
            InferenceType.DATA_PROPERTY_HIERARCHY, InferenceType.OBJECT_PROPERTY_ASSERTIONS,
            InferenceType.DATA_PROPERTY_ASSERTIONS, InferenceType.SAME_INDIVIDUAL));

    private static final Set<AxiomType<?>> ENTAILMENT_TYPES = new HashSet<AxiomType<?>>(Arrays.<AxiomType<?>>asList(
            AxiomType.DECLARATION, AxiomType.SUBCLASS_OF, AxiomType.EQUIVALENT_CLASSES, AxiomType.DISJOINT_CLASSES,
            AxiomType.CLASS_ASSERTION, AxiomType.OBJECT_PROPERTY_ASSERTION, AxiomType.DATA_PROPERTY_ASSERTION,
            AxiomType.SAME_INDIVIDUAL, AxiomType.DIFFERENT_INDIVIDUALS, AxiomType.SUB_OBJECT_PROPERTY, AxiomType.EQUIVALENT_OBJECT_PROPERTIES,
            AxiomType.INVERSE_OBJECT_PROPERTIES, AxiomType.SUB_DATA_PROPERTY, AxiomType.EQUIVALENT_DATA_PROPERTIES,
            AxiomType.OBJECT_PROPERTY_DOMAIN, AxiomType.OBJECT_PROPERTY_RANGE, AxiomType.DATA_PROPERTY_DOMAIN));

    private static final AtomicInteger QUERY_IDS = new AtomicInteger();
    private static final Logger LOG = Logger.getLogger(KoncludeReasoner.class.getName());

    private final OWLDataFactory df;
    private final KoncludeSettings settings;
    private final KoncludeRunner runner;
    private final ReasonerProgressMonitor monitor;
    private final IRI queryNamespace = IRI.create("urn:konclude-protege:query#");

    private Materialization result;
    private Map<IRI, Set<IRI>> toldInverses = Collections.emptyMap();
    private int ignoredRules;
    private final Map<OWLClassExpression, Query> queries = new HashMap<>();

    /** OWLlink client for entailment checks before classification (null until first use). */
    private OwlLinkClient owlLink;
    private boolean owlLinkFailed;
    private Boolean owlLinkConsistent;

    /** A materialization of the ontology plus {@code fresh ≡ expression}. */
    private static final class Query {
        final Materialization m;
        final IRI fresh;

        Query(Materialization m, IRI fresh) {
            this.m = m;
            this.fresh = fresh;
        }
    }

    public KoncludeReasoner(OWLOntology rootOntology, OWLReasonerConfiguration configuration,
            BufferingMode bufferingMode, KoncludeSettings settings) {
        super(rootOntology, configuration, bufferingMode);
        this.df = rootOntology.getOWLOntologyManager().getOWLDataFactory();
        this.settings = settings;
        this.runner = new KoncludeRunner(settings, df);
        this.monitor = configuration.getProgressMonitor();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public String getReasonerName() {
        return "Konclude";
    }

    @Override
    public Version getReasonerVersion() {
        return new Version(0, 1, 0, 0);
    }

    @Override
    protected synchronized void handleChanges(Set<OWLAxiom> addAxioms, Set<OWLAxiom> removeAxioms) {
        result = null;
        queries.clear();
        owlLinkConsistent = null;
    }

    @Override
    public void interrupt() {
        runner.interrupt();
        OwlLinkClient c = owlLink;
        if (c != null) {
            c.interrupt();
        }
    }

    @Override
    public synchronized void precomputeInferences(InferenceType... inferenceTypes) {
        ensureMaterialized();
    }

    @Override
    public synchronized boolean isPrecomputed(InferenceType inferenceType) {
        return result != null && PRECOMPUTABLE.contains(inferenceType);
    }

    @Override
    public Set<InferenceType> getPrecomputableInferenceTypes() {
        return PRECOMPUTABLE;
    }

    @Override
    public FreshEntityPolicy getFreshEntityPolicy() {
        return getReasonerConfiguration().getFreshEntityPolicy();
    }

    @Override
    public IndividualNodeSetPolicy getIndividualNodeSetPolicy() {
        return getReasonerConfiguration().getIndividualNodeSetPolicy();
    }

    @Override
    public void dispose() {
        runner.dispose();
        synchronized (this) {
            result = null;
            queries.clear();
        }
    }

    /** Directory holding Konclude exchange files (useful with "keep temp files"). */
    public java.io.File getWorkDirectory() {
        return runner.getWorkDirectory();
    }

    private Materialization ensureMaterialized() {
        if (result == null) {
            monitor.reasonerTaskStarted("Reasoning with Konclude");
            monitor.reasonerTaskBusy();
            try {
                Collection<OWLAxiom> axioms = inputAxioms();
                if (ignoredRules > 0) {
                    LOG.warning("Konclude does not support SWRL: " + ignoredRules + " rule(s) ignored");
                }
                result = runner.materialize(axioms, getTimeOut());
                toldInverses = collectInverses(axioms);
            } finally {
                monitor.reasonerTaskStopped();
            }
        }
        return result;
    }

    /** The materialization of a consistent ontology. */
    private Materialization consistent() {
        Materialization m = ensureMaterialized();
        if (m.inconsistent) {
            throw new InconsistentOntologyException();
        }
        return m;
    }

    private Query query(OWLClassExpression ce) {
        consistent();
        Query q = queries.get(ce);
        if (q == null) {
            IRI fresh = IRI.create(queryNamespace + "Q" + QUERY_IDS.incrementAndGet());
            OWLClass freshClass = df.getOWLClass(fresh);
            List<OWLAxiom> axioms = new ArrayList<>(inputAxioms());
            axioms.add(df.getOWLDeclarationAxiom(freshClass));
            axioms.add(df.getOWLEquivalentClassesAxiom(freshClass, ce));
            monitor.reasonerTaskStarted("Konclude: evaluating class expression");
            monitor.reasonerTaskBusy();
            try {
                q = new Query(runner.materialize(axioms, getTimeOut()), fresh);
            } finally {
                monitor.reasonerTaskStopped();
            }
            queries.put(ce, q);
        }
        return q;
    }

    /**
     * The axioms sent to Konclude: SWRL rules (unsupported, they break Konclude's parser) and
     * annotation property declarations (rejected with an error) are left out.
     */
    private List<OWLAxiom> inputAxioms() {
        List<OWLAxiom> axioms = new ArrayList<>();
        int rules = 0;
        for (OWLAxiom ax : getReasonerAxioms()) {
            if (ax.getAxiomType() == AxiomType.SWRL_RULE) {
                rules++;
            } else if (!(ax instanceof OWLDeclarationAxiom
                    && ((OWLDeclarationAxiom) ax).getEntity().isOWLAnnotationProperty())) {
                axioms.add(ax);
            }
        }
        ignoredRules = rules;
        return axioms;
    }

    /** Number of SWRL rules left out of the last Konclude run. */
    public synchronized int getIgnoredRuleCount() {
        return ignoredRules;
    }

    // ------------------------------------------------------------------ OWLlink entailment checks

    /**
     * Entailment checks of a reasoner that has not classified go to the OWLlink server: the explanation
     * workbench creates one reasoner per check, and a Konclude process (or container) per check is slow.
     */
    private boolean useOwlLink() {
        return result == null && !owlLinkFailed && settings.getOwlLinkMode() != KoncludeSettings.OwlLinkMode.OFF;
    }

    /** {@code [consistent, satisfiable(ces…)]} from the OWLlink server, or null to fall back to materialize. */
    private boolean[] owlLinkCheck(List<OWLClassExpression> ces) {
        try {
            if (owlLink == null) {
                owlLink = new OwlLinkClient(OwlLinkServer.endpoint(settings), df);
            }
            boolean[] answers = owlLink.check(inputAxioms(), ces, getTimeOut());
            owlLinkConsistent = answers[0];
            return answers;
        } catch (java.io.IOException e) {
            owlLinkFailed = true;
            LOG.warning("Konclude OWLlink server not usable, falling back to one Konclude run per check: "
                    + e.getMessage());
            return null;
        }
    }

    /**
     * Class expressions that are all unsatisfiable iff {@code axiom} is entailed (by a consistent
     * ontology), or null if the axiom type has no such reduction here.
     */
    private List<OWLClassExpression> unsatisfiableIfEntailed(OWLAxiom axiom) {
        List<OWLClassExpression> out = new ArrayList<>();
        AxiomType<?> type = axiom.getAxiomType();
        if (type == AxiomType.SUBCLASS_OF) {
            OWLSubClassOfAxiom ax = (OWLSubClassOfAxiom) axiom;
            out.add(notSubsumed(ax.getSubClass(), ax.getSuperClass()));
        } else if (type == AxiomType.EQUIVALENT_CLASSES) {
            List<OWLClassExpression> ces = new ArrayList<>(((OWLEquivalentClassesAxiom) axiom).getClassExpressions());
            for (int i = 1; i < ces.size(); i++) {
                out.add(notSubsumed(ces.get(0), ces.get(i)));
                out.add(notSubsumed(ces.get(i), ces.get(0)));
            }
        } else if (type == AxiomType.DISJOINT_CLASSES) {
            List<OWLClassExpression> ces = new ArrayList<>(((OWLDisjointClassesAxiom) axiom).getClassExpressions());
            for (int i = 0; i < ces.size(); i++) {
                for (int j = i + 1; j < ces.size(); j++) {
                    out.add(df.getOWLObjectIntersectionOf(ces.get(i), ces.get(j)));
                }
            }
        } else if (type == AxiomType.CLASS_ASSERTION) {
            OWLClassAssertionAxiom ax = (OWLClassAssertionAxiom) axiom;
            out.add(notSubsumed(df.getOWLObjectOneOf(ax.getIndividual()), ax.getClassExpression()));
        } else if (type == AxiomType.OBJECT_PROPERTY_ASSERTION) {
            OWLObjectPropertyAssertionAxiom ax = (OWLObjectPropertyAssertionAxiom) axiom;
            out.add(df.getOWLObjectIntersectionOf(df.getOWLObjectOneOf(ax.getSubject()), df.getOWLObjectAllValuesFrom(
                    ax.getProperty(), df.getOWLObjectComplementOf(df.getOWLObjectOneOf(ax.getObject())))));
        } else if (type == AxiomType.DATA_PROPERTY_ASSERTION) {
            OWLDataPropertyAssertionAxiom ax = (OWLDataPropertyAssertionAxiom) axiom;
            out.add(notSubsumed(df.getOWLObjectOneOf(ax.getSubject()),
                    df.getOWLDataHasValue(ax.getProperty(), ax.getObject())));
        } else if (type == AxiomType.SAME_INDIVIDUAL) {
            List<OWLIndividual> inds = ((OWLSameIndividualAxiom) axiom).getIndividualsAsList();
            for (int i = 1; i < inds.size(); i++) {
                out.add(notSubsumed(df.getOWLObjectOneOf(inds.get(0)), df.getOWLObjectOneOf(inds.get(i))));
            }
        } else if (type == AxiomType.DIFFERENT_INDIVIDUALS) {
            List<OWLIndividual> inds = ((org.semanticweb.owlapi.model.OWLDifferentIndividualsAxiom) axiom)
                    .getIndividualsAsList();
            for (int i = 0; i < inds.size(); i++) {
                for (int j = i + 1; j < inds.size(); j++) {
                    out.add(df.getOWLObjectIntersectionOf(df.getOWLObjectOneOf(inds.get(i)),
                            df.getOWLObjectOneOf(inds.get(j))));
                }
            }
        } else if (type == AxiomType.SUB_OBJECT_PROPERTY) {
            OWLSubObjectPropertyOfAxiom ax = (OWLSubObjectPropertyOfAxiom) axiom;
            out.add(notSubProperty(ax.getSubProperty(), ax.getSuperProperty()));
        } else if (type == AxiomType.EQUIVALENT_OBJECT_PROPERTIES) {
            List<OWLObjectPropertyExpression> ps = new ArrayList<>(
                    ((OWLEquivalentObjectPropertiesAxiom) axiom).getProperties());
            for (int i = 1; i < ps.size(); i++) {
                out.add(notSubProperty(ps.get(0), ps.get(i)));
                out.add(notSubProperty(ps.get(i), ps.get(0)));
            }
        } else if (type == AxiomType.INVERSE_OBJECT_PROPERTIES) {
            OWLInverseObjectPropertiesAxiom ax = (OWLInverseObjectPropertiesAxiom) axiom;
            OWLObjectPropertyExpression inv = ax.getSecondProperty().getInverseProperty();
            out.add(notSubProperty(ax.getFirstProperty(), inv));
            out.add(notSubProperty(inv, ax.getFirstProperty()));
        } else if (type == AxiomType.OBJECT_PROPERTY_DOMAIN) {
            OWLObjectPropertyDomainAxiom ax = (OWLObjectPropertyDomainAxiom) axiom;
            out.add(notSubsumed(df.getOWLObjectSomeValuesFrom(ax.getProperty(), df.getOWLThing()), ax.getDomain()));
        } else if (type == AxiomType.OBJECT_PROPERTY_RANGE) {
            OWLObjectPropertyRangeAxiom ax = (OWLObjectPropertyRangeAxiom) axiom;
            out.add(notSubsumed(df.getOWLObjectSomeValuesFrom(ax.getProperty().getInverseProperty(), df.getOWLThing()),
                    ax.getRange()));
        } else if (type == AxiomType.DATA_PROPERTY_DOMAIN) {
            OWLDataPropertyDomainAxiom ax = (OWLDataPropertyDomainAxiom) axiom;
            out.add(notSubsumed(df.getOWLDataSomeValuesFrom(ax.getProperty(), df.getTopDatatype()), ax.getDomain()));
        } else {
            return null;
        }
        return out;
    }

    private OWLClassExpression notSubsumed(OWLClassExpression sub, OWLClassExpression sup) {
        return df.getOWLObjectIntersectionOf(sub, df.getOWLObjectComplementOf(sup));
    }

    /** {@code R ⊑ S} iff {@code ∃R.X ⊓ ∀S.¬X} is unsatisfiable for a fresh class X. */
    private OWLClassExpression notSubProperty(OWLObjectPropertyExpression sub, OWLObjectPropertyExpression sup) {
        OWLClass x = df.getOWLClass(IRI.create(queryNamespace + "X" + QUERY_IDS.incrementAndGet()));
        return df.getOWLObjectIntersectionOf(df.getOWLObjectSomeValuesFrom(sub, x),
                df.getOWLObjectAllValuesFrom(sup, df.getOWLObjectComplementOf(x)));
    }

    private static Map<IRI, Set<IRI>> collectInverses(Collection<OWLAxiom> axioms) {
        Map<IRI, Set<IRI>> inverses = new HashMap<>();
        for (OWLAxiom ax : axioms) {
            if (ax instanceof OWLInverseObjectPropertiesAxiom) {
                OWLInverseObjectPropertiesAxiom inv = (OWLInverseObjectPropertiesAxiom) ax;
                OWLObjectPropertyExpression a = inv.getFirstProperty().getSimplified();
                OWLObjectPropertyExpression b = inv.getSecondProperty().getSimplified();
                if (!a.isAnonymous() && !b.isAnonymous()) {
                    IRI ia = a.asOWLObjectProperty().getIRI();
                    IRI ib = b.asOWLObjectProperty().getIRI();
                    inverses.computeIfAbsent(ia, k -> new HashSet<IRI>()).add(ib);
                    inverses.computeIfAbsent(ib, k -> new HashSet<IRI>()).add(ia);
                }
            }
        }
        return inverses;
    }

    private void checkFresh(Taxonomy t, IRI iri, Object entity) {
        if (!t.contains(iri) && getFreshEntityPolicy() == FreshEntityPolicy.DISALLOW
                && !iri.toString().startsWith(queryNamespace.toString())) {
            throw new FreshEntitiesException(Collections.singleton((org.semanticweb.owlapi.model.OWLEntity) entity));
        }
    }

    // ------------------------------------------------------------------ consistency

    @Override
    public synchronized boolean isConsistent() {
        if (useOwlLink()) {
            if (owlLinkConsistent == null) {
                owlLinkCheck(Collections.<OWLClassExpression>emptyList());
            }
            if (owlLinkConsistent != null && !owlLinkFailed) {
                return owlLinkConsistent;
            }
        }
        return !ensureMaterialized().inconsistent;
    }

    @Override
    public synchronized boolean isSatisfiable(OWLClassExpression ce) {
        if (useOwlLink()) {
            boolean[] r = owlLinkCheck(Collections.singletonList(ce));
            if (r != null) {
                if (!r[0]) {
                    throw new InconsistentOntologyException();
                }
                return r[1];
            }
        }
        if (!ce.isAnonymous()) {
            Taxonomy t = consistent().classes;
            int g = t.group(ce.asOWLClass().getIRI());
            return g < 0 ? !ce.isOWLNothing() : g != t.bottom();
        }
        Query q = query(ce);
        return q.m.classes.group(q.fresh) != q.m.classes.bottom();
    }

    @Override
    public synchronized Node<OWLClass> getUnsatisfiableClasses() {
        return getBottomClassNode();
    }

    // ------------------------------------------------------------------ class hierarchy

    @Override
    public synchronized Node<OWLClass> getTopClassNode() {
        Taxonomy t = consistent().classes;
        return classNode(t.members(t.top()), null);
    }

    @Override
    public synchronized Node<OWLClass> getBottomClassNode() {
        Taxonomy t = consistent().classes;
        return classNode(t.members(t.bottom()), null);
    }

    @Override
    public synchronized NodeSet<OWLClass> getSubClasses(OWLClassExpression ce, boolean direct) {
        Lookup l = lookup(ce);
        if (l.group < 0) {
            return classNodes(l.t, Collections.singleton(l.t.bottom()), null);
        }
        return classNodes(l.t, l.t.subs(l.group, direct), l.exclude);
    }

    @Override
    public synchronized NodeSet<OWLClass> getSuperClasses(OWLClassExpression ce, boolean direct) {
        Lookup l = lookup(ce);
        if (l.group < 0) {
            return classNodes(l.t, Collections.singleton(l.t.top()), null);
        }
        return classNodes(l.t, l.t.supers(l.group, direct), l.exclude);
    }

    @Override
    public synchronized Node<OWLClass> getEquivalentClasses(OWLClassExpression ce) {
        Lookup l = lookup(ce);
        if (l.group < 0) {
            return new OWLClassNode(ce.asOWLClass());
        }
        return classNode(l.t.members(l.group), l.exclude);
    }

    @Override
    public synchronized NodeSet<OWLClass> getDisjointClasses(OWLClassExpression ce) {
        OWLClassExpression complement = df.getOWLObjectComplementOf(ce).getNNF();
        Lookup l = lookup(complement);
        OWLClassNodeSet nodes = new OWLClassNodeSet();
        if (l.group < 0) {
            nodes.addNode(classNode(l.t.members(l.t.bottom()), null));
            return nodes;
        }
        Node<OWLClass> eq = classNode(l.t.members(l.group), l.exclude);
        if (eq.getSize() > 0) {
            nodes.addNode(eq);
        }
        nodes.addAllNodes(classNodes(l.t, l.t.subs(l.group, false), l.exclude).getNodes());
        return nodes;
    }

    /** Where a class expression lives: its taxonomy, group (-1 for unknown) and the fresh IRI to hide. */
    private static final class Lookup {
        final Taxonomy t;
        final int group;
        final IRI exclude;
        final Materialization m;

        Lookup(Materialization m, Taxonomy t, int group, IRI exclude) {
            this.m = m;
            this.t = t;
            this.group = group;
            this.exclude = exclude;
        }
    }

    private Lookup lookup(OWLClassExpression ce) {
        if (!ce.isAnonymous()) {
            Materialization m = consistent();
            IRI iri = ce.asOWLClass().getIRI();
            checkFresh(m.classes, iri, ce);
            return new Lookup(m, m.classes, m.classes.group(iri), null);
        }
        Query q = query(ce);
        return new Lookup(q.m, q.m.classes, q.m.classes.group(q.fresh), q.fresh);
    }

    private Node<OWLClass> classNode(Set<IRI> iris, IRI exclude) {
        Set<OWLClass> classes = new LinkedHashSet<>();
        for (IRI iri : iris) {
            if (!iri.equals(exclude)) {
                classes.add(df.getOWLClass(iri));
            }
        }
        return new OWLClassNode(classes);
    }

    private NodeSet<OWLClass> classNodes(Taxonomy t, Collection<Integer> groups, IRI exclude) {
        OWLClassNodeSet nodes = new OWLClassNodeSet();
        for (int g : groups) {
            Node<OWLClass> node = classNode(t.members(g), exclude);
            if (node.getSize() > 0) {
                nodes.addNode(node);
            }
        }
        return nodes;
    }

    // ------------------------------------------------------------------ object properties

    @Override
    public synchronized Node<OWLObjectPropertyExpression> getTopObjectPropertyNode() {
        Taxonomy t = consistent().objectProperties;
        return objectPropertyNode(t.members(t.top()), false);
    }

    @Override
    public synchronized Node<OWLObjectPropertyExpression> getBottomObjectPropertyNode() {
        Taxonomy t = consistent().objectProperties;
        return objectPropertyNode(t.members(t.bottom()), false);
    }

    @Override
    public synchronized NodeSet<OWLObjectPropertyExpression> getSubObjectProperties(OWLObjectPropertyExpression pe,
            boolean direct) {
        Taxonomy t = consistent().objectProperties;
        OWLObjectPropertyExpression s = pe.getSimplified();
        int g = t.group(s.getNamedProperty().getIRI());
        if (g < 0) {
            return objectPropertyNodes(t, Collections.singleton(t.bottom()), false);
        }
        return objectPropertyNodes(t, t.subs(g, direct), s.isAnonymous());
    }

    @Override
    public synchronized NodeSet<OWLObjectPropertyExpression> getSuperObjectProperties(OWLObjectPropertyExpression pe,
            boolean direct) {
        Taxonomy t = consistent().objectProperties;
        OWLObjectPropertyExpression s = pe.getSimplified();
        int g = t.group(s.getNamedProperty().getIRI());
        if (g < 0) {
            return objectPropertyNodes(t, Collections.singleton(t.top()), false);
        }
        return objectPropertyNodes(t, t.supers(g, direct), s.isAnonymous());
    }

    @Override
    public synchronized Node<OWLObjectPropertyExpression> getEquivalentObjectProperties(
            OWLObjectPropertyExpression pe) {
        Taxonomy t = consistent().objectProperties;
        OWLObjectPropertyExpression s = pe.getSimplified();
        Set<OWLObjectPropertyExpression> props = new LinkedHashSet<>();
        int g = t.group(s.getNamedProperty().getIRI());
        Set<IRI> group = g < 0 ? Collections.singleton(s.getNamedProperty().getIRI()) : t.members(g);
        props.addAll(objectPropertyNode(group, s.isAnonymous()).getEntities());
        // told inverses: inv(p) ≡ q
        for (IRI member : group) {
            for (IRI inverse : toldInverses.getOrDefault(member, Collections.<IRI>emptySet())) {
                int ig = t.group(inverse);
                Set<IRI> inverseGroup = ig < 0 ? Collections.singleton(inverse) : t.members(ig);
                props.addAll(objectPropertyNode(inverseGroup, !s.isAnonymous()).getEntities());
            }
        }
        return new OWLObjectPropertyNode(props);
    }

    @Override
    public synchronized NodeSet<OWLObjectPropertyExpression> getDisjointObjectProperties(
            OWLObjectPropertyExpression pe) {
        OWLObjectPropertyNodeSet nodes = new OWLObjectPropertyNodeSet();
        nodes.addNode(getBottomObjectPropertyNode());
        return nodes;
    }

    @Override
    public synchronized Node<OWLObjectPropertyExpression> getInverseObjectProperties(OWLObjectPropertyExpression pe) {
        return getEquivalentObjectProperties(pe.getInverseProperty().getSimplified());
    }

    @Override
    public synchronized NodeSet<OWLClass> getObjectPropertyDomains(OWLObjectPropertyExpression pe, boolean direct) {
        return domainLike(df.getOWLObjectSomeValuesFrom(pe, df.getOWLThing()), direct);
    }

    @Override
    public synchronized NodeSet<OWLClass> getObjectPropertyRanges(OWLObjectPropertyExpression pe, boolean direct) {
        return domainLike(df.getOWLObjectSomeValuesFrom(pe.getInverseProperty().getSimplified(), df.getOWLThing()),
                direct);
    }

    /** Equivalent classes plus (direct) superclasses of {@code ce}, as the OWL API specifies for domains/ranges. */
    private NodeSet<OWLClass> domainLike(OWLClassExpression ce, boolean direct) {
        Node<OWLClass> eq = getEquivalentClasses(ce);
        OWLClassNodeSet nodes = new OWLClassNodeSet();
        if (direct && eq.getSize() > 0) {
            nodes.addNode(eq);
            return nodes;
        }
        if (eq.getSize() > 0) {
            nodes.addNode(eq);
        }
        nodes.addAllNodes(getSuperClasses(ce, direct).getNodes());
        return nodes;
    }

    private Node<OWLObjectPropertyExpression> objectPropertyNode(Set<IRI> iris, boolean inverse) {
        Set<OWLObjectPropertyExpression> props = new LinkedHashSet<>();
        for (IRI iri : iris) {
            if (inverse && !iri.equals(Materialization.TOP_OBJECT) && !iri.equals(Materialization.BOTTOM_OBJECT)) {
                props.add(df.getOWLObjectInverseOf(df.getOWLObjectProperty(iri)));
            } else {
                props.add(df.getOWLObjectProperty(iri));
            }
        }
        return new OWLObjectPropertyNode(props);
    }

    private NodeSet<OWLObjectPropertyExpression> objectPropertyNodes(Taxonomy t, Collection<Integer> groups,
            boolean inverse) {
        OWLObjectPropertyNodeSet nodes = new OWLObjectPropertyNodeSet();
        for (int g : groups) {
            nodes.addNode(objectPropertyNode(t.members(g), inverse));
        }
        return nodes;
    }

    // ------------------------------------------------------------------ data properties

    @Override
    public synchronized Node<OWLDataProperty> getTopDataPropertyNode() {
        Taxonomy t = consistent().dataProperties;
        return dataPropertyNode(t.members(t.top()));
    }

    @Override
    public synchronized Node<OWLDataProperty> getBottomDataPropertyNode() {
        Taxonomy t = consistent().dataProperties;
        return dataPropertyNode(t.members(t.bottom()));
    }

    @Override
    public synchronized NodeSet<OWLDataProperty> getSubDataProperties(OWLDataProperty pe, boolean direct) {
        Taxonomy t = consistent().dataProperties;
        int g = t.group(pe.getIRI());
        return dataPropertyNodes(t, g < 0 ? Collections.singleton(t.bottom()) : t.subs(g, direct));
    }

    @Override
    public synchronized NodeSet<OWLDataProperty> getSuperDataProperties(OWLDataProperty pe, boolean direct) {
        Taxonomy t = consistent().dataProperties;
        int g = t.group(pe.getIRI());
        return dataPropertyNodes(t, g < 0 ? Collections.singleton(t.top()) : t.supers(g, direct));
    }

    @Override
    public synchronized Node<OWLDataProperty> getEquivalentDataProperties(OWLDataProperty pe) {
        Taxonomy t = consistent().dataProperties;
        int g = t.group(pe.getIRI());
        return g < 0 ? new OWLDataPropertyNode(pe) : dataPropertyNode(t.members(g));
    }

    @Override
    public synchronized NodeSet<OWLDataProperty> getDisjointDataProperties(OWLDataPropertyExpression pe) {
        OWLDataPropertyNodeSet nodes = new OWLDataPropertyNodeSet();
        nodes.addNode(getBottomDataPropertyNode());
        return nodes;
    }

    @Override
    public synchronized NodeSet<OWLClass> getDataPropertyDomains(OWLDataProperty pe, boolean direct) {
        return domainLike(df.getOWLDataSomeValuesFrom(pe, df.getTopDatatype()), direct);
    }

    private Node<OWLDataProperty> dataPropertyNode(Set<IRI> iris) {
        Set<OWLDataProperty> props = new LinkedHashSet<>();
        for (IRI iri : iris) {
            props.add(df.getOWLDataProperty(iri));
        }
        return new OWLDataPropertyNode(props);
    }

    private NodeSet<OWLDataProperty> dataPropertyNodes(Taxonomy t, Collection<Integer> groups) {
        OWLDataPropertyNodeSet nodes = new OWLDataPropertyNodeSet();
        for (int g : groups) {
            nodes.addNode(dataPropertyNode(t.members(g)));
        }
        return nodes;
    }

    // ------------------------------------------------------------------ individuals

    @Override
    public synchronized NodeSet<OWLClass> getTypes(OWLNamedIndividual ind, boolean direct) {
        Materialization m = consistent();
        Taxonomy t = m.classes;
        Set<Integer> groups = new LinkedHashSet<>();
        for (IRI cls : m.typesOf(ind.getIRI())) {
            int g = t.group(cls);
            if (g >= 0) {
                groups.add(g);
            }
        }
        groups.add(t.top());
        if (direct) {
            groups = t.minimal(groups);
        } else {
            // all superclasses of the types (the materialization is normally closed already)
            Set<Integer> closed = new LinkedHashSet<>(groups);
            for (int g : groups) {
                closed.addAll(t.supers(g, false));
            }
            groups = closed;
        }
        return classNodes(t, groups, null);
    }

    @Override
    public synchronized NodeSet<OWLNamedIndividual> getInstances(OWLClassExpression ce, boolean direct) {
        Lookup l = lookup(ce);
        OWLNamedIndividualNodeSet nodes = new OWLNamedIndividualNodeSet();
        if (l.group < 0) {
            return nodes;
        }
        Taxonomy t = l.t;
        Set<IRI> seen = new HashSet<>();
        for (Map.Entry<IRI, Set<IRI>> e : l.m.types.entrySet()) {
            IRI ind = e.getKey();
            if (seen.contains(ind) || ind.toString().startsWith(queryNamespace.toString())) {
                continue;
            }
            Set<Integer> groups = new HashSet<>();
            for (IRI cls : e.getValue()) {
                int g = t.group(cls);
                if (g >= 0) {
                    groups.add(g);
                }
            }
            boolean member = false;
            for (int g : groups) {
                if (t.isSubsumedBy(g, l.group)) {
                    member = true;
                    break;
                }
            }
            if (!member && l.group != t.top()) {
                continue;
            }
            if (direct && !t.minimal(withTop(groups, t)).contains(l.group)) {
                continue;
            }
            Node<OWLNamedIndividual> node = individualNode(l.m, ind);
            for (OWLNamedIndividual i : node.getEntities()) {
                seen.add(i.getIRI());
            }
            if (getIndividualNodeSetPolicy() == IndividualNodeSetPolicy.BY_NAME) {
                for (OWLNamedIndividual i : node.getEntities()) {
                    nodes.addNode(new OWLNamedIndividualNode(i));
                }
            } else {
                nodes.addNode(node);
            }
        }
        return nodes;
    }

    private static Set<Integer> withTop(Set<Integer> groups, Taxonomy t) {
        Set<Integer> s = new HashSet<>(groups);
        s.add(t.top());
        return s;
    }

    private Node<OWLNamedIndividual> individualNode(Materialization m, IRI ind) {
        Set<OWLNamedIndividual> inds = new LinkedHashSet<>();
        for (IRI i : m.sameAs(ind)) {
            inds.add(df.getOWLNamedIndividual(i));
        }
        return new OWLNamedIndividualNode(inds);
    }

    @Override
    public synchronized NodeSet<OWLNamedIndividual> getObjectPropertyValues(OWLNamedIndividual ind,
            OWLObjectPropertyExpression pe) {
        Materialization m = consistent();
        OWLObjectPropertyExpression s = pe.getSimplified();
        IRI property = s.getNamedProperty().getIRI();
        Set<IRI> values = new LinkedHashSet<>();
        if (property.equals(Materialization.TOP_OBJECT)) {
            // the top property relates every pair of individuals; Konclude does not materialize it
            values.addAll(m.individuals);
        } else if (!s.isAnonymous()) {
            values.addAll(m.objectValues(property, ind.getIRI()));
        } else {
            Map<IRI, Set<IRI>> bySubject = m.objectValues.get(property);
            if (bySubject != null) {
                for (Map.Entry<IRI, Set<IRI>> e : bySubject.entrySet()) {
                    if (e.getValue().contains(ind.getIRI())) {
                        values.add(e.getKey());
                    }
                }
            }
        }
        OWLNamedIndividualNodeSet nodes = new OWLNamedIndividualNodeSet();
        Set<IRI> seen = new HashSet<>();
        for (IRI v : values) {
            if (seen.add(v)) {
                Node<OWLNamedIndividual> node = individualNode(m, v);
                for (OWLNamedIndividual i : node.getEntities()) {
                    seen.add(i.getIRI());
                }
                nodes.addNode(node);
            }
        }
        return nodes;
    }

    @Override
    public synchronized Set<OWLLiteral> getDataPropertyValues(OWLNamedIndividual ind, OWLDataProperty pe) {
        return new LinkedHashSet<>(consistent().dataValues(pe.getIRI(), ind.getIRI()));
    }

    @Override
    public synchronized Node<OWLNamedIndividual> getSameIndividuals(OWLNamedIndividual ind) {
        return individualNode(consistent(), ind.getIRI());
    }

    @Override
    public synchronized NodeSet<OWLNamedIndividual> getDifferentIndividuals(OWLNamedIndividual ind) {
        // individuals known to be different: instances of the complement of {ind}
        return getInstances(df.getOWLObjectComplementOf(df.getOWLObjectOneOf(ind)), false);
    }

    // ------------------------------------------------------------------ entailment

    @Override
    public synchronized boolean isEntailmentCheckingSupported(AxiomType<?> axiomType) {
        return ENTAILMENT_TYPES.contains(axiomType);
    }

    @Override
    public synchronized boolean isEntailed(Set<? extends OWLAxiom> axioms) {
        for (OWLAxiom ax : axioms) {
            if (!isEntailed(ax)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public synchronized boolean isEntailed(OWLAxiom axiom) {
        if (axiom.getAxiomType() == AxiomType.DECLARATION) {
            return true;
        }
        if (useOwlLink()) {
            List<OWLClassExpression> ces = unsatisfiableIfEntailed(axiom);
            if (ces != null) {
                boolean[] r = owlLinkCheck(ces);
                if (r != null) {
                    if (!r[0]) {
                        return true;
                    }
                    for (int i = 1; i < r.length; i++) {
                        if (r[i]) {
                            return false;
                        }
                    }
                    return true;
                }
            }
        }
        Materialization m = ensureMaterialized();
        if (m.inconsistent) {
            return true;
        }
        AxiomType<?> type = axiom.getAxiomType();
        if (type == AxiomType.DECLARATION) {
            return true;
        }
        if (type == AxiomType.SUBCLASS_OF) {
            OWLSubClassOfAxiom ax = (OWLSubClassOfAxiom) axiom;
            return subsumes(ax.getSuperClass(), ax.getSubClass());
        }
        if (type == AxiomType.EQUIVALENT_CLASSES) {
            List<OWLClassExpression> ces = new ArrayList<>(((OWLEquivalentClassesAxiom) axiom).getClassExpressions());
            for (int i = 1; i < ces.size(); i++) {
                if (!subsumes(ces.get(0), ces.get(i)) || !subsumes(ces.get(i), ces.get(0))) {
                    return false;
                }
            }
            return true;
        }
        if (type == AxiomType.DISJOINT_CLASSES) {
            List<OWLClassExpression> ces = new ArrayList<>(((OWLDisjointClassesAxiom) axiom).getClassExpressions());
            for (int i = 0; i < ces.size(); i++) {
                for (int j = i + 1; j < ces.size(); j++) {
                    if (isSatisfiable(df.getOWLObjectIntersectionOf(ces.get(i), ces.get(j)))) {
                        return false;
                    }
                }
            }
            return true;
        }
        if (type == AxiomType.CLASS_ASSERTION) {
            OWLClassAssertionAxiom ax = (OWLClassAssertionAxiom) axiom;
            OWLIndividual ind = ax.getIndividual();
            if (ind.isNamed() && !ax.getClassExpression().isAnonymous()) {
                return getTypes(ind.asOWLNamedIndividual(), false).containsEntity(ax.getClassExpression().asOWLClass());
            }
            return !isSatisfiable(df.getOWLObjectIntersectionOf(df.getOWLObjectOneOf(ind),
                    df.getOWLObjectComplementOf(ax.getClassExpression())));
        }
        if (type == AxiomType.OBJECT_PROPERTY_ASSERTION) {
            OWLObjectPropertyAssertionAxiom ax = (OWLObjectPropertyAssertionAxiom) axiom;
            if (ax.getSubject().isNamed() && ax.getObject().isNamed()) {
                return getObjectPropertyValues(ax.getSubject().asOWLNamedIndividual(), ax.getProperty())
                        .containsEntity(ax.getObject().asOWLNamedIndividual());
            }
            return !isSatisfiable(df.getOWLObjectIntersectionOf(df.getOWLObjectOneOf(ax.getSubject()),
                    df.getOWLObjectAllValuesFrom(ax.getProperty(),
                            df.getOWLObjectComplementOf(df.getOWLObjectOneOf(ax.getObject())))));
        }
        if (type == AxiomType.DATA_PROPERTY_ASSERTION) {
            OWLDataPropertyAssertionAxiom ax = (OWLDataPropertyAssertionAxiom) axiom;
            if (ax.getSubject().isNamed() && !ax.getProperty().isAnonymous()) {
                return getDataPropertyValues(ax.getSubject().asOWLNamedIndividual(),
                        ax.getProperty().asOWLDataProperty()).contains(ax.getObject());
            }
            throw new UnsupportedEntailmentTypeException(axiom);
        }
        if (type == AxiomType.SAME_INDIVIDUAL) {
            List<OWLIndividual> inds = ((OWLSameIndividualAxiom) axiom).getIndividualsAsList();
            for (OWLIndividual i : inds) {
                if (!i.isNamed()) {
                    throw new UnsupportedEntailmentTypeException(axiom);
                }
            }
            Node<OWLNamedIndividual> same = getSameIndividuals(inds.get(0).asOWLNamedIndividual());
            for (OWLIndividual i : inds) {
                if (!same.contains(i.asOWLNamedIndividual())) {
                    return false;
                }
            }
            return true;
        }
        if (type == AxiomType.DIFFERENT_INDIVIDUALS) {
            List<OWLIndividual> inds = ((org.semanticweb.owlapi.model.OWLDifferentIndividualsAxiom) axiom)
                    .getIndividualsAsList();
            for (int i = 0; i < inds.size(); i++) {
                for (int j = i + 1; j < inds.size(); j++) {
                    if (isSatisfiable(df.getOWLObjectIntersectionOf(df.getOWLObjectOneOf(inds.get(i)),
                            df.getOWLObjectOneOf(inds.get(j))))) {
                        return false;
                    }
                }
            }
            return true;
        }
        if (type == AxiomType.SUB_OBJECT_PROPERTY) {
            OWLSubObjectPropertyOfAxiom ax = (OWLSubObjectPropertyOfAxiom) axiom;
            OWLObjectPropertyExpression sub = ax.getSubProperty().getSimplified();
            OWLObjectPropertyExpression sup = ax.getSuperProperty().getSimplified();
            if (sub.isAnonymous() != sup.isAnonymous()) {
                throw new UnsupportedEntailmentTypeException(axiom);
            }
            return objectSubsumes(sup.getNamedProperty().getIRI(), sub.getNamedProperty().getIRI());
        }
        if (type == AxiomType.EQUIVALENT_OBJECT_PROPERTIES) {
            Set<OWLObjectPropertyExpression> props = ((OWLEquivalentObjectPropertiesAxiom) axiom).getProperties();
            OWLObjectPropertyExpression first = props.iterator().next();
            Node<OWLObjectPropertyExpression> eq = getEquivalentObjectProperties(first);
            for (OWLObjectPropertyExpression p : props) {
                if (!eq.contains(p.getSimplified())) {
                    return false;
                }
            }
            return true;
        }
        if (type == AxiomType.INVERSE_OBJECT_PROPERTIES) {
            OWLInverseObjectPropertiesAxiom ax = (OWLInverseObjectPropertiesAxiom) axiom;
            return getInverseObjectProperties(ax.getFirstProperty()).contains(ax.getSecondProperty().getSimplified());
        }
        if (type == AxiomType.SUB_DATA_PROPERTY) {
            OWLSubDataPropertyOfAxiom ax = (OWLSubDataPropertyOfAxiom) axiom;
            Taxonomy t = consistent().dataProperties;
            int sub = t.group(ax.getSubProperty().asOWLDataProperty().getIRI());
            int sup = t.group(ax.getSuperProperty().asOWLDataProperty().getIRI());
            if (sub < 0 || sup < 0) {
                return ax.getSubProperty().equals(ax.getSuperProperty()) || ax.getSuperProperty().isOWLTopDataProperty();
            }
            return t.isSubsumedBy(sub, sup);
        }
        if (type == AxiomType.EQUIVALENT_DATA_PROPERTIES) {
            Set<OWLDataPropertyExpression> props = ((OWLEquivalentDataPropertiesAxiom) axiom).getProperties();
            Node<OWLDataProperty> eq = getEquivalentDataProperties(props.iterator().next().asOWLDataProperty());
            for (OWLDataPropertyExpression p : props) {
                if (!eq.contains(p.asOWLDataProperty())) {
                    return false;
                }
            }
            return true;
        }
        if (type == AxiomType.OBJECT_PROPERTY_DOMAIN) {
            OWLObjectPropertyDomainAxiom ax = (OWLObjectPropertyDomainAxiom) axiom;
            return subsumes(ax.getDomain(), df.getOWLObjectSomeValuesFrom(ax.getProperty(), df.getOWLThing()));
        }
        if (type == AxiomType.OBJECT_PROPERTY_RANGE) {
            OWLObjectPropertyRangeAxiom ax = (OWLObjectPropertyRangeAxiom) axiom;
            return subsumes(ax.getRange(), df.getOWLObjectSomeValuesFrom(
                    ax.getProperty().getInverseProperty().getSimplified(), df.getOWLThing()));
        }
        if (type == AxiomType.DATA_PROPERTY_DOMAIN) {
            OWLDataPropertyDomainAxiom ax = (OWLDataPropertyDomainAxiom) axiom;
            return subsumes(ax.getDomain(), df.getOWLDataSomeValuesFrom(ax.getProperty(), df.getTopDatatype()));
        }
        throw new UnsupportedEntailmentTypeException(axiom);
    }

    /** True if {@code sub ⊑ sup}. Named classes are looked up; otherwise {@code sub ⊓ ¬sup} is tested. */
    private boolean subsumes(OWLClassExpression sup, OWLClassExpression sub) {
        if (!sup.isAnonymous() && !sub.isAnonymous()) {
            Taxonomy t = consistent().classes;
            int gSub = t.group(sub.asOWLClass().getIRI());
            int gSup = t.group(sup.asOWLClass().getIRI());
            if (gSub >= 0 && gSup >= 0) {
                return t.isSubsumedBy(gSub, gSup);
            }
        }
        if (sup.isOWLThing() || sub.isOWLNothing()) {
            return true;
        }
        return !isSatisfiable(df.getOWLObjectIntersectionOf(sub, df.getOWLObjectComplementOf(sup)));
    }

    private boolean objectSubsumes(IRI sup, IRI sub) {
        Taxonomy t = consistent().objectProperties;
        int gSub = t.group(sub);
        int gSup = t.group(sup);
        if (gSub < 0 || gSup < 0) {
            return sub.equals(sup) || sup.equals(Materialization.TOP_OBJECT);
        }
        return t.isSubsumedBy(gSub, gSup);
    }
}
