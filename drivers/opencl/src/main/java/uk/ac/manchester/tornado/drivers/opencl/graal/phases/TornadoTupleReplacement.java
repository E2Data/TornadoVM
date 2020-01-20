package uk.ac.manchester.tornado.drivers.opencl.graal.phases;

import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.RawConstant;

import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.ValuePhiNode;
import org.graalvm.compiler.nodes.calc.AddNode;
import org.graalvm.compiler.nodes.calc.MulNode;
import org.graalvm.compiler.nodes.extended.BoxNode;
import org.graalvm.compiler.nodes.extended.UnboxNode;
import org.graalvm.compiler.nodes.java.LoadFieldNode;
import org.graalvm.compiler.nodes.java.LoadIndexedNode;
import org.graalvm.compiler.nodes.java.StoreIndexedNode;
import org.graalvm.compiler.nodes.virtual.CommitAllocationNode;
import org.graalvm.compiler.nodes.virtual.VirtualInstanceNode;
import org.graalvm.compiler.phases.BasePhase;
import org.graalvm.compiler.nodes.ConstantNode;

import uk.ac.manchester.tornado.runtime.graal.phases.TornadoHighTierContext;

import javax.swing.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;

public class TornadoTupleReplacement extends BasePhase<TornadoHighTierContext> {

    public static boolean hasTuples;
    public static int tupleSize;
    public static ArrayList<Class> tupleFieldKind;
    public static Class storeJavaKind;
    public static boolean returnTuple;

