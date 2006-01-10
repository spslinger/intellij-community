package com.intellij.compiler;

import com.intellij.j2ee.j2eeDom.J2EEModuleProperties;
import com.intellij.j2ee.j2eeDom.application.J2EEApplicationModel;
import com.intellij.j2ee.j2eeDom.application.ModuleInApplication;
import com.intellij.j2ee.j2eeDom.xmlData.ObjectsList;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.Pair;
import com.intellij.util.graph.CachingSemiGraph;
import com.intellij.util.graph.DFSTBuilder;
import com.intellij.util.graph.Graph;
import com.intellij.util.graph.GraphGenerator;

import java.util.*;

/**
 * @author dsl
 */
public final class ModuleCompilerUtil {
  private static final Logger LOG = Logger.getInstance("#com.intellij.compiler.ModuleCompilerUtil");
  private ModuleCompilerUtil() { }

  public static Module[] getDependencies(Module module) {
    if (!ModuleType.J2EE_APPLICATION.equals(module.getModuleType())) {
      return ModuleRootManager.getInstance(module).getDependencies();
    }
    List<Module> result = new ArrayList<Module>();
    final ObjectsList<ModuleInApplication> modules = ((J2EEApplicationModel)J2EEModuleProperties.getInstance(module)).getModules();
    for (final ModuleInApplication moduleInApplication : modules) {
      final Module depModule = moduleInApplication.getReferenceModule();
      if (depModule != null && !dependsOn(depModule, module)) {
        result.add(depModule);
      }
    }
    return result.toArray(new Module[result.size()]);
  }

  private static boolean dependsOn(final Module dependant, final Module dependee) {
    return new Object(){
      final Set<Module> myChecked = new HashSet<Module>();
      boolean dependsOn(Module dependant, Module dependee) {
        if (dependant.equals(dependee)) {
          return true;
        }
        myChecked.add(dependant);
        final Module[] dependencies = ModuleRootManager.getInstance(dependant).getDependencies();
        for (final Module dependency : dependencies) {
          if (!myChecked.contains(dependency)) { // prevent cycles
            if (dependsOn(dependency, dependee)) {
              return true;
            }
          }
        }
        return false;
      }
    }.dependsOn(dependant, dependee);
  }

  public static Comparator<Module> moduleDependencyComparator(Project project) {
    final Module[] modules = ModuleManager.getInstance(project).getModules();
    final Graph<Module> graph = createModuleGraph(modules);
    DFSTBuilder<Module> builder = new DFSTBuilder<Module>(graph);
    return builder.comparator();
  }

  public static Graph<Module> createModuleGraph(final Module[] modules) {
    return GraphGenerator.create(CachingSemiGraph.create(new GraphGenerator.SemiGraph<Module>() {
      public Collection<Module> getNodes() {
        return Arrays.asList(modules);
      }

      public Iterator<Module> getIn(Module module) {
        return Arrays.asList(getDependencies(module)).iterator();
      }
    }));
  }

  public static List<Chunk<Module>> getSortedModuleChunks(Project project, Module[] modules) {
    final Module[] allModules = ModuleManager.getInstance(project).getModules();
    return getSortedModuleChunks(modules, createModuleGraph(allModules));
  }

  public static List<Chunk<Module>> getSortedModuleChunks(Module[] modules, Graph<Module> moduleGraph) {
    final Graph<Chunk<Module>> graph = toChunkGraph(moduleGraph);
    final List<Chunk<Module>> chunks = new ArrayList<Chunk<Module>>(moduleGraph.getNodes().size());
    for (final Chunk<Module> chunk : graph.getNodes()) {
      chunks.add(chunk);
    }
    DFSTBuilder<Chunk<Module>> builder = new DFSTBuilder<Chunk<Module>>(graph);
    if (!builder.isAcyclic()) {
      LOG.error("Acyclic graph expected");
      return null;
    }

    Collections.sort(chunks, builder.comparator());

    final Set<Module> modulesSet = new HashSet<Module>(Arrays.asList(modules));
    // leave only those chunks that contain at least one module from modules
    for (Iterator<Chunk<Module>> it = chunks.iterator(); it.hasNext();) {
      final Chunk<Module> chunk = it.next();
      if (!intersects(chunk.getNodes(), modulesSet)) {
        it.remove();
      }
    }
    return chunks;
  }

  private static boolean intersects(Set set1, Set set2) {
    for (final Object item : set1) {
      if (set2.contains(item)) {
        return true;
      }
    }
    return false;
  }

  public static <Node> Graph<Chunk<Node>> toChunkGraph(final Graph<Node> graph) {
    final DFSTBuilder<Node> builder = new DFSTBuilder<Node>(graph);
    final LinkedList<Pair<Integer, Integer>> sccs = builder.getSCCs();

    final List<Chunk<Node>> chunks = new ArrayList<Chunk<Node>>(sccs.size());
    final Map<Node, Chunk<Node>> nodeToChunkMap = new HashMap<Node, Chunk<Node>>();
    for (Pair<Integer, Integer> scc : sccs) {
      final int num = scc.getFirst();
      final int size = scc.getSecond();
      final Set<Node> chunkNodes = new HashSet<Node>();
      final Chunk<Node> chunk = new Chunk<Node>(chunkNodes);
      chunks.add(chunk);
      for (int j = 0; j < size; j++) {
        final Node node = builder.getNodeByTNumber(num + j);
        chunkNodes.add(node);
        nodeToChunkMap.put(node, chunk);
      }
    }

    return GraphGenerator.create(CachingSemiGraph.create(new GraphGenerator.SemiGraph<Chunk<Node>>() {
      public Collection<Chunk<Node>> getNodes() {
        return chunks;
      }

      public Iterator<Chunk<Node>> getIn(Chunk<Node> chunk) {
        final Set<Node> chunkNodes = chunk.getNodes();
        final Set<Chunk<Node>> ins = new HashSet<Chunk<Node>>();
        for (final Node node : chunkNodes) {
          for (Iterator<Node> nodeIns = graph.getIn(node); nodeIns.hasNext();) {
            final Node in = nodeIns.next();
            if (!chunk.containsNode(in)) {
              ins.add(nodeToChunkMap.get(in));
            }
          }
        }
        return ins.iterator();
      }
    }));
  }

  public static void sortModules(final Project project, final List<Module> modules) {
    final Application applicationEx = ApplicationManager.getApplication();
    if (applicationEx.isDispatchThread()) {
      Collections.sort(modules, moduleDependencyComparator(project));
    }
    else {
      applicationEx.runReadAction(new Runnable() {
        public void run() {
          Collections.sort(modules, moduleDependencyComparator(project));
        }
      });
    }
  }
}
