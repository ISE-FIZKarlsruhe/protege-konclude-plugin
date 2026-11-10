package de.fizkarlsruhe.konclude.owlapi;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.semanticweb.owlapi.model.IRI;

/**
 * An entity hierarchy (classes or properties) with equivalence groups, a top and a bottom group.
 *
 * <p>Built from the (possibly partial) subsumptions Konclude reports; the closure and the direct
 * (transitively reduced) relations are computed here. Groups are addressed by int ids.
 */
final class Taxonomy {

    private final Map<IRI, Integer> groupOf;
    private final List<Set<IRI>> members;
    private final List<Set<Integer>> strictSupers;
    private final List<Set<Integer>> directSupers;
    private final List<Set<Integer>> strictSubs;
    private final List<Set<Integer>> directSubs;
    private final int top;
    private final int bottom;

    private Taxonomy(Map<IRI, Integer> groupOf, List<Set<IRI>> members, List<Set<Integer>> strictSupers,
            List<Set<Integer>> directSupers, List<Set<Integer>> strictSubs, List<Set<Integer>> directSubs,
            int top, int bottom) {
        this.groupOf = groupOf;
        this.members = members;
        this.strictSupers = strictSupers;
        this.directSupers = directSupers;
        this.strictSubs = strictSubs;
        this.directSubs = directSubs;
        this.top = top;
        this.bottom = bottom;
    }

    /** Group id of the entity, or -1 if the entity is unknown. */
    int group(IRI iri) {
        Integer g = groupOf.get(iri);
        return g == null ? -1 : g;
    }

    boolean contains(IRI iri) {
        return groupOf.containsKey(iri);
    }

    Set<IRI> members(int group) {
        return members.get(group);
    }

    int top() {
        return top;
    }

    int bottom() {
        return bottom;
    }

    Set<Integer> supers(int group, boolean direct) {
        return (direct ? directSupers : strictSupers).get(group);
    }

    Set<Integer> subs(int group, boolean direct) {
        return (direct ? directSubs : strictSubs).get(group);
    }

    /** True if {@code sub} is equal to or below {@code sup}. */
    boolean isSubsumedBy(int sub, int sup) {
        return sub == sup || sub == bottom || sup == top || strictSupers.get(sub).contains(sup);
    }

    /** The groups in {@code groups} that have no strict subgroup in {@code groups}. */
    Set<Integer> minimal(Collection<Integer> groups) {
        Set<Integer> result = new LinkedHashSet<>();
        for (int g : groups) {
            boolean min = true;
            for (int h : groups) {
                if (h != g && strictSupers.get(h).contains(g)) {
                    min = false;
                    break;
                }
            }
            if (min) {
                result.add(g);
            }
        }
        return result;
    }

    int size() {
        return members.size();
    }

    static final class Builder {

        private final Map<IRI, IRI> parent = new HashMap<>();
        private final List<IRI[]> subsumptions = new ArrayList<>();

        void add(IRI iri) {
            parent.putIfAbsent(iri, iri);
        }

        void equivalent(Collection<IRI> iris) {
            IRI first = null;
            for (IRI iri : iris) {
                add(iri);
                if (first == null) {
                    first = iri;
                } else {
                    union(first, iri);
                }
            }
        }

        void sub(IRI sub, IRI sup) {
            add(sub);
            add(sup);
            subsumptions.add(new IRI[] {sub, sup});
        }

        private IRI find(IRI x) {
            IRI root = x;
            while (!parent.get(root).equals(root)) {
                root = parent.get(root);
            }
            while (!parent.get(x).equals(root)) {
                IRI next = parent.get(x);
                parent.put(x, root);
                x = next;
            }
            return root;
        }

        private void union(IRI a, IRI b) {
            IRI ra = find(a);
            IRI rb = find(b);
            if (!ra.equals(rb)) {
                parent.put(ra, rb);
            }
        }

