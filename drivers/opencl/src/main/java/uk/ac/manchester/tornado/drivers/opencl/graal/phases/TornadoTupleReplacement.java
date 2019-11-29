package uk.ac.manchester.tornado.drivers.opencl.graal.phases;

import jdk.vm.ci.meta.JavaField;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaField;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.graph.iterators.NodeIterable;
import org.graalvm.compiler.nodes.FixedGuardNode;
import org.graalvm.compiler.nodes.PiNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.calc.AddNode;
import org.graalvm.compiler.nodes.calc.IsNullNode;
import org.graalvm.compiler.nodes.extended.UnboxNode;
import org.graalvm.compiler.nodes.java.InstanceOfNode;
import org.graalvm.compiler.nodes.java.LoadFieldNode;
import org.graalvm.compiler.nodes.java.LoadIndexedNode;
import org.graalvm.compiler.phases.BasePhase;
import uk.ac.manchester.tornado.drivers.opencl.graal.nodes.TupleFieldNode;
import uk.ac.manchester.tornado.runtime.graal.phases.TornadoHighTierContext;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Stack;

public class TornadoTupleReplacement extends BasePhase<TornadoHighTierContext> {
    @Override
    protected void run(StructuredGraph graph, TornadoHighTierContext context) {
        List<Node> prev = new ArrayList<>();
        List<Node> unboxedNodes = new ArrayList<>();
        // NodeIterable<Node> sucs = null;
        ArrayList<ArrayList<Node>> sucs = new ArrayList<ArrayList<Node>>();
        for (Node n : graph.getNodes()) {
            // System.out.println("Node type: " + n.getNodeClass());
            if (n.getNodeClass().toString().contains("LoadFieldNode")) {
                prev.add(n.predecessor());
                // System.out.println("LoadFieldNode... this has to go");
                // n.safeDelete();
            } else if (n.getNodeClass().toString().contains("UnboxNode")) {
                // System.out.println("UnboxNode... this has to go");
                System.out.println("USAGES of UnBoxNode: ");
                for (Node in : n.usages()) {
                    System.out.println("   " + in.getNodeClass());
                }
                ArrayList<Node> ar = new ArrayList<Node>();
                for (Node sucNode : n.successors()) {
                    ar.add(sucNode);
                }
                sucs.add(ar);
                unboxedNodes.add(n);
                // n.safeDelete();
            }
        }
        if (prev != null) {
            for (Node n : prev) {
                System.out.println("prev of loadfieldNode " + n.getNodeClass());
            }
        }

        if (sucs != null) {
            for (int i = 0; i < sucs.size(); i++) {
                ArrayList<Node> ar = sucs.get(i);
                System.out.println("=== Successors for " + i + " LoadField ");
                for (Node suc : ar) {
                    System.out.println("  " + suc.getNodeClass());
                    // suc.replaceAtPredecessor(prev.get(i));
                    // System.out.println(" Predecessor of node " + suc.getNodeClass() + " is " +
                    // suc.predecessor().getNodeClass());
                    // suc.replaceAndDelete();
                }
            }
        }

        ArrayList<Node> dataNodes = new ArrayList<>();
        int i = 0;
        for (Node n : graph.getNodes()) {

            // System.out.println("Node type: " + n.getNodeClass());
            if (n.getNodeClass().toString().contains("LoadFieldNode")) {
                // prev.add(n.predecessor());
                // System.out.println("Replace " + n.getNodeClass() + " with " +
                // sucs.get(i).get(0).getNodeClass() + " i = " + i);
                System.out.println("Replace " + n.getNodeClass() + " with " + unboxedNodes.get(i).getNodeClass() + " i = " + i);
                for (Node in : n.inputs()) {
                    System.out.println("Inputs " + in.getNodeClass());
                    dataNodes.add(in);
                }
                // n.replaceAndDelete(usages.get(i));
                // n.replaceAndDelete(sucs.get(i).get(0));
                n.replaceAndDelete(unboxedNodes.get(i));
                i++;
            }
        }

        // ---- Remove Unbox Nodes----------
        Queue<Node> LoadIndexedNodes = new LinkedList<>();

        for (Node n : graph.getNodes()) {
            if (n.getNodeClass().toString().contains("LoadIndexed")) {
                LoadIndexedNodes.add(n);
            }
        }

        // ArrayList<Node> loadIndexNodes = new ArrayList<>();
        for (Node n : graph.getNodes()) {
            // System.out.println("Node type: " + n.getNodeClass());
            if (n.getNodeClass().toString().contains("InstanceOfNode")) {
                System.out.println("InstanceNode... this has to go");
                n.safeDelete();
            } else if (n.getNodeClass().toString().contains("FixedGuardNode")) {
                System.out.println("FixedGuardNode... this has to go");
                n.safeDelete();
            } else if (n.getNodeClass().toString().contains("PiNode")) {
                System.out.println("PiNode... this has to go");
                n.safeDelete();
            } else if (n.getNodeClass().toString().contains("IsNullNode")) {
                System.out.println("IsNullNode... this has to go");
                n.safeDelete();
            } else if (n.getNodeClass().toString().contains("UnboxNode")) {
                System.out.println("UnboxNode... this has to go");
                int numOfusages = n.getUsageCount();
                System.out.println("Number of usages of UnboxNode: " + numOfusages);
                for (int j = 0; j < numOfusages; j++) {
                    System.out.println("Usage " + j + " : " + n.getUsageAt(j).getNodeClass());
                }
                // if (n.predecessor() != null) {
                Node pred = LoadIndexedNodes.remove();
                System.out.println("++ Predecessor of UnboxNode " + pred);
                n.replaceAtUsages(pred);
                // n.safeDelete();
                // }
                // n.replaceAndDelete(sucs.get(j).get(0));
                // j++;
                // n.safeDelete();
            }
        }

        for (Node n : graph.getNodes()) {
            for (Node suc : n.successors()) {
                if (suc.getNodeClass().toString().contains("UnboxNode")) {
                    for (Node sucsuc : suc.successors()) {
                        System.out.println("Replace " + suc.getNodeClass() + " with " + sucsuc.getNodeClass());
                        n.replaceFirstSuccessor(suc, sucsuc);
                    }
                }
            }
        }

        for (Node n : graph.getNodes()) {
            if (n.getNodeClass().toString().contains("UnboxNode")) {
                n.safeDelete();
            }
        }
        // -----------
        Node newInput = null;
        Node oldInput;
        for (Node n : graph.getNodes()) {
            if (n.getNodeClass().toString().contains("BoxNode")) {
                for (Node inputs : n.inputs()) {
                    newInput = inputs;
                }

                Node suc = n.successors().first();
                for (Node sucInputs : suc.inputs()) {
                    if (sucInputs.getNodeClass().toString().contains("BoxNode")) {
                        oldInput = sucInputs;
                        System.out.println("For BoxNode, replace " + oldInput + " with " + newInput);
                        suc.replaceFirstInput(oldInput, newInput);
                    }
                }
            }
        }

        for (Node n : graph.getNodes()) {
            for (Node suc : n.successors()) {
                if (suc.getNodeClass().toString().contains("BoxNode")) {
                    for (Node sucsuc : suc.successors()) {
                        n.replaceFirstSuccessor(suc, sucsuc);
                    }
                    suc.safeDelete();
                }
            }
        }
    }
}
