# AUDIT_FINDINGS.md

Findings from the bulkhead pattern completion audit (REFACTORING.md sub-steps
2.12 through 2.20). Each entry describes a concrete observation made during the
audit, its priority, and a suggestion for where it should land — a later sub-step,
TODO.md, IDEAS.md, an ADR update, or no action.

Findings are not commitments. They are inputs for the review session that follows
each audit sub-step. Decisions are made there.

When this file's findings have all been routed to their respective destinations,
the file is deleted along with REFACTORING.md at the end of the bulkhead refactor.

---

## 2.12.3 — Race zwischen `markRemoved` und `onSnapshotChange` während Hot-Swap nicht getestet

**Bereich:** 2.12.3
**Priorität:** nachrangig
**Vorschlag:** Aufnahme in 2.20 (Bulkhead Integration Test Module). Dort gehört die End-to-End-Verifikation hin.

**Beobachtung:**

`BulkheadHotPhase.shutdown()` schließt die `subscription`, `markRemoved` ruft
`shutdown()` vor dem Installieren der `RemovedPhase`. Aber: ein Hot-Swap, der
gerade vom Dispatcher angestoßen ist und in der Mitte von `onSnapshotChange`
steht, könnte mit `markRemoved` racen. Insbesondere wenn `markRemoved` die
Subscription schließt, während der Dispatcher gerade die nächste
Listener-Notification aufruft, oder wenn `onSnapshotChange` gerade die alte
Strategy in `closeStrategy(...)` schließt, während `markRemoved` schon den
RemovedPhase-Sentinel installiert hat.

Code-Lesung suggeriert keine Korrektheits-Probleme: `closeStrategy` ist
best-effort, `subscription = null` nach Close, RemovedPhase blockt alle
weiteren execute-Calls. Aber kein Test pinnt diese Reihenfolge unter Last.

**Begründung der Priorität:**

Plausibel benigne. Fehlende Test-Abdeckung eher Hygiene-Lücke als akute
Korrektheits-Frage.

---

## 2.12.4 — Strategy-Konstruktions-Fehler beim Cold→Hot-Übergang nicht test-abgedeckt

**Bereich:** 2.12.4
**Priorität:** wichtig
**Vorschlag:** Aufnahme in 2.20 (Integration-Test-Modul) als Negative-Test, oder eigener Sub-Step-Test in `BulkheadHotPhaseStrategyMaterializationTest` falls trivial schreibbar.

**Beobachtung:**

`BulkheadStrategyFactory.create(...)` kann theoretisch werfen — z.B. wenn ein
Algorithmen-Sub-Config-Konstruktor in der Validierung scheitert (Vegas
`smoothingTimeConstant <= 0` o.Ä.). Compact-Constructors fangen das beim
Snapshot-Build, aber falls jemals ein Sub-Config-Setter durchschlüpft (etwa via
ServiceLoader-Provider oder zukünftige SPI-Strategy), würde der Wurf vom
`BulkheadHotPhase`-Konstruktor durch `cold.execute` an den Caller propagieren.
Die `phase`-Referenz bleibt im `ColdPhase`, der nächste Call würde retry. Das
Verhalten ist plausibel aber nirgends spezifiziert oder getestet.

Beim Hot-Swap (in `onSnapshotChange`) noch heikler: ein Wurf hier würde an die
Live-Container-Subscription propagieren. Der Caller (Dispatcher-Thread) bekommt
die Exception. Die `strategy`-Volatile bleibt auf der alten Strategy, aber
der Snapshot wurde bereits committet. Sich-aus-dem-Tritt-bringende
Snapshot-vs-Runtime-Diskrepanz.

**Begründung der Priorität:**

Kein bekannter Pfad löst das aktuell aus, aber das ist eine fragile Annahme.
Kleiner Test, der einen synthetisch werfenden Sub-Config-Provider injiziert,
würde die Erwartungen festschreiben.

