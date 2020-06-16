package uk.ac.manchester.tornado.drivers.opencl.graal.phases;

import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.RawConstant;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.FixedGuardNode;
import org.graalvm.compiler.nodes.InvokeNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.calc.IntegerLessThanNode;
import org.graalvm.compiler.nodes.java.LoadFieldNode;
import org.graalvm.compiler.phases.BasePhase;
import uk.ac.manchester.tornado.api.flink.FlinkCompilerInfo;
import uk.ac.manchester.tornado.runtime.FlinkCompilerInfoIntermediate;
import uk.ac.manchester.tornado.runtime.graal.phases.TornadoHighTierContext;

import java.util.ArrayList;

public class TornadoCollectionElimination extends BasePhase<TornadoHighTierContext> {

    private int sizeOfCollection = 2;
    private boolean broadcastedDataset;

    void flinkSetCompInfo(FlinkCompilerInfo flinkCompilerInfo) {
        this.broadcastedDataset = flinkCompilerInfo.getBroadcastedDataset();
    }

    @Override
    protected void run(StructuredGraph graph, TornadoHighTierContext context) {
        FlinkCompilerInfo fcomp = FlinkCompilerInfoIntermediate.getFlinkCompilerInfo();
        if (fcomp != null) {
            flinkSetCompInfo(fcomp);
        }

        if (broadcastedDataset) {

            boolean forEach = false;

            for (Node n : graph.getNodes()) {
                if (n instanceof InvokeNode) {
                    InvokeNode in = (InvokeNode) n;
                    if (in.toString().contains("hasNext")) {
                        forEach = true;
                    }
                }
            }

            if (forEach) {

                System.out.println("[ERROR] We currently do not support for each iterators. Please replace with with a regular for loop.");
                return;

            } else {

                for (Node n : graph.getNodes()) {
                    if (n instanceof LoadFieldNode && n.toString().contains("centroid")) {

                        ArrayList<Node> nodesToBeRemoved = new ArrayList<>();
                        nodesToBeRemoved.add(n);

                        Node pred = n.predecessor();
                        Node currSuc = n.successors().first();

                        Node newSuc = currSuc;

                        while (newSuc instanceof InvokeNode || newSuc instanceof FixedGuardNode) {
                            nodesToBeRemoved.add(newSuc);
                            newSuc = newSuc.successors().first();
                        }

                        for (Node nodes : graph.getNodes()) {
                            if (nodes instanceof IntegerLessThanNode) {
                                for (Node in : nodes.inputs()) {
                                    if (in instanceof InvokeNode) {
                                        Constant loopLimitConst = new RawConstant(sizeOfCollection);
                                        ConstantNode loopLimit = new ConstantNode(loopLimitConst, StampFactory.positiveInt());
                                        graph.addWithoutUnique(loopLimit);
                                        nodes.replaceFirstInput(in, loopLimit);
                                    }
                                }
                            }
                        }

                        Node newSucPrev = newSuc.predecessor();

                        // break link
                        newSucPrev.replaceFirstSuccessor(newSuc, null);

                        n.replaceAtPredecessor(newSuc);

                        pred.replaceFirstSuccessor(n, newSuc);

                        for (int i = 0; i < nodesToBeRemoved.size(); i++) {
                            Node del = nodesToBeRemoved.get(i);
                            if (del instanceof LoadFieldNode) {
                                del.safeDelete();
                            } else {
                                for (Node in : del.inputs()) {
                                    in.safeDelete();
                                }
                                del.safeDelete();
                            }
                        }

                    }
                }

            }

        }
    }
}