    @Override
    protected void run(StructuredGraph graph, TornadoHighTierContext context) {
        if (hasTuples) {
            ValuePhiNode ldIndx = null;
            MulNode indexOffset = null;
            if (!TornadoTupleOffset.differentTypes) {
                Constant tupleSizeConst = new RawConstant(tupleSize);
                ConstantNode indexInc = new ConstantNode(tupleSizeConst, StampFactory.positiveInt());
                graph.addOrUnique(indexInc);
                ValuePhiNode phNode = null;
                for (Node n : graph.getNodes()) {
                    if (n instanceof ValuePhiNode) {
                        phNode = (ValuePhiNode) n;
                        indexOffset = new MulNode(indexInc, phNode);
                        graph.addOrUnique(indexOffset);
                    }
                }
            } else {
                for (Node n : graph.getNodes()) {
                    if (n instanceof ValuePhiNode) {
                        ldIndx = (ValuePhiNode) n;
                    }
                }
            }
            ArrayList<ValueNode> storeTupleInputs = new ArrayList<>();
            LinkedHashMap<ValueNode, StoreIndexedNode> storesWithInputs = new LinkedHashMap<>();
            boolean alloc = false;
            if (returnTuple) {
                for (Node n : graph.getNodes()) {
                    if (n instanceof CommitAllocationNode) {
                        alloc = true;
                        for (Node in : n.inputs()) {
                            if (!(in instanceof VirtualInstanceNode)) {
                                storeTupleInputs.add((ValueNode) in);
                            }
                        }
                    }
                }

                if (alloc) {
                    for (Node n : graph.getNodes()) {
                        if (n instanceof StoreIndexedNode) {
                            StoreIndexedNode st = (StoreIndexedNode) n;
                            StoreIndexedNode newst = graph.addOrUnique(new StoreIndexedNode(st.array(), st.index(), JavaKind.fromJavaClass(int.class), storeTupleInputs.get(0)));
                            storesWithInputs.put(storeTupleInputs.get(0), newst);
                            StoreIndexedNode newst2 = graph.addOrUnique(new StoreIndexedNode(st.array(), st.index(), JavaKind.fromJavaClass(int.class), storeTupleInputs.get(1)));
                            storesWithInputs.put(storeTupleInputs.get(1), newst2);
                            graph.replaceFixed(st, newst);
                            graph.addAfterFixed(newst, newst2);
                            break;
                        }
                    }

                    // delete nodes related to Tuple allocation

                    for (Node n : graph.getNodes()) {
                        if (n instanceof CommitAllocationNode) {
                            Node predAlloc = n.predecessor();
                            for (Node sucAlloc : n.successors()) {
                                predAlloc.replaceFirstSuccessor(n, sucAlloc);
                            }
                            n.safeDelete();
                        }
                    }

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
                                LoadIndexedNode ldf0;
                                if (TornadoTupleOffset.differentTypes) {
                                    ldf0 = new LoadIndexedNode(null, idx.array(), ldIndx, JavaKind.fromJavaClass(tupleFieldKind.get(0)));
                                } else {
                                    ldf0 = new LoadIndexedNode(null, idx.array(), indexOffset, JavaKind.fromJavaClass(tupleFieldKind.get(0)));
                                }
                                graph.addOrUnique(ldf0);
                                graph.replaceFixed(idx, ldf0);
                                loadindxNodes.add(ldf0);
                                for (int i = 1; i < tupleSize; i++) {
                                    // create nodes to read data for next field of the tuple from the next
                                    // position of the array
                                    LoadIndexedNode ldfn;
                                    if (TornadoTupleOffset.differentTypes) {
                                        ldfn = new LoadIndexedNode(null, ldf0.array(), ldIndx, JavaKind.fromJavaClass(tupleFieldKind.get(i)));
                                    } else {
                                        Constant nextPosition = new RawConstant(i);
                                        ConstantNode nextIndxOffset = new ConstantNode(nextPosition, StampFactory.positiveInt());
                                        graph.addOrUnique(nextIndxOffset);
                                        AddNode nextTupleIndx = new AddNode(nextIndxOffset, indexOffset);
                                        graph.addOrUnique(nextTupleIndx);
                                        // create loadindexed for the next field of the tuple
                                        ldfn = new LoadIndexedNode(null, ldf0.array(), nextTupleIndx, JavaKind.fromJavaClass(tupleFieldKind.get(i)));
                                    }
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

            if (returnTuple) {
                HashMap<LoadFieldNode, LoadIndexedNode> fieldToIndex = new HashMap<>();

                int j = 0;
                for (Node n : graph.getNodes()) {
                    if (n instanceof LoadFieldNode) {
                        LoadFieldNode ld = (LoadFieldNode) n;
                        if (ld.field().toString().contains("org.apache.flink.api.java.tuple.Tuple")) {
                            fieldToIndex.put(ld, loadindxNodes.get(j));
                            j++;
                        }
                    }
                }

                for (Node n : storesWithInputs.keySet()) {
                    if (fieldToIndex.containsKey(n)) {
                        StoreIndexedNode st = storesWithInputs.get(n);
                        st.replaceFirstInput(n, fieldToIndex.get(n));
                    }
                }

                int k = 0;
                for (StoreIndexedNode st : storesWithInputs.values()) {
                    st.replaceFirstInput(st.index(), loadindxNodes.get(k).index());
                    k++;
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

            if (!returnTuple) {
                // replace storeindexednode with a new storeindexednode that has the appropriate
                // javakind
                for (Node n : graph.getNodes()) {
                    if (n instanceof StoreIndexedNode) {
                        StoreIndexedNode st = (StoreIndexedNode) n;
                        StoreIndexedNode newst = graph.addOrUnique(new StoreIndexedNode(st.array(), st.index(), JavaKind.fromJavaClass(storeJavaKind), st.value()));
                        graph.replaceFixed(st, newst);
                        break;
                    }
                }
            }

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
                        boolean inputLdIndx = false;
                        for (Node inn : n.inputs()) {
                            if (inn instanceof LoadIndexedNode && !(n instanceof LoadFieldNode)) {
                                inputLdIndx = true;
                            }
                        }
                        if (!inputLdIndx) {
                            nodesToDelete.add(n);
                        }
                    }
                }

            }

            // delete nodes associated with Tuples
            for (Node n : nodesToDelete) {
                if (n.successors().first() instanceof StoreIndexedNode) {
                    if (returnTuple) {
                        pred.replaceFirstSuccessor(pred.successors().first(), n);
                    } else {
                        UnboxNode un = (UnboxNode) n;
                        graph.replaceFixed(un, pred);
                    }
                } else if (n instanceof ConstantNode) {
                    System.out.println("Do not delete constant!!!!");
                } else if (n instanceof BoxNode) {
                    for (Node u : n.usages()) {
                        u.replaceFirstInput(n, n.inputs().first());
                    }
                    n.safeDelete();
                } else {
                    n.safeDelete();
                }
            }

            if (returnTuple) {
                for (Node n : graph.getNodes()) {
                    if (n instanceof BoxNode) {
                        for (Node u : n.usages()) {
                            u.replaceFirstInput(n, n.inputs().first());
                        }
                        Node boxPred = n.predecessor();
                        boxPred.replaceFirstSuccessor(n, n.successors().first());
                        n.safeDelete();
                    }
                }
            }
        }
    }

}
