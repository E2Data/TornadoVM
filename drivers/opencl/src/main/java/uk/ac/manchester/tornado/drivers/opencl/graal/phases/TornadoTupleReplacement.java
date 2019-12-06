package uk.ac.manchester.tornado.drivers.opencl.graal.phases;

import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.RawConstant;

import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValuePhiNode;
import org.graalvm.compiler.nodes.calc.AddNode;
import org.graalvm.compiler.nodes.calc.MulNode;
import org.graalvm.compiler.nodes.extended.UnboxNode;
import org.graalvm.compiler.nodes.java.LoadFieldNode;
import org.graalvm.compiler.nodes.java.LoadIndexedNode;
import org.graalvm.compiler.nodes.java.StoreIndexedNode;
import org.graalvm.compiler.phases.BasePhase;
import org.graalvm.compiler.nodes.ConstantNode;

import uk.ac.manchester.tornado.runtime.graal.phases.TornadoHighTierContext;

import java.util.ArrayList;

public class TornadoTupleReplacement extends BasePhase<TornadoHighTierContext> {
    @Override
    protected void run(StructuredGraph graph, TornadoHighTierContext context) {

        boolean hasTuples = false;
        // TODO: check if code contains Tuples using the information stored in TypeInfo
        for (Node n : graph.getNodes()) {
            if (n instanceof LoadFieldNode) {
                LoadFieldNode ld = (LoadFieldNode) n;
                if (ld.field().toString().contains("org.apache.flink.api.java.tuple.Tuple")) {
                    hasTuples = true;
                }

            }
        }

        if (hasTuples) {
            // TODO: The constant node's value is fixed to 2 because we are currenly
            // checking Tuple2 types
            // TODO cont: make this more generic in the future
            Constant tupleSize = new RawConstant(2);
            ConstantNode indexInc = new ConstantNode(tupleSize, StampFactory.positiveInt());
            graph.addOrUnique(indexInc);
            ValuePhiNode phNode;
            MulNode indexOffset = null;
            for (Node n : graph.getNodes()) {
                if (n instanceof ValuePhiNode) {
                    phNode = (ValuePhiNode) n;
                    indexOffset = new MulNode(indexInc, phNode);
                    graph.addOrUnique(indexOffset);
                }
            }

            ArrayList<LoadIndexedNode> loadindxNodes = new ArrayList<>();

            // TODO: Create JavaKind types based on the info stored in TypeInfo
            for (Node n : graph.getNodes()) {
                if (n instanceof LoadFieldNode) {
                    LoadFieldNode ld = (LoadFieldNode) n;
                    if (ld.field().toString().contains("TornadoMap.mdm")) {
                        for (Node successor : ld.successors()) {
                            if (successor instanceof LoadIndexedNode) {
                                LoadIndexedNode idx = (LoadIndexedNode) successor;
                                // create loadindexed for the first field (f0) of the tuple
                                JavaKind jvk = JavaKind.fromJavaClass(int.class);
                                LoadIndexedNode ldf0 = new LoadIndexedNode(null, idx.array(), indexOffset, jvk);
                                graph.addOrUnique(ldf0);
                                graph.replaceFixed(idx, ldf0);
                                loadindxNodes.add(ldf0);
                                // create nodes to read data for second field of the tuple from the next
                                // position of the array
                                Constant nextPosition = new RawConstant(1);
                                ConstantNode secondIndxOffset = new ConstantNode(nextPosition, StampFactory.positiveInt());
                                graph.addOrUnique(secondIndxOffset);
                                AddNode secondIndx = new AddNode(secondIndxOffset, indexOffset);
                                graph.addOrUnique(secondIndx);
                                // create loadindexed for the second field (f1) of the tuple
                                LoadIndexedNode ldf1 = new LoadIndexedNode(null, ldf0.array(), secondIndx, jvk);
                                graph.addOrUnique(ldf1);
                                graph.addAfterFixed(ldf0, ldf1);
                                loadindxNodes.add(ldf1);
                                break;
                            }
                        }

                    }
                }

            }

            // set usages of unbox nodes to field nodes
            int i = 0;
            for (Node n : graph.getNodes()) {
                if (i == loadindxNodes.size())
                    break;
                if (n instanceof UnboxNode) {
                    n.replaceAtUsages(loadindxNodes.get(i));
                    i++;
                }
            }

            // replace storeindexednode with a new storeindexednode that has the appropriate
            // javakind
            for (Node n : graph.getNodes()) {
                if (n instanceof StoreIndexedNode) {
                    // Node pred = n.predecessor();
                    JavaKind jvk = JavaKind.fromJavaClass(int.class);
                    StoreIndexedNode st = (StoreIndexedNode) n;
                    StoreIndexedNode newst = graph.addOrUnique(new StoreIndexedNode(st.array(), st.index(), jvk, st.value()));
                    graph.replaceFixed(st, newst);
                    break;
                }
            }

            // store the nodes that need to be deleted (nodes associated with the tuple) in
            // an arraylist
            Node pred = null;
            ArrayList<Node> nodesToDelete = new ArrayList<>();
            boolean flag = false;
            for (Node n : graph.getNodes()) {
                if (n instanceof LoadFieldNode && !flag) {
                    LoadFieldNode ld = (LoadFieldNode) n;
                    if (ld.field().toString().contains("org.apache.flink.api.java.tuple.Tuple")) {
                        pred = ld.predecessor();
                        flag = true;
                    }
                }
                if (flag) {
                    if (n.successors().first() instanceof StoreIndexedNode) {
                        nodesToDelete.add(n);
                        break;
                    } else {
                        nodesToDelete.add(n);
                    }
                }
            }

            // delete nodes associated with Tuples
            for (Node n : nodesToDelete) {
                if (n.successors().first() instanceof StoreIndexedNode) {
                    UnboxNode un = (UnboxNode) n;
                    graph.replaceFixed(un, pred);
                } else {
                    n.safeDelete();
                }
            }
        }

    }
}