        Taxonomy build(IRI topIri, IRI bottomIri) {
            add(topIri);
            add(bottomIri);
            for (IRI[] s : subsumptions) {
                if (s[1].equals(bottomIri)) {
                    union(s[0], bottomIri);
                }
                if (s[0].equals(topIri)) {
                    union(s[1], topIri);
                }
            }
            Map<IRI, Integer> ids = new HashMap<>();
            Map<IRI, Integer> groupOf = new HashMap<>();
            List<Set<IRI>> members = new ArrayList<>();
            for (IRI iri : parent.keySet()) {
                IRI root = find(iri);
                Integer id = ids.get(root);
                if (id == null) {
                    id = members.size();
                    ids.put(root, id);
                    members.add(new LinkedHashSet<IRI>());
                }
                members.get(id).add(iri);
                groupOf.put(iri, id);
            }
            int n = members.size();
            int top = groupOf.get(topIri);
            int bottom = groupOf.get(bottomIri);

            List<Set<Integer>> edges = newSets(n);
            for (IRI[] s : subsumptions) {
                int a = groupOf.get(s[0]);
                int b = groupOf.get(s[1]);
                if (a != b) {
                    edges.get(a).add(b);
                }
            }

            List<Set<Integer>> strictSupers = newSets(n);
            for (int g = 0; g < n; g++) {
                if (g == bottom) {
                    continue;
                }
                Set<Integer> reach = strictSupers.get(g);
                Deque<Integer> todo = new ArrayDeque<>(edges.get(g));
                while (!todo.isEmpty()) {
                    int h = todo.pop();
                    if (h != g && reach.add(h)) {
                        todo.addAll(edges.get(h));
                    }
                }
                reach.remove(bottom);
                if (g != top) {
                    reach.add(top);
                } else {
                    reach.clear();
                }
            }
            for (int g = 0; g < n; g++) {
                if (g != bottom) {
                    strictSupers.get(bottom).add(g);
                }
            }

            List<Set<Integer>> strictSubs = newSets(n);
            for (int g = 0; g < n; g++) {
                for (int h : strictSupers.get(g)) {
                    strictSubs.get(h).add(g);
                }
            }

            List<Set<Integer>> directSupers = newSets(n);
            for (int g = 0; g < n; g++) {
                if (g == bottom) {
                    continue;
                }
                Set<Integer> sup = strictSupers.get(g);
                for (int s : sup) {
                    boolean direct = true;
                    for (int t : sup) {
                        if (t != s && strictSupers.get(t).contains(s)) {
                            direct = false;
                            break;
                        }
                    }
                    if (direct) {
                        directSupers.get(g).add(s);
                    }
                }
            }
            for (int g = 0; g < n; g++) {
                if (g != bottom && strictSubs.get(g).size() == 1) {
                    // only the bottom group is below g
                    directSupers.get(bottom).add(g);
                }
            }
            List<Set<Integer>> directSubs = newSets(n);
            for (int g = 0; g < n; g++) {
                for (int h : directSupers.get(g)) {
                    directSubs.get(h).add(g);
                }
            }
            return new Taxonomy(groupOf, freeze(members), freezeInts(strictSupers), freezeInts(directSupers),
                    freezeInts(strictSubs), freezeInts(directSubs), top, bottom);
        }

        private static List<Set<Integer>> newSets(int n) {
            List<Set<Integer>> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                list.add(new HashSet<Integer>());
            }
            return list;
        }

        private static List<Set<IRI>> freeze(List<Set<IRI>> sets) {
            List<Set<IRI>> out = new ArrayList<>(sets.size());
            for (Set<IRI> s : sets) {
                out.add(Collections.unmodifiableSet(s));
            }
            return out;
        }

        private static List<Set<Integer>> freezeInts(List<Set<Integer>> sets) {
            List<Set<Integer>> out = new ArrayList<>(sets.size());
            for (Set<Integer> s : sets) {
                out.add(Collections.unmodifiableSet(s));
            }
            return out;
        }
    }
}
