import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import shark.*;

/** Usage: HeapCheck <converted.hprof>. See build.gradle. */
public class HeapCheck {
  static final String ACTIVITY = "com.reproducerapp.MainActivity";
  static final String HELPER = "com.facebook.react.views.scroll.MaintainVisibleScrollPositionHelper";
  static final String FABRIC = "com.facebook.react.fabric.FabricUIManager";

  static HeapGraph graph;

  static <T> List<T> list(kotlin.sequences.Sequence<T> s) {
    List<T> l = new ArrayList<>();
    s.iterator().forEachRemaining(l::add);
    return l;
  }

  // HeapGraph.findClassByName returned null for these dumps, so look the class up by name.
  static HeapObject.HeapClass findClass(String name) {
    for (HeapObject.HeapClass c : list(graph.getClasses())) {
      if (c.getName().equals(name)) return c;
    }
    return null;
  }

  static HeapObject field(HeapObject.HeapInstance i, String owner, String name) {
    HeapField f = i == null ? null : i.get(owner, name);
    return f == null ? null : f.getValue().getAsObject();
  }

  static Set<Long> destroyedActivities() {
    Set<Long> destroyed = new HashSet<>();
    int all = 0;
    HeapObject.HeapClass c = findClass(ACTIVITY);
    if (c != null) {
      for (HeapObject.HeapInstance a : list(c.getInstances())) {
        all++;
        HeapField d = a.get("android.app.Activity", "mDestroyed");
        if (d != null && Boolean.TRUE.equals(d.getValue().getAsBoolean())) destroyed.add(a.getObjectId());
      }
    }
    System.out.println("MainActivity instances: " + all + " (destroyed: " + destroyed.size() + ")");
    return destroyed;
  }

  static void helpers() {
    Set<Long> inListeners = new HashSet<>();
    HeapObject.HeapClass fabric = findClass(FABRIC);
    if (fabric != null) {
      for (HeapObject.HeapInstance ui : list(fabric.getInstances())) {
        HeapObject l = field(ui, FABRIC, "mListeners");
        HeapObject arr = l instanceof HeapObject.HeapInstance
            ? field((HeapObject.HeapInstance) l, "java.util.concurrent.CopyOnWriteArrayList", "array")
            : null;
        if (arr instanceof HeapObject.HeapObjectArray) {
          for (long id : ((HeapObject.HeapObjectArray) arr).readRecord().getElementIds()) inListeners.add(id);
        }
      }
    }
    int all = 0, listening = 0, attached = 0, withParent = 0;
    HeapObject.HeapClass c = findClass(HELPER);
    if (c != null) {
      for (HeapObject.HeapInstance h : list(c.getInstances())) {
        all++;
        if (!inListeners.contains(h.getObjectId())) continue;
        listening++;
        HeapObject sv = field(h, HELPER, "scrollView");
        if (sv instanceof HeapObject.HeapInstance) {
          HeapObject.HeapInstance v = (HeapObject.HeapInstance) sv;
          if (field(v, "android.view.View", "mAttachInfo") != null) attached++;
          if (field(v, "android.view.View", "mParent") != null) withParent++;
        }
      }
    }
    System.out.println("MaintainVisibleScrollPositionHelper instances: " + all
        + " (in FabricUIManager.mListeners: " + listening
        + "; their scroll views attached to a window: " + attached
        + ", with a parent: " + withParent + ")");
  }

  static int paths(Set<Long> destroyed, boolean ignoreListeners, boolean print) {
    List<ReferenceMatcher> matchers =
        new ArrayList<>(AndroidReferenceMatchers.Companion.getIgnoredReferencesOnly());
    if (ignoreListeners) {
      matchers.add(new IgnoredReferenceMatcher(
          new ReferencePattern.InstanceFieldPattern(FABRIC, "mListeners")));
    }
    HeapAnalysis result = new HeapAnalyzer(step -> {}).analyze(
        new File("unused"), graph, g -> destroyed, matchers, false, List.of(),
        MetadataExtractor.Companion.getNO_OP());
    if (!(result instanceof HeapAnalysisSuccess)) {
      System.out.println(result);
      return -1;
    }
    HeapAnalysisSuccess s = (HeapAnalysisSuccess) result;
    List<Leak> leaks = new ArrayList<>(s.getApplicationLeaks());
    leaks.addAll(s.getLibraryLeaks());
    int traces = 0;
    for (Leak leak : leaks) {
      traces += leak.getLeakTraces().size();
      if (!print) continue;
      List<String> entries = new ArrayList<>();
      for (LeakTrace lt : leak.getLeakTraces()) {
        for (LeakTraceReference r : lt.getReferencePath()) {
          if (r.getReferenceType() == LeakTraceReference.ReferenceType.ARRAY_ENTRY) {
            entries.add(r.getReferenceDisplayName());
          }
        }
      }
      LeakTrace t = leak.getLeakTraces().get(0);
      System.out.println();
      System.out.println("Shortest path from a GC root, the same for " + leak.getLeakTraces().size()
          + " destroyed MainActivity (array entries " + String.join(" ", entries) + "):");
      System.out.println("  GC root: " + t.getGcRootType());
      for (LeakTraceReference r : t.getReferencePath()) {
        boolean entry = r.getReferenceType() == LeakTraceReference.ReferenceType.ARRAY_ENTRY;
        System.out.println("  " + r.getOriginObject().getClassName()
            + (entry ? " [i]" : "." + r.getReferenceDisplayName()));
      }
      System.out.println("  " + t.getLeakingObject().getClassName() + " (mDestroyed = true)");
    }
    return traces;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      System.err.println("usage: HeapCheck <converted.hprof>");
      System.exit(2);
    }
    File hprof = new File(args[0]);
    try (CloseableHeapGraph g = HprofHeapGraph.Companion.openHeapGraph(
        hprof, null, HprofIndex.Companion.defaultIndexedGcRootTags())) {
      graph = g;
      Set<Long> destroyed = destroyedActivities();
      helpers();
      if (destroyed.isEmpty()) return;
      int reachable = paths(destroyed, false, true);
      System.out.println();
      System.out.println("Destroyed MainActivity reachable: " + reachable);
      System.out.println("Destroyed MainActivity reachable with FabricUIManager.mListeners ignored: "
          + paths(destroyed, true, false));
    }
  }
}
