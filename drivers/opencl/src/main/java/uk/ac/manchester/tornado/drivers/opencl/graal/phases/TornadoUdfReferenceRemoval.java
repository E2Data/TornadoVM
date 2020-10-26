package uk.ac.manchester.tornado.drivers.opencl.graal.phases;

import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.*;
import org.graalvm.compiler.nodes.java.LoadFieldNode;
import org.graalvm.compiler.phases.BasePhase;
import uk.ac.manchester.tornado.runtime.graal.phases.TornadoHighTierContext;

import java.util.ArrayList;

public class TornadoUdfReferenceRemoval extends BasePhase<TornadoHighTierContext> {

    @Override
    protected void run(StructuredGraph graph, TornadoHighTierContext context) {

        LoadFieldNode ldmd = null;
        LoadFieldNode ldudf = null;
        ArrayList<Node> nodesToBeDeleted = new ArrayList<>();

        for (Node n : graph.getNodes()) {
            if (n instanceof LoadFieldNode) {
                if (n.toString().contains("mdm") || n.toString().contains("mdr")) {
                    ldmd = (LoadFieldNode) n;
                }
                if (n.toString().contains("udf")) {
                    ldudf = (LoadFieldNode) n;
                }
            }
        }

        if (ldmd != null && ldudf != null) {
            nodesToBeDeleted.add(ldmd);
            nodesToBeDeleted.add(ldudf);

            // start with LoadField#mdm
            boolean isPredFixedGuardMdm = false;
            Node predmdm = ldmd.predecessor();
            if (predmdm instanceof FixedGuardNode) {
                predmdm = getLdPred(ldmd, nodesToBeDeleted);
                isPredFixedGuardMdm = true;
            }

            Node sucmdm = ldmd.successors().first();
            if (sucmdm instanceof FixedGuardNode) {
                sucmdm = getLdSuc(ldmd, nodesToBeDeleted);
            }

            Node sucmdmPrev = sucmdm.predecessor();
            sucmdmPrev.replaceFirstSuccessor(sucmdm, null);
            if (isPredFixedGuardMdm) {
                Node predSuc = predmdm.successors().first();
                predSuc.replaceAtPredecessor(sucmdm);
                predmdm.replaceFirstSuccessor(predSuc, sucmdm);
            } else {
                ldmd.replaceAtPredecessor(sucmdm);
                predmdm.replaceFirstSuccessor(ldmd, sucmdm);
            }

            // continue with LoadField#udf
            boolean isPredFixedGuardUdf = false;
            Node predudf = ldudf.predecessor();
            if (predudf instanceof FixedGuardNode) {
                predudf = getLdPred(ldudf, nodesToBeDeleted);
                isPredFixedGuardUdf = true;
            }

            Node sucudf = ldudf.successors().first();
            if (sucudf instanceof FixedGuardNode) {
                sucudf = getLdSuc(ldudf, nodesToBeDeleted);
            }
            Node sucudfPrev = sucudf.predecessor();
            sucudfPrev.replaceFirstSuccessor(sucudf, null);
            if (isPredFixedGuardUdf) {
                Node predSuc = predudf.successors().first();
                predSuc.replaceAtPredecessor(sucudf);
                predudf.replaceFirstSuccessor(predSuc, sucudf);
            } else {
                ldudf.replaceAtPredecessor(sucudf);
                predudf.replaceFirstSuccessor(ldudf, sucudf);
            }

            ArrayList<Node> nodesToBeDeletedTotal = new ArrayList<>();

            for (int i = 0; i < nodesToBeDeleted.size(); i++) {
                Node n = nodesToBeDeleted.get(i);
                nodesToBeDeletedTotal.add(n);
                for (Node in : n.inputs()) {
                    if (!(in instanceof ParameterNode)) {
                        if (!nodesToBeDeletedTotal.contains(in)) {
                            nodesToBeDeletedTotal.add(in);
                        }
                    } else {
                        n.replaceFirstInput(in, null);
                    }

                }
                for (Node us : n.usages()) {
                    if (!nodesToBeDeletedTotal.contains(us)) {
                        nodesToBeDeletedTotal.add(us);
                    }

                }
            }

            for (Node n : nodesToBeDeletedTotal) {
                removeFromFrameState(n, graph);
                for (Node us : n.usages()) {
                    n.removeUsage(us);
                }
                n.clearInputs();
                n.safeDelete();

            }

            for (Node n : graph.getNodes()) {
                if (n instanceof FixedGuardNode) {
                    removeFixed(n, graph);
                }
            }
        }

        for (FrameState f : graph.getNodes().filter(FrameState.class)) {
            for (int i = 0; i < f.virtualObjectMappingCount(); i++) {
                f.virtualObjectMappings().remove(i);
            }

            for (Node val : f.values()) {
                if (val != null && val.isDeleted()) {
                    f.values().remove(val);
                }
            }

        }

        // for (Node n : graph.getNodes()) {
        // // if (n instanceof FrameState) {
        // System.out.println("*** Node: " + n);
        // for (Node in : n.inputs()) {
        // System.out.println("\t+ Input: " + in);
        // }
        // for (Node us : n.usages()) {
        // System.out.println("\t- Usage: " + us);
        // }
        // if (n instanceof FrameState) {
        // for (Node v : ((FrameState) n).values()) {
        // System.out.println("\t& Value: " + v);
        // }
        // }
        // // }
        // }
    }

    public Node getLdPred(LoadFieldNode ldf, ArrayList<Node> nodesToBeDeleted) {
        Node pred = ldf.predecessor();
        while (pred instanceof FixedGuardNode) {
            nodesToBeDeleted.add(pred);
            pred = pred.predecessor();
        }
        return pred;
    }

    public Node getLdSuc(LoadFieldNode ldf, ArrayList<Node> nodesToBeDeleted) {
        Node suc = ldf.successors().first();
        while (suc instanceof FixedGuardNode) {
            nodesToBeDeleted.add(suc);
            suc = suc.successors().first();
        }
        return suc;
    }

    public static void removeFromFrameState(Node del, StructuredGraph graph) {
        for (Node n : graph.getNodes()) {
            if (n instanceof FrameState) {
                FrameState f = (FrameState) n;
                if (f.values().contains(del)) {
                    f.values().remove(del);
                }
                // } else if (f.inputs().contains(del)) {
                // f.replaceFirstInput(del, null);
                // }
            }
        }
    }

    public static void removeFixed(Node n, StructuredGraph graph) {
        Node pred = n.predecessor();
        Node suc = n.successors().first();

        n.replaceFirstSuccessor(suc, null);
        n.replaceAtPredecessor(suc);
        pred.replaceFirstSuccessor(n, suc);

        removeFromFrameState(n, graph);

        for (Node us : n.usages()) {
            n.removeUsage(us);
        }
        n.clearInputs();

        n.safeDelete();

        return;
    }
}
