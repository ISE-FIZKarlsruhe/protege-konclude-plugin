package de.fizkarlsruhe.konclude.owlapi;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.vocab.OWL2Datatype;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

/**
 * The result of {@code Konclude materialize}: class and property hierarchies, class assertions,
 * object/data property assertions and same-individual groups, read from Konclude's OWL/XML output.
 */
final class Materialization {

    static final IRI THING = OWLRDFVocabulary.OWL_THING.getIRI();
    static final IRI NOTHING = OWLRDFVocabulary.OWL_NOTHING.getIRI();
    static final IRI TOP_OBJECT = OWLRDFVocabulary.OWL_TOP_OBJECT_PROPERTY.getIRI();
    static final IRI BOTTOM_OBJECT = OWLRDFVocabulary.OWL_BOTTOM_OBJECT_PROPERTY.getIRI();
    static final IRI TOP_DATA = OWLRDFVocabulary.OWL_TOP_DATA_PROPERTY.getIRI();
    static final IRI BOTTOM_DATA = OWLRDFVocabulary.OWL_BOTTOM_DATA_PROPERTY.getIRI();

    private static final String OWL_NS = "http://www.w3.org/2002/07/owl#";
    private static final String XML_NS = "http://www.w3.org/XML/1998/namespace";

    final Taxonomy classes;
    final Taxonomy objectProperties;
    final Taxonomy dataProperties;
    /** individual -> all (inferred) class IRIs */
    final Map<IRI, Set<IRI>> types;
    /** property -> subject -> objects */
    final Map<IRI, Map<IRI, Set<IRI>>> objectValues;
    /** property -> subject -> literals */
    final Map<IRI, Map<IRI, Set<OWLLiteral>>> dataValues;
    /** individual -> its same-individual group (including itself); absent for singletons */
    final Map<IRI, Set<IRI>> sameAs;
    final Set<IRI> individuals;
    final boolean inconsistent;

    private Materialization(Taxonomy classes, Taxonomy objectProperties, Taxonomy dataProperties,
            Map<IRI, Set<IRI>> types, Map<IRI, Map<IRI, Set<IRI>>> objectValues,
            Map<IRI, Map<IRI, Set<OWLLiteral>>> dataValues, Map<IRI, Set<IRI>> sameAs, Set<IRI> individuals,
            boolean inconsistent) {
        this.classes = classes;
        this.objectProperties = objectProperties;
        this.dataProperties = dataProperties;
        this.types = types;
        this.objectValues = objectValues;
        this.dataValues = dataValues;
        this.sameAs = sameAs;
        this.individuals = individuals;
        this.inconsistent = inconsistent;
    }

    Set<IRI> typesOf(IRI individual) {
        Set<IRI> t = types.get(individual);
        return t == null ? Collections.<IRI>emptySet() : t;
    }

    Set<IRI> sameAs(IRI individual) {
        Set<IRI> s = sameAs.get(individual);
        return s == null ? Collections.singleton(individual) : s;
    }

    Set<IRI> objectValues(IRI property, IRI subject) {
        Map<IRI, Set<IRI>> bySubject = objectValues.get(property);
        Set<IRI> values = bySubject == null ? null : bySubject.get(subject);
        return values == null ? Collections.<IRI>emptySet() : values;
    }

    Set<OWLLiteral> dataValues(IRI property, IRI subject) {
        Map<IRI, Set<OWLLiteral>> bySubject = dataValues.get(property);
        Set<OWLLiteral> values = bySubject == null ? null : bySubject.get(subject);
        return values == null ? Collections.<OWLLiteral>emptySet() : values;
    }

