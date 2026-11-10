package de.fizkarlsruhe.konclude.owlapi;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.OWLXMLDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;

/**
 * Asks a Konclude OWLlink server whether an axiom set is consistent and which class expressions are
 * satisfiable, in one stateless request (CreateKB, Tell, queries, ReleaseKB).
 */
final class OwlLinkClient {

    private static final String OWLLINK_NS = "http://www.owllink.org/owllink#";
    private static final Pattern PREFIX = Pattern.compile("<Prefix name=\"([^\"]*)\" IRI=\"([^\"]*)\"/>");
    private static final Pattern ABBREVIATED = Pattern.compile("abbreviatedIRI=\"([^\":]*):([^\"]*)\"");

    /** OWLlink requests sent by all clients (diagnostics). */
    static final java.util.concurrent.atomic.AtomicLong TOTAL_REQUESTS = new java.util.concurrent.atomic.AtomicLong();

    private final URL url;
    private final OWLDataFactory df;
    private volatile HttpURLConnection connection;

    OwlLinkClient(URL url, OWLDataFactory df) {
        this.url = url;
        this.df = df;
    }

    /**
     * @return {@code [consistent, satisfiable(ces[0]), satisfiable(ces[1]), …]}; the satisfiability values
     *         are meaningless if the axioms are inconsistent
     */
    boolean[] check(Collection<OWLAxiom> axioms, List<OWLClassExpression> ces, long timeoutMillis)
            throws IOException {
        String kb = "urn:konclude-protege:kb:" + UUID.randomUUID();
        List<OWLAxiom> all = new ArrayList<>(axioms);
        List<IRI> fresh = new ArrayList<>();
        for (int i = 0; i < ces.size(); i++) {
            OWLClass q = df.getOWLClass(IRI.create("urn:konclude-protege:owllink#Q" + i));
            fresh.add(q.getIRI());
            all.add(df.getOWLDeclarationAxiom(q));
            all.add(df.getOWLEquivalentClassesAxiom(q, ces.get(i)));
        }
        StringBuilder req = new StringBuilder(4096);
        req.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<ol:RequestMessage xmlns:ol=\"").append(OWLLINK_NS)
                .append("\" xmlns=\"http://www.w3.org/2002/07/owl#\">\n")
                .append("<ol:CreateKB kb=\"").append(kb).append("\"/>\n")
                .append("<ol:Tell kb=\"").append(kb).append("\">\n").append(owlXml(all)).append("</ol:Tell>\n")
                .append("<ol:IsKBSatisfiable kb=\"").append(kb).append("\"/>\n");
        for (IRI q : fresh) {
            req.append("<ol:IsClassSatisfiable kb=\"").append(kb).append("\"><Class IRI=\"").append(q)
                    .append("\"/></ol:IsClassSatisfiable>\n");
        }
        req.append("<ol:ReleaseKB kb=\"").append(kb).append("\"/>\n</ol:RequestMessage>\n");
        byte[] response = post(req.toString().getBytes(StandardCharsets.UTF_8), timeoutMillis);
        return parse(response, 1 + ces.size());
    }

    /** Aborts a running request. */
    void interrupt() {
        HttpURLConnection c = connection;
        if (c != null) {
            c.disconnect();
        }
    }

    private byte[] post(byte[] body, long timeoutMillis) throws IOException {
        TOTAL_REQUESTS.incrementAndGet();
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        connection = c;
        try {
            c.setConnectTimeout(5000);
            if (timeoutMillis > 0 && timeoutMillis < Integer.MAX_VALUE) {
                c.setReadTimeout((int) timeoutMillis);
            }
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            if (c.getResponseCode() != 200) {
                throw new IOException("OWLlink server " + url + " answered HTTP " + c.getResponseCode());
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) {
                    buf.write(b, 0, n);
                }
                return buf.toByteArray();
            }
        } finally {
            connection = null;
            c.disconnect();
        }
    }

    /** The axioms as OWL/XML elements (default namespace owl, full IRIs, no prefixes). */
    static String owlXml(Collection<OWLAxiom> axioms) throws IOException {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            OWLOntology o = manager.createOntology();
            manager.addAxioms(o, new HashSet<OWLAxiom>(axioms));
            manager.saveOntology(o, new OWLXMLDocumentFormat(), out);
        } catch (OWLOntologyCreationException | OWLOntologyStorageException e) {
            throw new IOException("Cannot render axioms as OWL/XML: " + e.getMessage(), e);
        }
        String doc = new String(out.toByteArray(), StandardCharsets.UTF_8);
        int start = doc.indexOf('>', doc.indexOf("<Ontology")) + 1;
        int end = doc.lastIndexOf("</Ontology>");
        String body = start > 0 && end > start ? doc.substring(start, end) : "";
        Map<String, String> prefixes = new HashMap<>();
        Matcher pm = PREFIX.matcher(body);
        while (pm.find()) {
            prefixes.put(pm.group(1), pm.group(2));
        }
        body = PREFIX.matcher(body).replaceAll("");
        Matcher am = ABBREVIATED.matcher(body);
        StringBuffer expanded = new StringBuffer(body.length());
        while (am.find()) {
            String ns = prefixes.get(am.group(1));
            String iri = ns == null ? am.group(1) + ":" + am.group(2) : ns + am.group(2);
            am.appendReplacement(expanded, Matcher.quoteReplacement("IRI=\"" + iri + "\""));
        }
        am.appendTail(expanded);
        return expanded.toString();
    }

    static boolean[] parse(byte[] response, int expected) throws IOException {
        List<Boolean> answers = new ArrayList<>();
        String error = null;
        try {
            XMLInputFactory f = XMLInputFactory.newInstance();
            f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader r = f.createXMLStreamReader(new ByteArrayInputStream(response));
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT) {
                    String name = r.getLocalName();
                    if ("BooleanResponse".equals(name)) {
                        answers.add(Boolean.valueOf(r.getAttributeValue(null, "result")));
                    } else if (("Error".equals(name) || name.endsWith("Error") || "Unsupported".equals(name))
                            && error == null) {
                        String e = r.getAttributeValue(null, "error");
                        error = e == null ? name : e;
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new IOException("Cannot parse OWLlink response: " + e.getMessage(), e);
        }
        boolean[] out = new boolean[expected];
        if (!answers.isEmpty() && !answers.get(0)) {
            return out; // inconsistent: the other answers do not matter
        }
        if (error != null || answers.size() != expected) {
            String detail = error == null ? "expected " + expected + " answers, got " + answers.size() : error;
            int cut = detail.indexOf("Error process protocol");
            throw new IOException("Konclude OWLlink error: " + (cut > 0 ? detail.substring(0, cut).trim() : detail));
        }
        for (int i = 0; i < expected; i++) {
            out[i] = answers.get(i);
        }
        return out;
    }
}
