package uk.ac.manchester.tornado.drivers.opencl.graal.phases;

import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.RawConstant;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.FixedGuardNode;
import org.graalvm.compiler.nodes.InvokeNode;
import org.graalvm.compiler.nodes.PiNode;
import org.graalvm.compiler.nodes.StartNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.calc.IntegerLessThanNode;
import org.graalvm.compiler.nodes.java.LoadFieldNode;
import org.graalvm.compiler.nodes.java.LoadIndexedNode;
import org.graalvm.compiler.nodes.java.MethodCallTargetNode;
import org.graalvm.compiler.nodes.java.StoreFieldNode;
import org.graalvm.compiler.phases.BasePhase;
import uk.ac.manchester.tornado.api.flink.FlinkCompilerInfo;
import uk.ac.manchester.tornado.runtime.FlinkCompilerInfoIntermediate;
import uk.ac.manchester.tornado.runtime.graal.phases.TornadoHighTierContext;

import java.util.ArrayList;

public class TornadoCollectionElimination extends BasePhase<TornadoHighTierContext> {

    private int sizeOfCollection = 2;
    private boolean broadcastedDataset;
    private String collectionName = "centroids";

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

                boolean iterateCollection = false;

                for (Node n : graph.getNodes()) {
                    if (n instanceof InvokeNode) {
                        if (n.toString().contains("Collection")) {
                            iterateCollection = true;
                            break;
                        }
                    }
                }

                if (iterateCollection) {
                    for (Node n : graph.getNodes()) {
                        if (n instanceof LoadFieldNode && n.toString().contains(collectionName)) {

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
                } else {
                    // --- HACK: In order to be able to access the data we have a dummy
                    // LoadIndex node right after the start node of the graph
                    // The location of this node is fixed because we have an array access
                    // on the Flink map skeleton right before the computation
                    // We need to extract this array in order to use it as input for the
                    // nodes that use the broadcasted dataset

                    LoadIndexedNode secondInput = null;
                    // find parameter p(2)
                    for (Node n : graph.getNodes()) {
                        if (n instanceof StartNode) {
                            if (n.successors().first() instanceof LoadIndexedNode) {
                                secondInput = (LoadIndexedNode) n.successors().first();
                                break;
                            }
                        }
                    }

                    if (secondInput == null)
                        return;

                    // find the usages of the broadcasted dataset

                    // PiNode piNode = null;
                    FixedGuardNode fx = null;
                    LoadFieldNode lfBroadcasted = null;
                    StoreFieldNode storeBroadcasted = null;
                    ConstantNode indxOffset = null;
                    ArrayList<Node> collectionFixedNodes = new ArrayList<>();

                    for (Node n : graph.getNodes()) {
                        if (n instanceof InvokeNode && n.toString().contains("get")) {
                            for (Node in : n.inputs()) {
                                if (in instanceof MethodCallTargetNode) {
                                    for (Node mtIn : in.inputs()) {
                                        if (mtIn instanceof ConstantNode) {
                                            indxOffset = (ConstantNode) mtIn.copyWithInputs();
                                        }
                                    }
                                }
                            }

                            if (n.successors().first() instanceof FixedGuardNode) {
                                FixedGuardNode fxG = (FixedGuardNode) n.successors().first();
                                for (Node us : fxG.usages()) {
                                    if (us instanceof PiNode) {
                                        // piNode = (PiNode) us;
                                        fx = fxG;
                                        break;
                                    }
                                }
                            }
                        } else if (n instanceof LoadFieldNode && n.toString().contains("parameters")) {
                            lfBroadcasted = (LoadFieldNode) n;
                        } else if (n instanceof StoreFieldNode && n.toString().contains("parameter")) {
                            storeBroadcasted = (StoreFieldNode) n;
                        }
                    }

                    ArrayList<Class> tupleFieldKindSecondDataSet = new ArrayList<>();
                    if (fcomp != null) {
                        tupleFieldKindSecondDataSet = fcomp.getTupleFieldKindSecondDataSet();
                    } else {
                        System.out.println("[ERROR]: COMPILER INFO NOT INITIALIZED");
                        return;
                    }
                    // Constant position = new RawConstant(0);
                    // ConstantNode indxOffset = new ConstantNode(position,
                    // StampFactory.positiveInt());
                    // graph.addWithoutUnique(indxOffset);

                    LoadIndexedNode ldInxdBroadcasted = new LoadIndexedNode(null, secondInput.array(), indxOffset, null, JavaKind.fromJavaClass(tupleFieldKindSecondDataSet.get(0)));
                    graph.addWithoutUnique(ldInxdBroadcasted);

                    // ldInxdBroadcasted.replaceAtUsages(fx);
                    PiNode p = (PiNode) fx.usages().first();
                    p.replaceFirstInput(fx, ldInxdBroadcasted);
                    // if (piNode != null) {
                    // for (Node piUs : piNode.usages()) {
                    // System.out.println("Usage: " + piUs);
                    // for (Node piUsInput : piUs.inputs()) {
                    // if (piUsInput == piNode) {
                    // System.out.println("- Replace input pi with loadIndex");
                    // piUsInput.replaceFirstInput(piNode, ldInxdBroadcasted);
                    // }
                    // }
                    // }
                    // }
                    // ldInxdBroadcasted.replaceAtUsages(piNode);
                    //
                    // for (Node ldinxUs : ldInxdBroadcasted.usages()) {
                    // System.out.println("+ LoadIndexed Usage: " + ldinxUs);
                    // }

                    // remove fixed nodes related to the broadcasted collection
                    // identify the fixed nodes related to the collection
                    Node n = lfBroadcasted;
                    while (true) {
                        collectionFixedNodes.add(n);
                        if (n instanceof StoreFieldNode && n.toString().contains("parameter")) {
                            break;
                        }
                        n = n.successors().first();
                    }

                    Node suc = storeBroadcasted.successors().first();
                    Node pred = lfBroadcasted.predecessor();
                    storeBroadcasted.replaceFirstSuccessor(suc, null);
                    lfBroadcasted.replaceAtPredecessor(ldInxdBroadcasted);
                    pred.replaceFirstSuccessor(lfBroadcasted, ldInxdBroadcasted);
                    ldInxdBroadcasted.replaceFirstSuccessor(null, suc);

                    for (Node fixedColl : collectionFixedNodes) {
                        fixedColl.safeDelete();
                    }

                    // remove dummy loadIndex node
                    Node predD = secondInput.predecessor();
                    Node lf = secondInput.successors().first();
                    Node fixG = lf.successors().first();
                    Node sucD = fixG.successors().first();

                    fixG.replaceFirstSuccessor(sucD, null);
                    secondInput.replaceAtPredecessor(sucD);
                    predD.replaceFirstSuccessor(secondInput, sucD);

                    lf.safeDelete();
                    secondInput.safeDelete();
                    fixG.safeDelete();
                }
            }

        }
    }
}
