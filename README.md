# Konclude reasoner plugin for Protégé

## Installation

1. Get a Konclude binary for your platform which supports materializion (e.g. from [Koncludix](https://github.com/ISE-FIZKarlsruhe/Koncludix)) and check that
   `Konclude -h` runs. Konclude needs Qt5 (Core, Network, Xml, Concurrent) and libjemalloc on Linux.
2. Copy `dist/konclude-protege-0.1.0.jar` into the Protégé `plugins` folder
   (or `~/.Protege/plugins`) and restart Protégé.
3. **File ▸ Preferences ▸ Konclude**: set the Konclude command (or leave it empty to use the system property
   `konclude.path`, the environment variable `KONCLUDE_PATH`, or `Konclude` on the `PATH`), press **Test**.
4. **Reasoner ▸ Konclude**, then **Reasoner ▸ Start reasoner**.

Preferences:

| Setting | Meaning |
|---|---|
| Konclude command | Path to the executable, optionally followed by arguments placed *before* the Konclude command, e.g. a Docker prefix (see below). Quote paths containing spaces. |
| Threads | Konclude `-w` value: a number or `AUTO` (all cores). |
| Temp directory | Where exchange files are written (default: system temp dir). |
| Keep exchange files | Keep `input-N.ofn`, `output-N.owl.xml` and `konclude-N.log` for debugging. |
| Explanations | How the many small entailment checks of the explanation workbench reach Konclude: *OWLlink server, started automatically* (default), *OWLlink server at URL*, or *one Konclude run per check* (slow). See below. |
| OWLlink server URL | Only for *OWLlink server at URL*, e.g. `http://localhost:8080/`. |

Running Konclude in Docker its entrypoint already *is* Konclude, so the command ends with the image name. Create a folder, set it as **Temp directory**, and mount it at
the same path inside the container:

```
docker run --rm -v /Users/me/konclude-tmp:/Users/me/konclude-tmp konclude/konclude
```

The plugin appends `materialize -w … -i <temp dir>/input-N.ofn -o <temp dir>/output-N.owl.xml`. Do **not** add
`Konclude` after the image name: Konclude would then print its usage help instead of reasoning. The **Test**
button runs a small reasoning job through exactly this command and temp directory, so such mistakes (and
missing volume mounts) show up there.


## Behaviour and limits

- The recommended buffering mode is *buffering*: after edits, use **Reasoner ▸ Synchronize reasoner**.
  Each synchronisation is one Konclude run.
- **SWRL rules are ignored** (Konclude does not support them; they would also break its parser). The number of
  ignored rules is written to the Protégé log. Results can therefore differ from HermiT/Pellet on ontologies
  with rules.
- Annotation axioms are not sent to Konclude (they have no logical effect).
- Disjoint object/data properties are not computed (only the bottom property is reported).
- Data property values are Konclude's materialized values (asserted values plus sub-property closure).
- Very large integer literals beyond Konclude's range produce a warning in the Konclude log.
- Each new DL Query expression costs one Konclude run over the whole ontology (≈ the classification time).


## Code

| Package / class | Purpose |
|---|---|
| `de.fizkarlsruhe.konclude.owlapi.KoncludeReasoner` | OWL API 4 `OWLReasoner` implementation (usable without Protégé) |
| `…owlapi.KoncludeReasonerFactory` | `OWLReasonerFactory` |
| `…owlapi.KoncludeRunner` | writes the input, runs `Konclude materialize`, timeout/interrupt handling |
| `…owlapi.Materialization`, `Taxonomy` | streaming parser for Konclude's OWL/XML output; hierarchy with direct relations |
| `…owlapi.KoncludeSettings` | command, threads, temp directory, auto-detection |
| `de.fizkarlsruhe.konclude.protege.*` | Protégé registration (`plugin.xml`), preferences panel |