    static Materialization parse(File file, OWLDataFactory df) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return new Parser(df).parse(in);
        } catch (XMLStreamException e) {
            throw new IOException("Cannot parse Konclude output " + file + ": " + e.getMessage(), e);
        }
    }

    /** One child of an axiom element: element name plus IRI, or a literal. */
    private static final class Arg {
        final String kind;
        final IRI iri;
        final OWLLiteral literal;

        Arg(String kind, IRI iri, OWLLiteral literal) {
            this.kind = kind;
            this.iri = iri;
            this.literal = literal;
        }
    }

    private static final class Parser {

        private final OWLDataFactory df;
        private final Map<String, String> prefixes = new HashMap<>();
        private final Taxonomy.Builder classes = new Taxonomy.Builder();
        private final Taxonomy.Builder objectProperties = new Taxonomy.Builder();
        private final Taxonomy.Builder dataProperties = new Taxonomy.Builder();
        private final Map<IRI, Set<IRI>> types = new HashMap<>();
        private final Map<IRI, Map<IRI, Set<IRI>>> objectValues = new HashMap<>();
        private final Map<IRI, Map<IRI, Set<OWLLiteral>>> dataValues = new HashMap<>();
        private final Map<IRI, Set<IRI>> sameAs = new HashMap<>();
        private final Set<IRI> individuals = new HashSet<>();
        private boolean inconsistent;

        Parser(OWLDataFactory df) {
            this.df = df;
        }

        Materialization parse(InputStream in) throws XMLStreamException {
            XMLInputFactory factory = XMLInputFactory.newInstance();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader r = factory.createXMLStreamReader(in);
            int depth = 0;
            String axiom = null;
            List<Arg> args = new ArrayList<>();
            boolean sawOntology = false;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    String name = r.getLocalName();
                    if (depth == 1) {
                        sawOntology = "Ontology".equals(name);
                    } else if (depth == 2) {
                        if ("Prefix".equals(name)) {
                            prefixes.put(r.getAttributeValue(null, "name"), r.getAttributeValue(null, "IRI"));
                        }
                        axiom = name;
                        args.clear();
                    } else if (depth == 3) {
                        if ("Literal".equals(name)) {
                            String datatype = r.getAttributeValue(null, "datatypeIRI");
                            String lang = r.getAttributeValue(XML_NS, "lang");
                            String text = r.getElementText();
                            depth--;
                            args.add(new Arg(name, null, literal(text, datatype, lang)));
                        } else {
                            args.add(new Arg(name, iri(r), null));
                        }
                    } else {
                        // nested expressions (e.g. ObjectInverseOf) are not used by the materialization
                        args.add(new Arg("nested", null, null));
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (depth == 2 && axiom != null) {
                        axiom(axiom, args);
                        axiom = null;
                    }
                    depth--;
                }
            }
            r.close();
            if (!sawOntology) {
                throw new XMLStreamException("no OWL/XML Ontology element");
            }
            Taxonomy classTaxonomy = classes.build(THING, NOTHING);
            if (classTaxonomy.group(THING) == classTaxonomy.group(NOTHING)) {
                inconsistent = true;
            }
            return new Materialization(classTaxonomy, objectProperties.build(TOP_OBJECT, BOTTOM_OBJECT),
                    dataProperties.build(TOP_DATA, BOTTOM_DATA), types, objectValues, dataValues, sameAs,
                    individuals, inconsistent);
        }

        private IRI iri(XMLStreamReader r) {
            String full = r.getAttributeValue(null, "IRI");
            if (full != null) {
                return IRI.create(full);
            }
            String abbreviated = r.getAttributeValue(null, "abbreviatedIRI");
            if (abbreviated != null) {
                int colon = abbreviated.indexOf(':');
                String ns = prefixes.get(colon < 0 ? "" : abbreviated.substring(0, colon));
                if (ns != null) {
                    return IRI.create(ns + abbreviated.substring(colon + 1));
                }
                return IRI.create(abbreviated);
            }
            return null;
        }

        private OWLLiteral literal(String text, String datatype, String lang) {
            if (lang != null && !lang.isEmpty()) {
                return df.getOWLLiteral(text, lang);
            }
            if (datatype == null || datatype.equals(OWL2Datatype.RDF_PLAIN_LITERAL.getIRI().toString())) {
                // Konclude writes rdf:PlainLiteral values as "lexical@lang"
                int at = text.lastIndexOf('@');
                if (datatype != null && at >= 0) {
                    String l = text.substring(at + 1);
                    String lexical = text.substring(0, at);
                    return l.isEmpty() ? df.getOWLLiteral(lexical) : df.getOWLLiteral(lexical, l);
                }
                return df.getOWLLiteral(text);
            }
            return df.getOWLLiteral(text, df.getOWLDatatype(IRI.create(datatype)));
        }

        private boolean all(List<Arg> args, String kind) {
            if (args.isEmpty()) {
                return false;
            }
            for (Arg a : args) {
                if (!kind.equals(a.kind) || a.iri == null) {
                    return false;
                }
            }
            return true;
        }

        private List<IRI> iris(List<Arg> args) {
            List<IRI> out = new ArrayList<>(args.size());
            for (Arg a : args) {
                out.add(a.iri);
            }
            return out;
        }

        private void axiom(String name, List<Arg> args) {
            switch (name) {
                case "Declaration":
                    if (args.size() == 1 && args.get(0).iri != null) {
                        declare(args.get(0).kind, args.get(0).iri);
                    }
                    break;
                case "SubClassOf":
                    if (args.size() == 2 && all(args, "Class")) {
                        classes.sub(args.get(0).iri, args.get(1).iri);
                    }
                    break;
                case "EquivalentClasses":
                    if (all(args, "Class")) {
                        classes.equivalent(iris(args));
                    }
                    break;
                case "SubObjectPropertyOf":
                    if (args.size() == 2 && all(args, "ObjectProperty")) {
                        objectProperties.sub(args.get(0).iri, args.get(1).iri);
                    }
                    break;
                case "EquivalentObjectProperties":
                    if (all(args, "ObjectProperty")) {
                        objectProperties.equivalent(iris(args));
                    }
                    break;
                case "SubDataPropertyOf":
                    if (args.size() == 2 && all(args, "DataProperty")) {
                        dataProperties.sub(args.get(0).iri, args.get(1).iri);
                    }
                    break;
                case "EquivalentDataProperties":
                    if (all(args, "DataProperty")) {
                        dataProperties.equivalent(iris(args));
                    }
                    break;
                case "ClassAssertion":
                    if (args.size() == 2 && "Class".equals(args.get(0).kind)
                            && "NamedIndividual".equals(args.get(1).kind)) {
                        IRI cls = args.get(0).iri;
                        IRI ind = args.get(1).iri;
                        individuals.add(ind);
                        classes.add(cls);
                        if (NOTHING.equals(cls)) {
                            inconsistent = true;
                        }
                        types.computeIfAbsent(ind, k -> new LinkedHashSet<IRI>()).add(cls);
                    }
                    break;
                case "ObjectPropertyAssertion":
                    if (args.size() == 3 && "ObjectProperty".equals(args.get(0).kind)
                            && "NamedIndividual".equals(args.get(1).kind)
                            && "NamedIndividual".equals(args.get(2).kind)) {
                        individuals.add(args.get(1).iri);
                        individuals.add(args.get(2).iri);
                        objectValues.computeIfAbsent(args.get(0).iri, k -> new HashMap<IRI, Set<IRI>>())
                                .computeIfAbsent(args.get(1).iri, k -> new LinkedHashSet<IRI>()).add(args.get(2).iri);
                    }
                    break;
                case "DataPropertyAssertion":
                    if (args.size() == 3 && "DataProperty".equals(args.get(0).kind)
                            && "NamedIndividual".equals(args.get(1).kind) && args.get(2).literal != null) {
                        individuals.add(args.get(1).iri);
                        dataValues.computeIfAbsent(args.get(0).iri, k -> new HashMap<IRI, Set<OWLLiteral>>())
                                .computeIfAbsent(args.get(1).iri, k -> new LinkedHashSet<OWLLiteral>())
                                .add(args.get(2).literal);
                    }
                    break;
                case "SameIndividual":
                    if (all(args, "NamedIndividual")) {
                        Set<IRI> group = new LinkedHashSet<>(iris(args));
                        for (IRI i : iris(args)) {
                            Set<IRI> previous = sameAs.get(i);
                            if (previous != null) {
                                group.addAll(previous);
                            }
                        }
                        Set<IRI> frozen = Collections.unmodifiableSet(group);
                        for (IRI i : group) {
                            sameAs.put(i, frozen);
                            individuals.add(i);
                        }
                    }
                    break;
                default:
                    break;
            }
        }

        private void declare(String kind, IRI iri) {
            switch (kind) {
                case "Class":
                    classes.add(iri);
                    break;
                case "ObjectProperty":
                    objectProperties.add(iri);
                    break;
                case "DataProperty":
                    dataProperties.add(iri);
                    break;
                case "NamedIndividual":
                    individuals.add(iri);
                    break;
                default:
                    break;
            }
        }
    }
}
