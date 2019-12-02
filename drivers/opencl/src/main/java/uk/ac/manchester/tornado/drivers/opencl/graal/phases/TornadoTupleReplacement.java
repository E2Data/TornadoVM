package uk.ac.manchester.tornado.drivers.opencl.graal.phases;

import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.RawConstant;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValuePhiNode;
import org.graalvm.compiler.nodes.calc.AddNode;
import org.graalvm.compiler.nodes.java.LoadIndexedNode;
import org.graalvm.compiler.nodes.java.StoreIndexedNode;
import org.graalvm.compiler.phases.BasePhase;
import uk.ac.manchester.tornado.runtime.graal.phases.TornadoHighTierContext;
import org.graalvm.compiler.nodes.ConstantNode;

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

        ArrayList<ArrayList<Node>> sucs = new ArrayList<ArrayList<Node>>();
        for (Node n : graph.getNodes()) {
            // System.out.println("Node type: " + n.getNodeClass());
            if (n.getNodeClass().toString().contains("LoadFieldNode")) {
                // get the predecessors of all LoadFieldNodes
                // this is necessary because we plan to remove the LoadFieldNodes
                prev.add(n.predecessor());
            } else if (n.getNodeClass().toString().contains("UnboxNode")) {
                // get usages of UnboxNode
                for (Node in : n.usages()) {
                    System.out.println("   " + in.getNodeClass());
                }
                ArrayList<Node> ar = new ArrayList<Node>();
                for (Node sucNode : n.successors()) {
                    ar.add(sucNode);
                }
                sucs.add(ar);
                unboxedNodes.add(n);
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
            if (n.getNodeClass().toString().contains("InstanceOfNode")) {
                n.safeDelete();
            } else if (n.getNodeClass().toString().contains("FixedGuardNode")) {
                n.safeDelete();
            } else if (n.getNodeClass().toString().contains("PiNode")) {
                n.safeDelete();
            } else if (n.getNodeClass().toString().contains("IsNullNode")) {
                n.safeDelete();
            } else if (n.getNodeClass().toString().contains("UnboxNode")) {
                // replace at usages the UnboxNode with its predecessor
                // this is done because moving forward we will remove the UnboxNode
                Node pred = LoadIndexedNodes.remove();
                System.out.println("++ Predecessor of UnboxNode " + pred);
                n.replaceAtUsages(pred);
            }
        }

        // replace in the graph the UnboxNodes with their predecessors
        for (Node n : graph.getNodes()) {
            for (Node suc : n.successors()) {
                if (suc.getNodeClass().toString().contains("UnboxNode")) {
                    for (Node sucsuc : suc.successors()) {
                        n.replaceFirstSuccessor(suc, sucsuc);
                    }
                }
            }
        }

        // Remove the UnboxNodes from the graph
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

        // number of bytes to skip
        Constant cv = new RawConstant(1);
        ConstantNode c = new ConstantNode(cv, StampFactory.positiveInt());
        graph.addOrUnique(c);
        ValuePhiNode ph;
        AddNode secondIndex = null;
        for (Node n : graph.getNodes()) {
            if (n.getNodeClass().toString().contains("ValuePhiNode")) {
                ph = (ValuePhiNode) n;
                secondIndex = new AddNode(c, ph);
                graph.addOrUnique(secondIndex);
            }
        }

        // Queue<LoadIndexedNode> LoadIndexed2 = new LinkedList<>();
        Stack<LoadIndexedNode> LoadIndexedStack = new Stack<>();

        for (Node n : graph.getNodes()) {
            System.out.println("* Node " + n.getNodeClass());
            if (n.getNodeClass().toString().contains("LoadIndexedNode")) {
                LoadIndexedStack.push((LoadIndexedNode) n);
            }
        }
        if (LoadIndexedStack.size() > 0) {
            LoadIndexedNode lastLd = LoadIndexedStack.pop();
            for (Node in : lastLd.inputs()) {
                if (in.getNodeClass().toString().contains("ValuePhiNode")) {
                    lastLd.replaceFirstInput(in, secondIndex);
                }
            }

        }

        List<LoadIndexedNode> newlist = new ArrayList<>();
        for (Node n : graph.getNodes()) {
            if (n.getNodeClass().toString().contains("LoadIndexedNode")) {
                LoadIndexedNode cld = (LoadIndexedNode) n;
                newlist.add(cld);
                // JavaKind jvk = JavaKind.fromJavaClass(int.class);
                // LoadIndexedNode ld = new LoadIndexedNode(null, cld.array(), cld.index(),
                // jvk);

            }
        }

        for (int k = 0; k < newlist.size(); k++) {
            LoadIndexedNode cld = newlist.get(k);
            JavaKind jvk = JavaKind.fromJavaClass(int.class);
            LoadIndexedNode ld = graph.addOrUnique(new LoadIndexedNode(null, cld.array(), cld.index(), jvk));
            graph.replaceFixed(cld, ld);
        }

        for (Node n : graph.getNodes()) {
            if (n.getNodeClass().toString().contains("StoreIndexed")) {
                JavaKind jvk = JavaKind.fromJavaClass(int.class);
                StoreIndexedNode st = (StoreIndexedNode) n;
                StoreIndexedNode newst = graph.addOrUnique(new StoreIndexedNode(st.array(), st.index(), jvk, st.value()));
                graph.replaceFixed(st, newst);
                break;
            }
        }

    }
}
