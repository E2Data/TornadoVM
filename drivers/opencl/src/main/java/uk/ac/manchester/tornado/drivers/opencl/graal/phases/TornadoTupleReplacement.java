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

    public static boolean hasTuples;
    public static int tupleSize;
    public static ArrayList<Class> tupleFieldKind;
    public static Class storeJavaKind;

    @Override
    protected void run(StructuredGraph graph, TornadoHighTierContext context) {
        if (hasTuples) {
            Constant tupleSizeConst = new RawConstant(tupleSize);
            ConstantNode indexInc = new ConstantNode(tupleSizeConst, StampFactory.positiveInt());
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
                                // Tuples have at least 2 fields
                                // create loadindexed for the first field (f0) of the tuple
                                LoadIndexedNode ldf0 = new LoadIndexedNode(null, idx.array(), indexOffset, JavaKind.fromJavaClass(tupleFieldKind.get(0)));
                                graph.addOrUnique(ldf0);
                                graph.replaceFixed(idx, ldf0);
                                loadindxNodes.add(ldf0);
                                for (int i = 1; i < tupleSize; i++) {
                                    // create nodes to read data for next field of the tuple from the next
                                    // position of the array
                                    Constant nextPosition = new RawConstant(i);
                                    ConstantNode nextIndxOffset = new ConstantNode(nextPosition, StampFactory.positiveInt());
                                    graph.addOrUnique(nextIndxOffset);
                                    AddNode nextTupleIndx = new AddNode(nextIndxOffset, indexOffset);
                                    graph.addOrUnique(nextTupleIndx);
                                    // create loadindexed for the next field of the tuple
                                    LoadIndexedNode ldfn = new LoadIndexedNode(null, ldf0.array(), nextTupleIndx, JavaKind.fromJavaClass(tupleFieldKind.get(i)));
                                    graph.addOrUnique(ldfn);
                                    graph.addAfterFixed(ldf0, ldfn);
                                    loadindxNodes.add(ldfn);
                                }
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
                    // JavaKind jvk = JavaKind.fromJavaClass(int.class);
                    StoreIndexedNode st = (StoreIndexedNode) n;
                    StoreIndexedNode newst = graph.addOrUnique(new StoreIndexedNode(st.array(), st.index(), JavaKind.fromJavaClass(storeJavaKind), st.value()));
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

    private static JavaKind getJavaKind(String jvkd) {
        if (jvkd.equals("Integer")) {
            return JavaKind.fromJavaClass(int.class);
        } else if (jvkd.equals("Double")) {
            return JavaKind.fromJavaClass(double.class);
        } else if (jvkd.equals("Float")) {
            return JavaKind.fromJavaClass(float.class);
        } else if (jvkd.equals("Long")) {
            return JavaKind.fromJavaClass(long.class);
        } else {
            return null;
        }
    }
}