---

## 2.12.4 — `closeStrategy(...)`-Wurf-Pfad hat Logging aber keinen Test

**Bereich:** 2.12.4
**Priorität:** nachrangig
**Vorschlag:** Aufnahme in 2.20 — ein synthetischer `AutoCloseable`-Strategy-Mock, der beim Close wirft, würde den Pfad pinnen.

**Beobachtung:**

`BulkheadHotPhase.closeStrategy(...)` (Zeilen 244–257) ist defensiv gegen Worf:
loggt warn und schluckt. Aktuell implementiert keine der vier Strategies
`AutoCloseable`, also läuft der Pfad nie. Der `instanceof AutoCloseable closeable`
Guard überspringt sofort. Der Code ist forward-looking — designed für eine
zukünftige Strategy mit Subscription-style-Resourcen.

Kein Test verifiziert: (a) dass `closeStrategy` korrekt unterscheidet zwischen
„nicht Closable" (no-op) und „Closable, wirft" (loggt-und-schluckt),
(b) dass nach einem Close-Wurf der Hot-Swap erfolgreich abgeschlossen ist
(neue Strategy installiert, alte „best-effort" beendet).

**Begründung der Priorität:**

Forward-looking Code ohne Test. Nicht heute brennend; lohnt erst ab erster
realer `AutoCloseable`-Strategy.

---

## 2.17.3 — Lifecycle-Kompatibilität strukturell intakt, aber nicht test-bewiesen

**Bereich:** 2.17.3
**Priorität:** wichtig (positives Finding mit Test-Lücke)
**Vorschlag:** Aufnahme der Test-Coverage in 2.20 (Bulkhead Integration Test Module). Dort
explizit drei Szenarien:
(a) Wrapper über cold→hot-Übergang,
(b) Wrapper über Strategy-Hot-Swap zur Laufzeit,
(c) Wrapper nach struktureller Removal (`ComponentRemovedException`).

**Beobachtung:**

Strukturell sind die Wrapper mit der neuen Architektur kompatibel. Nach ADR-033 implementiert
`InqBulkhead<A, R>` direkt `InqDecorator<A, R>` — die Brücke zwischen Lifecycle-Komponente und
Pipeline-Schicht ist Vertrag, kein Methoden-Referenz-Trick.

Die drei Lifecycle-Szenarien sind im Code korrekt aufgesetzt:

- **Cold→Hot:** Jeder Wrapper-Aufruf landet in `ImperativeLifecyclePhasedComponent.execute(...)`,
  das `phase.get()` jedes Mal frisch liest. `ColdPhase.execute(...)` führt den CAS auf den
  Hot-Phase-Container durch und delegiert. Der Wrapper merkt nichts vom Übergang — aus seiner
  Sicht ist es ein gewöhnlicher `next.execute(...)`-Call.
- **Strategy-Hot-Swap:** `BulkheadHotPhase.strategy` ist `volatile`. `BulkheadHotPhase.execute(...)`
  ruft `tryAcquire(...)` auf, das `strategy` re-liest. Eine via Snapshot-Patch ausgetauschte
  Strategy wird beim nächsten Wrapper-Aufruf aktiv.
- **Removal:** `ImperativeLifecyclePhasedComponent.execute(...)` prüft auf `RemovedPhase` und
  wirft `ComponentRemovedException`. Pro Aufruf, kein Caching beim Wrapper.

Das Problem: kein Test pinnt diese Eigenschaften für die Wrapper-Schicht fest.

- `WrapperPipelineTest`, `SyncPipelineTerminalTest`, `ProxyChainCompositionTest`,
  `InqProxyFactorySyncTest`, `ProxyPipelineTerminalTest`, `AsyncWrapperPipelineTest`,
  `HybridProxyPipelineTerminalTest`, `InqProxyFactoryAsyncTest` etc. nutzen ausschließlich
  synthetische `LayerAction`/`AsyncLayerAction`-Lambdas — kein einziges echtes
  `InqBulkhead`.
- `InqBulkheadTest` testet cold→hot, in-place semaphore tuning, removal — aber nur direkt
  über `bulkhead.execute(...)`. Nie eingebettet in einen Wrapper, nie über einen Proxy.

Wenn morgen jemand einen Caching-Adapter zwischen Bulkhead und Wrapper einführt (z. B.
einen `LayerAction` aus einem konkreten `BulkheadHotPhase` extrahiert statt aus dem
`InqBulkhead`-Handle), gibt es keinen Test, der den Hot-Swap-Verlust auffangen würde.

**Begründung der Priorität:**

Die Lifecycle-Kompatibilität ist heute korrekt. Die Test-Lücke ist die Form, in der die
korrekte Eigenschaft als nicht-load-bearing wahrgenommen wird, bis sie es gewesen wäre.
„Wichtig" statt „kritisch", weil heute keine Code-Änderung nötig ist; aber 2.20 muss die
Eigenschaften ans Test-Netz nageln.

---

## 2.17.4 — Wrapper-/Proxy-Tests verwenden ausschließlich synthetische `LayerAction`s

**Bereich:** 2.17.4
**Priorität:** wichtig
**Vorschlag:** Aufnahme in 2.20 (Bulkhead Integration Test Module). Mindestens je ein Test
pro Wrapper-Familie, der das Wrapping eines via `Inqudium.configure()` aufgebauten
`InqBulkhead` exerciert.

**Beobachtung:**

Eine systematische Sichtung der Wrapper-/Proxy-Tests in beiden Modulen zeigt: keiner baut
eine reale `InqBulkhead` (`Inqudium.configure()...build()` oder ähnlich) und reicht sie an
einen Wrapper / eine `SyncPipelineTerminal` / einen Proxy weiter. Belegstellen
(repräsentativ, nicht erschöpfend):

- `inqudium-core/src/test/java/eu/inqudium/core/pipeline/WrapperPipelineTest.java:43-128`:
  alle Wrapper-Konstruktionen verwenden ad-hoc-Lambdas; `trackingAction(name, log)` als
  hand-rolled `LayerAction`.
- `inqudium-core/src/test/java/eu/inqudium/core/pipeline/proxy/ProxyChainCompositionTest.java:95-317`:
  diverse `LayerAction<Void, Object> innerAction = (chainId, callId, arg, next) -> { … }`,
  keine `InqBulkhead`-Referenz im File.
- `inqudium-imperative/src/test/java/eu/inqudium/imperative/core/pipeline/AsyncWrapperPipelineTest.java:179`:
  hand-rolled `AsyncLayerAction`.
- Weder `decorate*` noch `bulkhead::execute` taucht in irgendeinem Test in
  `inqudium-imperative/src/test/.../bulkhead/` auf.

Im Gegenzug ist die Bulkhead-Lifecycle-Suite (`InqBulkheadTest`, `BulkheadRemovalTest`,
`BulkheadHotPhaseStrategyMaterializationTest`, …) blind für die Wrapper-Schicht — sie ruft
`bulkhead.execute(...)` direkt auf.

Die zwei Test-Welten — Wrapper-Welt und Bulkhead-Welt — überlappen sich nicht. Das ist
genau der Zustand, den 2.20 (laut REFACTORING.md Zeile 772–805) auflösen soll: ein
Integrations-Test-Modul, das den Stack als Ganzes verifiziert.

**Begründung der Priorität:**

Das Findings-Catalog-Item als solches ist nur ein Coverage-Befund, keine sofortige
Code-Drift. Die Priorität „wichtig" ergibt sich daraus, dass diese Lücke der Grund ist, warum
das verwandte 2.17.3-Finding (Lifecycle-Kompatibilität) bisher unauffällig war: ohne
End-to-End-Test schlägt der Drift nicht an.

