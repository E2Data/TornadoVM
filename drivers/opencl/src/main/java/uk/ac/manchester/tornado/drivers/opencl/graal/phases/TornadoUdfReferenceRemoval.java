package uk.ac.manchester.tornado.drivers.opencl.graal.phases;

import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.FixedGuardNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.extended.UnboxNode;
import org.graalvm.compiler.nodes.java.LoadFieldNode;
import org.graalvm.compiler.phases.BasePhase;
import uk.ac.manchester.tornado.runtime.graal.phases.TornadoHighTierContext;

import java.util.ArrayList;

public class TornadoUdfReferenceRemoval extends BasePhase<TornadoHighTierContext> {

    @Override
    protected void run(StructuredGraph graph, TornadoHighTierContext context) {

        LoadFieldNode ldmdm = null;
        LoadFieldNode ldudf = null;
        ArrayList<Node> nodesToBeDeleted = new ArrayList<>();

        for (Node n : graph.getNodes()) {
            if (n instanceof LoadFieldNode) {
                if (n.toString().contains("mdm")) {
                    ldmdm = (LoadFieldNode) n;
                }
                if (n.toString().contains("udf")) {
                    ldudf = (LoadFieldNode) n;
                }
            }
        }

        if (ldmdm != null && ldudf != null) {
            nodesToBeDeleted.add(ldmdm);
            nodesToBeDeleted.add(ldudf);

            // start with LoadField#mdm
            boolean isPredFixedGuardMdm = false;
            Node predmdm = ldmdm.predecessor();
            if (predmdm instanceof FixedGuardNode) {
                predmdm = getLdPred(ldmdm, nodesToBeDeleted);
                isPredFixedGuardMdm = true;
            }

            Node sucmdm = ldmdm.successors().first();
            if (sucmdm instanceof FixedGuardNode) {
                sucmdm = getLdSuc(ldmdm, nodesToBeDeleted);
            }

            Node sucmdmPrev = sucmdm.predecessor();
            sucmdmPrev.replaceFirstSuccessor(sucmdm, null);
            if (isPredFixedGuardMdm) {
                Node predSuc = predmdm.successors().first();
                predSuc.replaceAtPredecessor(sucmdm);
                predmdm.replaceFirstSuccessor(predSuc, sucmdm);
            } else {
                ldmdm.replaceAtPredecessor(sucmdm);
                predmdm.replaceFirstSuccessor(ldmdm, sucmdm);
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

            for (Node n : nodesToBeDeleted) {
                n.safeDelete();
            }

        }

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
}
