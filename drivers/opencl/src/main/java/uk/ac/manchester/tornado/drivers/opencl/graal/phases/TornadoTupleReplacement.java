package uk.ac.manchester.tornado.drivers.opencl.graal.phases;

import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.RawConstant;

import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.FixedGuardNode;
import org.graalvm.compiler.nodes.FixedNode;
import org.graalvm.compiler.nodes.FrameState;
import org.graalvm.compiler.nodes.ParameterNode;
import org.graalvm.compiler.nodes.PhiNode;
import org.graalvm.compiler.nodes.PiNode;
import org.graalvm.compiler.nodes.StartNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.ValuePhiNode;
import org.graalvm.compiler.nodes.calc.AddNode;
import org.graalvm.compiler.nodes.calc.FloatConvertNode;
import org.graalvm.compiler.nodes.calc.IsNullNode;
import org.graalvm.compiler.nodes.calc.MulNode;
import org.graalvm.compiler.nodes.extended.BoxNode;
import org.graalvm.compiler.nodes.extended.UnboxNode;
import org.graalvm.compiler.nodes.java.InstanceOfNode;
import org.graalvm.compiler.nodes.java.LoadFieldNode;
import org.graalvm.compiler.nodes.java.LoadIndexedNode;
import org.graalvm.compiler.nodes.java.StoreIndexedNode;
import org.graalvm.compiler.nodes.virtual.CommitAllocationNode;
import org.graalvm.compiler.nodes.virtual.VirtualInstanceNode;
import org.graalvm.compiler.phases.BasePhase;
import org.graalvm.compiler.nodes.ConstantNode;

import uk.ac.manchester.tornado.api.flink.FlinkCompilerInfo;
import uk.ac.manchester.tornado.drivers.opencl.graal.nodes.GlobalThreadIdNode;
import uk.ac.manchester.tornado.runtime.FlinkCompilerInfoIntermediate;
import uk.ac.manchester.tornado.runtime.graal.phases.TornadoHighTierContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;

public class TornadoTupleReplacement extends BasePhase<TornadoHighTierContext> {

    private boolean hasTuples;
    private int tupleSize;
    private int tupleSizeSecondDataSet;
    private ArrayList<Class> tupleFieldKind;
    private ArrayList<Class> tupleFieldKindSecondDataSet;
    private Class storeJavaKind;
    private int returnTupleSize;
    private boolean returnTuple;
    private ArrayList<Class> returnFieldKind;
    private boolean nestedTuples;
    private int nestedTupleField;
    private int sizeOfNestedTuple;
    private boolean broadcastedDataset;
    private boolean arrayField;
    private int tupleArrayFieldNo;
    private boolean returnArrayField;
    private int returnTupleArrayFieldNo;
    // set this by investigating the graph
    // private boolean copyArrayField = true;

    void flinkSetCompInfo(FlinkCompilerInfo flinkCompilerInfo) {
        this.hasTuples = flinkCompilerInfo.getHasTuples();
        this.tupleSize = flinkCompilerInfo.getTupleSize();
        this.tupleSizeSecondDataSet = flinkCompilerInfo.getTupleSizeSecondDataSet();
        this.tupleFieldKind = flinkCompilerInfo.getTupleFieldKind();
        this.tupleFieldKindSecondDataSet = flinkCompilerInfo.getTupleFieldKindSecondDataSet();
        this.storeJavaKind = flinkCompilerInfo.getStoreJavaKind();
        this.returnTupleSize = flinkCompilerInfo.getReturnTupleSize();
        this.returnTuple = flinkCompilerInfo.getReturnTuple();
        this.returnFieldKind = flinkCompilerInfo.getReturnFieldKind();
        this.nestedTuples = flinkCompilerInfo.getNestedTuples();
        this.nestedTupleField = flinkCompilerInfo.getNestedTupleField();
        this.sizeOfNestedTuple = flinkCompilerInfo.getSizeOfNestedTuple();
        this.broadcastedDataset = flinkCompilerInfo.getBroadcastedDataset();
        this.arrayField = flinkCompilerInfo.getArrayField();
        this.tupleArrayFieldNo = flinkCompilerInfo.getTupleArrayFieldNo();
        this.returnArrayField = flinkCompilerInfo.getReturnArrayField();
        this.returnTupleArrayFieldNo = flinkCompilerInfo.getReturnTupleArrayFieldNo();
    }

    @Override
    protected void run(StructuredGraph graph, TornadoHighTierContext context) {
        FlinkCompilerInfo fcomp = FlinkCompilerInfoIntermediate.getFlinkCompilerInfo();
        if (fcomp != null) {
            flinkSetCompInfo(fcomp);
        }

        if (hasTuples) {
            if (!broadcastedDataset) {
                // at the moment we only handle cases where the input tuples are nested, for one
                // for loop
                // TODO: implement this for two loops also and refactor the code
                if (nestedTuples) {
                    // System.out.println("One For loop - nested tuples");
                    ValuePhiNode ldIndx = null;
                    MulNode indexOffset = null;
                    // if (!TornadoTupleOffset.differentTypes) {
                    Constant tupleSizeConst = new RawConstant(tupleSize);
                    ConstantNode indexInc = new ConstantNode(tupleSizeConst, StampFactory.positiveInt());
                    graph.addWithoutUnique(indexInc);
                    ValuePhiNode phNode = null;
                    for (Node n : graph.getNodes()) {
                        if (n instanceof ValuePhiNode) {
                            phNode = (ValuePhiNode) n;
                            indexOffset = new MulNode(indexInc, phNode);
                            graph.addWithoutUnique(indexOffset);
                        }
                    }

                    ArrayList<LoadIndexedNode> loadindxNodes = new ArrayList<>();
                    ArrayList<LoadFieldNode> loadFieldNodesUnordered = new ArrayList<>();
                    // ArrayList<LoadFieldNode> loadfieldNodes = new ArrayList<>();

                    for (Node n : graph.getNodes()) {
                        if (n instanceof LoadFieldNode && ((LoadFieldNode) n).field().toString().contains("org.apache.flink.api.java.tuple.Tuple")) {
                            LoadFieldNode ld = (LoadFieldNode) n;
                            // String field = ld.field().toString();
                            // int fieldNumber =
                            // Integer.parseInt(Character.toString(field.substring(field.lastIndexOf(".") +
                            // 1).charAt(1)));
                            loadFieldNodesUnordered.add(ld);
                        }
                    }

                    if (loadFieldNodesUnordered.size() > tupleSize) {
                        // unfolded
                        // TODO: Create JavaKind types based on the info stored in TypeInfo
                        for (Node n : graph.getNodes()) {
                            if (n instanceof LoadFieldNode) {
                                LoadFieldNode ld = (LoadFieldNode) n;
                                if (ld.field().toString().contains("mdm")) {
                                    for (Node successor : ld.successors()) {
                                        if (successor instanceof LoadIndexedNode) {
                                            LoadIndexedNode idx = (LoadIndexedNode) successor;
                                            // Tuples have at least 2 fields
                                            // create loadindexed for the first field (f0) of the tuple
                                            LoadIndexedNode ldf0;
                                            ldf0 = new LoadIndexedNode(null, idx.array(), indexOffset, null, JavaKind.fromJavaClass(tupleFieldKind.get(0)));
                                            graph.addWithoutUnique(ldf0);
                                            graph.replaceFixed(idx, ldf0);
                                            loadindxNodes.add(ldf0);
                                            for (int i = 1; i < tupleSize; i++) {
                                                // create nodes to read data for next field of the tuple from the next
                                                // position of the array
                                                LoadIndexedNode ldfn;

                                                Constant nextPosition = new RawConstant(i);
                                                ConstantNode nextIndxOffset = new ConstantNode(nextPosition, StampFactory.positiveInt());
                                                graph.addWithoutUnique(nextIndxOffset);
                                                AddNode nextTupleIndx = new AddNode(nextIndxOffset, indexOffset);
                                                graph.addWithoutUnique(nextTupleIndx);
                                                // create loadindexed for the next field of the tuple
                                                ldfn = new LoadIndexedNode(null, ldf0.array(), nextTupleIndx, null, JavaKind.fromJavaClass(tupleFieldKind.get(i)));
                                                // }
                                                graph.addWithoutUnique(ldfn);
                                                // graph.addAfterFixed(loadindxNodes.get((loadindxNodes.size() - 1)), ldfn);
                                                loadindxNodes.add(ldfn);
                                            }
                                            break;
                                        }
                                    }

                                }
                            }

                        }

                        graph.addAfterFixed(loadindxNodes.get(0), loadindxNodes.get(1));
                        graph.addAfterFixed(loadindxNodes.get(1), loadindxNodes.get(3));
                        graph.addAfterFixed(loadindxNodes.get(3), loadindxNodes.get(2));

                        ArrayList<LoadIndexedNode> loadIndexedNodesOrdered = new ArrayList<>();

                        for (Node n : graph.getNodes()) {
                            if (n instanceof LoadIndexedNode) {
                                loadIndexedNodesOrdered.add((LoadIndexedNode) n);
                                Node suc = n.successors().first();
                                while (suc instanceof LoadIndexedNode) {
                                    loadIndexedNodesOrdered.add((LoadIndexedNode) suc);
                                    suc = suc.successors().first();
                                }
                                break;
                            }
                        }

                        int currentField = 0;
                        int j = 0;
                        for (int i = 0; i < loadFieldNodesUnordered.size(); i++) {
                            if (currentField == nestedTupleField) {
                                currentField++;
                            } else {
                                LoadFieldNode f = loadFieldNodesUnordered.get(i);
                                LoadIndexedNode indx = loadIndexedNodesOrdered.get(j);
                                if (f.successors().first() instanceof FixedGuardNode) {
                                    UnboxNode unb = getUnbox(f);
                                    unb.replaceAtUsages(indx);
                                } else {
                                    f.replaceAtUsages(indx);
                                }
                                j++;
                                currentField++;
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

                            MulNode returnIndexOffset = null;
                            Constant returnTupleSizeConst = null;

                            returnTupleSizeConst = new RawConstant(returnTupleSize);

                            ConstantNode retIndexInc = new ConstantNode(returnTupleSizeConst, StampFactory.positiveInt());
                            graph.addWithoutUnique(retIndexInc);
                            ValuePhiNode retPhNode = null;
                            for (Node n : graph.getNodes()) {
                                if (n instanceof ValuePhiNode) {
                                    retPhNode = (ValuePhiNode) n;
                                    returnIndexOffset = new MulNode(retIndexInc, retPhNode);
                                    graph.addWithoutUnique(returnIndexOffset);
                                }
                            }

                            if (alloc) {
                                for (Node n : graph.getNodes()) {
                                    if (n instanceof StoreIndexedNode) {
                                        if (returnTupleSize == 2) {
                                            StoreIndexedNode st = (StoreIndexedNode) n;
                                            StoreIndexedNode newst = graph.addOrUnique(
                                                    new StoreIndexedNode(st.array(), returnIndexOffset, null, null, JavaKind.fromJavaClass(returnFieldKind.get(0)), storeTupleInputs.get(0)));
                                            storesWithInputs.put(storeTupleInputs.get(0), newst);
                                            Constant retNextPosition = new RawConstant(1);
                                            ConstantNode retNextIndxOffset = new ConstantNode(retNextPosition, StampFactory.positiveInt());
                                            graph.addWithoutUnique(retNextIndxOffset);
                                            AddNode nextRetTupleIndx = new AddNode(retNextIndxOffset, returnIndexOffset);
                                            graph.addWithoutUnique(nextRetTupleIndx);
                                            StoreIndexedNode newst2 = graph.addOrUnique(
                                                    new StoreIndexedNode(st.array(), nextRetTupleIndx, null, null, JavaKind.fromJavaClass(returnFieldKind.get(1)), storeTupleInputs.get(1)));
                                            storesWithInputs.put(storeTupleInputs.get(1), newst2);
                                            graph.replaceFixed(st, newst);
                                            graph.addAfterFixed(newst, newst2);
                                            break;
                                        } else if (returnTupleSize == 3) {
                                            StoreIndexedNode st = (StoreIndexedNode) n;
                                            StoreIndexedNode newst = graph.addOrUnique(
                                                    new StoreIndexedNode(st.array(), returnIndexOffset, null, null, JavaKind.fromJavaClass(returnFieldKind.get(0)), storeTupleInputs.get(0)));
                                            storesWithInputs.put(storeTupleInputs.get(0), newst);

                                            Constant retNextPosition = new RawConstant(1);
                                            ConstantNode retNextIndxOffset = new ConstantNode(retNextPosition, StampFactory.positiveInt());
                                            graph.addWithoutUnique(retNextIndxOffset);
                                            AddNode nextRetTupleIndx = new AddNode(retNextIndxOffset, returnIndexOffset);
                                            graph.addWithoutUnique(nextRetTupleIndx);
                                            StoreIndexedNode newst2 = graph.addOrUnique(
                                                    new StoreIndexedNode(st.array(), nextRetTupleIndx, null, null, JavaKind.fromJavaClass(returnFieldKind.get(1)), storeTupleInputs.get(1)));
                                            storesWithInputs.put(storeTupleInputs.get(1), newst2);
                                            graph.replaceFixed(st, newst);
                                            graph.addAfterFixed(newst, newst2);

                                            Constant retNextPositionF3 = new RawConstant(2);
                                            ConstantNode retNextIndxOffsetF3 = new ConstantNode(retNextPositionF3, StampFactory.positiveInt());
                                            graph.addWithoutUnique(retNextIndxOffsetF3);
                                            AddNode nextRetTupleIndxF3 = new AddNode(retNextIndxOffsetF3, returnIndexOffset);
                                            graph.addWithoutUnique(nextRetTupleIndxF3);
                                            StoreIndexedNode newst3;
                                            // if (TornadoTupleOffset.differentTypesRet) {
                                            newst3 = graph.addOrUnique(
                                                    new StoreIndexedNode(newst2.array(), nextRetTupleIndxF3, null, null, JavaKind.fromJavaClass(returnFieldKind.get(2)), storeTupleInputs.get(2)));
                                            // } else {
                                            // newst3 = graph.addOrUnique(new StoreIndexedNode(newst2.array(),
                                            // nextRetTupleIndxF3, JavaKind.fromJavaClass(int.class),
                                            // storeTupleInputs.get(2)));
                                            // }
                                            storesWithInputs.put(storeTupleInputs.get(2), newst3);
                                            // graph.replaceFixed(st, newst);
                                            graph.addAfterFixed(newst2, newst3);
                                            break;
                                        } else if (returnTupleSize == 4) {
                                            StoreIndexedNode st = (StoreIndexedNode) n;
                                            StoreIndexedNode newst = graph.addOrUnique(
                                                    new StoreIndexedNode(st.array(), returnIndexOffset, null, null, JavaKind.fromJavaClass(returnFieldKind.get(0)), storeTupleInputs.get(0)));
                                            storesWithInputs.put(storeTupleInputs.get(0), newst);
                                            Constant retNextPosition = new RawConstant(1);
                                            ConstantNode retNextIndxOffset = new ConstantNode(retNextPosition, StampFactory.positiveInt());
                                            graph.addWithoutUnique(retNextIndxOffset);
                                            AddNode nextRetTupleIndx = new AddNode(retNextIndxOffset, returnIndexOffset);
                                            graph.addWithoutUnique(nextRetTupleIndx);
                                            StoreIndexedNode newst2 = graph.addOrUnique(
                                                    new StoreIndexedNode(st.array(), nextRetTupleIndx, null, null, JavaKind.fromJavaClass(returnFieldKind.get(1)), storeTupleInputs.get(1)));
                                            storesWithInputs.put(storeTupleInputs.get(1), newst2);
                                            graph.replaceFixed(st, newst);
                                            graph.addAfterFixed(newst, newst2);
                                            Constant retNextPositionF3 = new RawConstant(2);
                                            ConstantNode retNextIndxOffsetF3 = new ConstantNode(retNextPositionF3, StampFactory.positiveInt());
                                            graph.addWithoutUnique(retNextIndxOffsetF3);
                                            AddNode nextRetTupleIndxF3 = new AddNode(retNextIndxOffsetF3, returnIndexOffset);
                                            graph.addWithoutUnique(nextRetTupleIndxF3);
                                            StoreIndexedNode newst3;
                                            newst3 = graph.addOrUnique(
                                                    new StoreIndexedNode(newst2.array(), nextRetTupleIndxF3, null, null, JavaKind.fromJavaClass(returnFieldKind.get(2)), storeTupleInputs.get(2)));
                                            storesWithInputs.put(storeTupleInputs.get(2), newst3);
                                            graph.addAfterFixed(newst2, newst3);
                                            Constant retNextPositionF4 = new RawConstant(3);
                                            ConstantNode retNextIndxOffsetF4 = new ConstantNode(retNextPositionF4, StampFactory.positiveInt());
                                            graph.addWithoutUnique(retNextIndxOffsetF4);
                                            AddNode nextRetTupleIndxF4 = new AddNode(retNextIndxOffsetF4, returnIndexOffset);
                                            graph.addWithoutUnique(nextRetTupleIndxF4);
                                            StoreIndexedNode newst4;
                                            newst4 = graph.addOrUnique(
                                                    new StoreIndexedNode(newst3.array(), nextRetTupleIndxF4, null, null, JavaKind.fromJavaClass(returnFieldKind.get(3)), storeTupleInputs.get(3)));
                                            storesWithInputs.put(storeTupleInputs.get(3), newst4);
                                            graph.addAfterFixed(newst3, newst4);
                                            break;
                                        }
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
                            } else if (n instanceof BoxNode) {
                                for (Node u : n.usages()) {
                                    u.replaceFirstInput(n, n.inputs().first());
                                }
                                Node npred = n.predecessor();
                                Node nsuc = n.successors().first();
                                n.replaceFirstSuccessor(nsuc, null);
                                n.replaceAtPredecessor(nsuc);
                                npred.replaceFirstSuccessor(n, nsuc);
                                n.safeDelete();
                            } else {
                                if (!(n instanceof ConstantNode)) {
                                    if (n instanceof FixedNode) {
                                        Node npred = n.predecessor();
                                        Node nsuc = n.successors().first();
                                        n.replaceFirstSuccessor(nsuc, null);
                                        n.replaceAtPredecessor(nsuc);
                                        npred.replaceFirstSuccessor(n, nsuc);
                                        n.safeDelete();
                                    }
                                }
                            }
                        }

                        if (returnTuple) {
                            for (Node n : graph.getNodes()) {
                                if (n instanceof BoxNode) {
                                    for (Node u : n.usages()) {
                                        u.replaceFirstInput(n, n.inputs().first());
                                    }
                                    Node npred = n.predecessor();
                                    Node nsuc = n.successors().first();
                                    n.replaceFirstSuccessor(nsuc, null);
                                    n.replaceAtPredecessor(nsuc);
                                    npred.replaceFirstSuccessor(n, nsuc);
                                    n.safeDelete();
                                }
                            }
                        }

                    } else {

                        ArrayList<LoadFieldNode> loadfieldNodes = new ArrayList<>();

                        for (Node n : graph.getNodes()) {
                            if (n instanceof LoadFieldNode && ((LoadFieldNode) n).field().toString().contains("org.apache.flink.api.java.tuple.Tuple")) {
                                LoadFieldNode ld = (LoadFieldNode) n;
                                String field = ld.field().toString();
                                int fieldNumber = Integer.parseInt(Character.toString(field.substring(field.lastIndexOf(".") + 1).charAt(1)));
                                loadfieldNodes.add(fieldNumber, ld);
                            }
                        }

                        // TODO: Create JavaKind types based on the info stored in TypeInfo
                        for (Node n : graph.getNodes()) {
                            if (n instanceof LoadFieldNode) {
                                LoadFieldNode ld = (LoadFieldNode) n;
                                if (ld.field().toString().contains("mdm")) {
                                    for (Node successor : ld.successors()) {
                                        if (successor instanceof LoadIndexedNode) {
                                            LoadIndexedNode idx = (LoadIndexedNode) successor;
                                            // Tuples have at least 2 fields
                                            // create loadindexed for the first field (f0) of the tuple
                                            LoadIndexedNode ldf0;
                                            ldf0 = new LoadIndexedNode(null, idx.array(), indexOffset, null, JavaKind.fromJavaClass(tupleFieldKind.get(0)));
                                            graph.addWithoutUnique(ldf0);
                                            graph.replaceFixed(idx, ldf0);
                                            loadindxNodes.add(ldf0);
                                            for (int i = 1; i < tupleSize; i++) {
                                                // create nodes to read data for next field of the tuple from the next
                                                // position of the array
                                                LoadIndexedNode ldfn;

                                                Constant nextPosition = new RawConstant(i);
                                                ConstantNode nextIndxOffset = new ConstantNode(nextPosition, StampFactory.positiveInt());
                                                graph.addWithoutUnique(nextIndxOffset);
                                                AddNode nextTupleIndx = new AddNode(nextIndxOffset, indexOffset);
                                                graph.addWithoutUnique(nextTupleIndx);
                                                // create loadindexed for the next field of the tuple
                                                ldfn = new LoadIndexedNode(null, ldf0.array(), nextTupleIndx, null, JavaKind.fromJavaClass(tupleFieldKind.get(i)));
                                                // }
                                                graph.addWithoutUnique(ldfn);
                                                graph.addAfterFixed(loadindxNodes.get((loadindxNodes.size() - 1)), ldfn);
                                                loadindxNodes.add(ldfn);
                                            }
                                            break;
                                        }
                                    }

                                }
                            }

                        }

                        HashMap<LoadFieldNode, ArrayList<LoadIndexedNode>> loadFieldNodeArrayListHashMap = new HashMap<>();
                        int currentField = 0;
                        int counter = 0;
                        while (counter < loadindxNodes.size()) {
                            ArrayList<LoadIndexedNode> ld = new ArrayList<>();
                            LoadFieldNode ldf = loadfieldNodes.get(currentField);
                            if (nestedTupleField == currentField) {
                                for (int i = 0; i < sizeOfNestedTuple; i++) {
                                    ld.add(loadindxNodes.get(currentField + i));
                                }
                                loadFieldNodeArrayListHashMap.put(ldf, ld);
                                counter += sizeOfNestedTuple;
                            } else {
                                ld.add(loadindxNodes.get(counter));
                                loadFieldNodeArrayListHashMap.put(ldf, ld);
                                counter++;
                            }
                            currentField++;
                        }

                        // for (LoadFieldNode ldf : loadFieldNodeArrayListHashMap.keySet()) {
                        // System.out.println("LoadFieldNode: " + ldf);
                        // for (LoadIndexedNode ldindx : loadFieldNodeArrayListHashMap.get(ldf)) {
                        // System.out.println("== " + ldindx);
                        // }
                        // }

                        ArrayList<ValueNode> storeTupleInputs = new ArrayList<>();
                        LinkedHashMap<ValueNode, StoreIndexedNode> storesWithInputs = new LinkedHashMap<>();
                        boolean alloc = false;
                        if (returnTuple) {
                            for (Node n : graph.getNodes()) {
                                if (n instanceof CommitAllocationNode) {
                                    alloc = true;
                                    for (Node in : n.inputs()) {
                                        if (!(in instanceof VirtualInstanceNode)) {
                                            if (in instanceof LoadFieldNode) {
                                                for (LoadIndexedNode ldInd : loadFieldNodeArrayListHashMap.get((LoadFieldNode) in)) {
                                                    storeTupleInputs.add(ldInd);
                                                }
                                            } else {
                                                storeTupleInputs.add((ValueNode) in);
                                            }
                                        }
                                    }
                                }
                            }

                            MulNode returnIndexOffset = null;
                            Constant returnTupleSizeConst = null;

                            returnTupleSizeConst = new RawConstant(returnTupleSize);

                            ConstantNode retIndexInc = new ConstantNode(returnTupleSizeConst, StampFactory.positiveInt());
                            graph.addWithoutUnique(retIndexInc);
                            ValuePhiNode retPhNode = null;
                            for (Node n : graph.getNodes()) {
                                if (n instanceof ValuePhiNode) {
                                    retPhNode = (ValuePhiNode) n;
                                    returnIndexOffset = new MulNode(retIndexInc, retPhNode);
                                    graph.addWithoutUnique(returnIndexOffset);
                                }
                            }

                            if (alloc) {
                                for (Node n : graph.getNodes()) {
                                    if (n instanceof StoreIndexedNode) {
                                        if (returnTupleSize == 2) {
                                            StoreIndexedNode st = (StoreIndexedNode) n;
                                            StoreIndexedNode newst = graph.addOrUnique(
                                                    new StoreIndexedNode(st.array(), returnIndexOffset, null, null, JavaKind.fromJavaClass(returnFieldKind.get(0)), storeTupleInputs.get(0)));
                                            storesWithInputs.put(storeTupleInputs.get(0), newst);
                                            Constant retNextPosition = new RawConstant(1);
                                            ConstantNode retNextIndxOffset = new ConstantNode(retNextPosition, StampFactory.positiveInt());
                                            graph.addWithoutUnique(retNextIndxOffset);
                                            AddNode nextRetTupleIndx = new AddNode(retNextIndxOffset, returnIndexOffset);
                                            graph.addWithoutUnique(nextRetTupleIndx);
                                            StoreIndexedNode newst2 = graph.addOrUnique(
                                                    new StoreIndexedNode(st.array(), nextRetTupleIndx, null, null, JavaKind.fromJavaClass(returnFieldKind.get(1)), storeTupleInputs.get(1)));
                                            storesWithInputs.put(storeTupleInputs.get(1), newst2);
                                            graph.replaceFixed(st, newst);
                                            graph.addAfterFixed(newst, newst2);
                                            break;
                                        } else if (returnTupleSize == 3) {
                                            StoreIndexedNode st = (StoreIndexedNode) n;
                                            StoreIndexedNode newst = graph.addOrUnique(
                                                    new StoreIndexedNode(st.array(), returnIndexOffset, null, null, JavaKind.fromJavaClass(returnFieldKind.get(0)), storeTupleInputs.get(0)));
                                            storesWithInputs.put(storeTupleInputs.get(0), newst);

                                            Constant retNextPosition = new RawConstant(1);
                                            ConstantNode retNextIndxOffset = new ConstantNode(retNextPosition, StampFactory.positiveInt());
                                            graph.addWithoutUnique(retNextIndxOffset);
                                            AddNode nextRetTupleIndx = new AddNode(retNextIndxOffset, returnIndexOffset);
                                            graph.addWithoutUnique(nextRetTupleIndx);
                                            StoreIndexedNode newst2 = graph.addOrUnique(
                                                    new StoreIndexedNode(st.array(), nextRetTupleIndx, null, null, JavaKind.fromJavaClass(returnFieldKind.get(1)), storeTupleInputs.get(1)));
                                            storesWithInputs.put(storeTupleInputs.get(1), newst2);
                                            graph.replaceFixed(st, newst);
                                            graph.addAfterFixed(newst, newst2);

                                            Constant retNextPositionF3 = new RawConstant(2);
                                            ConstantNode retNextIndxOffsetF3 = new ConstantNode(retNextPositionF3, StampFactory.positiveInt());
                                            graph.addWithoutUnique(retNextIndxOffsetF3);
                                            AddNode nextRetTupleIndxF3 = new AddNode(retNextIndxOffsetF3, returnIndexOffset);
                                            graph.addWithoutUnique(nextRetTupleIndxF3);
                                            StoreIndexedNode newst3;
                                            // if (TornadoTupleOffset.differentTypesRet) {
                                            newst3 = graph.addOrUnique(
                                                    new StoreIndexedNode(newst2.array(), nextRetTupleIndxF3, null, null, JavaKind.fromJavaClass(returnFieldKind.get(2)), storeTupleInputs.get(2)));
                                            // } else {
                                            // newst3 = graph.addOrUnique(new StoreIndexedNode(newst2.array(),
                                            // nextRetTupleIndxF3, JavaKind.fromJavaClass(int.class),
                                            // storeTupleInputs.get(2)));
                                            // }
                                            storesWithInputs.put(storeTupleInputs.get(2), newst3);
                                            // graph.replaceFixed(st, newst);
                                            graph.addAfterFixed(newst2, newst3);
                                            break;
                                        } else if (returnTupleSize == 4) {
                                            StoreIndexedNode st = (StoreIndexedNode) n;
                                            StoreIndexedNode newst = graph.addOrUnique(
                                                    new StoreIndexedNode(st.array(), returnIndexOffset, null, null, JavaKind.fromJavaClass(returnFieldKind.get(0)), storeTupleInputs.get(0)));
                                            storesWithInputs.put(storeTupleInputs.get(0), newst);
                                            Constant retNextPosition = new RawConstant(1);
                                            ConstantNode retNextIndxOffset = new ConstantNode(retNextPosition, StampFactory.positiveInt());
                                            graph.addWithoutUnique(retNextIndxOffset);
                                            AddNode nextRetTupleIndx = new AddNode(retNextIndxOffset, returnIndexOffset);
                                            graph.addWithoutUnique(nextRetTupleIndx);
                                            StoreIndexedNode newst2 = graph.addOrUnique(
                                                    new StoreIndexedNode(st.array(), nextRetTupleIndx, null, null, JavaKind.fromJavaClass(returnFieldKind.get(1)), storeTupleInputs.get(1)));
                                            storesWithInputs.put(storeTupleInputs.get(1), newst2);
                                            graph.replaceFixed(st, newst);
                                            graph.addAfterFixed(newst, newst2);
                                            Constant retNextPositionF3 = new RawConstant(2);
                                            ConstantNode retNextIndxOffsetF3 = new ConstantNode(retNextPositionF3, StampFactory.positiveInt());
                                            graph.addWithoutUnique(retNextIndxOffsetF3);
                                            AddNode nextRetTupleIndxF3 = new AddNode(retNextIndxOffsetF3, returnIndexOffset);
                                            graph.addWithoutUnique(nextRetTupleIndxF3);
                                            StoreIndexedNode newst3;
                                            newst3 = graph.addOrUnique(
                                                    new StoreIndexedNode(newst2.array(), nextRetTupleIndxF3, null, null, JavaKind.fromJavaClass(returnFieldKind.get(2)), storeTupleInputs.get(2)));
                                            storesWithInputs.put(storeTupleInputs.get(2), newst3);
                                            graph.addAfterFixed(newst2, newst3);
                                            Constant retNextPositionF4 = new RawConstant(3);
                                            ConstantNode retNextIndxOffsetF4 = new ConstantNode(retNextPositionF4, StampFactory.positiveInt());
                                            graph.addWithoutUnique(retNextIndxOffsetF4);
                                            AddNode nextRetTupleIndxF4 = new AddNode(retNextIndxOffsetF4, returnIndexOffset);
                                            graph.addWithoutUnique(nextRetTupleIndxF4);
                                            StoreIndexedNode newst4;
                                            newst4 = graph.addOrUnique(
                                                    new StoreIndexedNode(newst3.array(), nextRetTupleIndxF4, null, null, JavaKind.fromJavaClass(returnFieldKind.get(3)), storeTupleInputs.get(3)));
                                            storesWithInputs.put(storeTupleInputs.get(3), newst4);
                                            graph.addAfterFixed(newst3, newst4);
                                            break;
                                        }
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
                            } else if (n instanceof BoxNode) {
                                for (Node u : n.usages()) {
                                    u.replaceFirstInput(n, n.inputs().first());
                                }
                                Node npred = n.predecessor();
                                Node nsuc = n.successors().first();
                                n.replaceFirstSuccessor(nsuc, null);
                                n.replaceAtPredecessor(nsuc);
                                npred.replaceFirstSuccessor(n, nsuc);
                                n.safeDelete();
                            } else {
                                if (!(n instanceof ConstantNode)) {
                                    if (n instanceof FixedNode) {
                                        Node npred = n.predecessor();
                                        Node nsuc = n.successors().first();
                                        n.replaceFirstSuccessor(nsuc, null);
                                        n.replaceAtPredecessor(nsuc);
                                        npred.replaceFirstSuccessor(n, nsuc);
                                    }
                                    n.safeDelete();
                                }
                            }
                        }

                        if (returnTuple) {
                            for (Node n : graph.getNodes()) {
                                if (n instanceof BoxNode) {
                                    for (Node u : n.usages()) {
                                        u.replaceFirstInput(n, n.inputs().first());
                                    }
                                    Node npred = n.predecessor();
                                    Node nsuc = n.successors().first();
                                    n.replaceFirstSuccessor(nsuc, null);
                                    n.replaceAtPredecessor(nsuc);
                                    npred.replaceFirstSuccessor(n, nsuc);
                                    n.safeDelete();
                                }
                            }
                        }
                    }

                } else {
                    // System.out.println("One For loop");
                    ValuePhiNode ldIndx = null;
                    MulNode indexOffset = null;
                    // if (!TornadoTupleOffset.differentTypes) {
                    Constant tupleSizeConst = new RawConstant(tupleSize);
                    ConstantNode indexInc = new ConstantNode(tupleSizeConst, StampFactory.positiveInt());
                    graph.addWithoutUnique(indexInc);
                    ValuePhiNode phNode = null;
                    for (Node n : graph.getNodes()) {
                        if (n instanceof ValuePhiNode) {
                            phNode = (ValuePhiNode) n;
                            indexOffset = new MulNode(indexInc, phNode);
                            graph.addWithoutUnique(indexOffset);
                        }
                    }

                    // } else {
                    // for (Node n : graph.getNodes()) {
                    // if (n instanceof ValuePhiNode) {
                    // ldIndx = (ValuePhiNode) n;
                    // }
                    // }
                    // }

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

                        MulNode returnIndexOffset = null;
                        Constant returnTupleSizeConst = null;

                        returnTupleSizeConst = new RawConstant(returnTupleSize);

                        ConstantNode retIndexInc = new ConstantNode(returnTupleSizeConst, StampFactory.positiveInt());
                        graph.addWithoutUnique(retIndexInc);
                        ValuePhiNode retPhNode = null;
                        for (Node n : graph.getNodes()) {
                            if (n instanceof ValuePhiNode) {
                                retPhNode = (ValuePhiNode) n;
                                returnIndexOffset = new MulNode(retIndexInc, retPhNode);
                                graph.addWithoutUnique(returnIndexOffset);
                            }
                        }

                        if (alloc) {
                            for (Node n : graph.getNodes()) {
                                if (n instanceof StoreIndexedNode) {
                                    if (returnTupleSize == 2) {
                                        StoreIndexedNode st = (StoreIndexedNode) n;
                                        // FIXME: This long value is hardcoded for testing - replace this
                                        StoreIndexedNode newst;
                                        // if (copyArrayField && returnTupleArrayFieldNo == 0) {
                                        // newst = graph.addOrUnique(new StoreIndexedNode(st.array(), returnIndexOffset,
                                        // null, null, JavaKind.fromJavaClass(double.class), storeTupleInputs.get(0)));
                                        // } else {
                                        newst = graph
                                                .addOrUnique(new StoreIndexedNode(st.array(), returnIndexOffset, null, null, JavaKind.fromJavaClass(returnFieldKind.get(0)), storeTupleInputs.get(0)));
                                        // }
                                        storesWithInputs.put(storeTupleInputs.get(0), newst);
                                        Constant retNextPosition = new RawConstant(1);
                                        ConstantNode retNextIndxOffset = new ConstantNode(retNextPosition, StampFactory.positiveInt());
                                        graph.addWithoutUnique(retNextIndxOffset);
                                        AddNode nextRetTupleIndx = new AddNode(retNextIndxOffset, returnIndexOffset);
                                        graph.addWithoutUnique(nextRetTupleIndx);
                                        StoreIndexedNode newst2 = graph
                                                .addOrUnique(new StoreIndexedNode(st.array(), nextRetTupleIndx, null, null, JavaKind.fromJavaClass(returnFieldKind.get(1)), storeTupleInputs.get(1)));
                                        storesWithInputs.put(storeTupleInputs.get(1), newst2);
                                        graph.replaceFixed(st, newst);
                                        graph.addAfterFixed(newst, newst2);
                                        break;
                                    } else if (returnTupleSize == 3) {
                                        StoreIndexedNode st = (StoreIndexedNode) n;
                                        StoreIndexedNode newst = graph
                                                .addOrUnique(new StoreIndexedNode(st.array(), returnIndexOffset, null, null, JavaKind.fromJavaClass(returnFieldKind.get(0)), storeTupleInputs.get(0)));
                                        storesWithInputs.put(storeTupleInputs.get(0), newst);

                                        Constant retNextPosition = new RawConstant(1);
                                        ConstantNode retNextIndxOffset = new ConstantNode(retNextPosition, StampFactory.positiveInt());
                                        graph.addWithoutUnique(retNextIndxOffset);
                                        AddNode nextRetTupleIndx = new AddNode(retNextIndxOffset, returnIndexOffset);
                                        graph.addWithoutUnique(nextRetTupleIndx);
                                        StoreIndexedNode newst2 = graph
                                                .addOrUnique(new StoreIndexedNode(st.array(), nextRetTupleIndx, null, null, JavaKind.fromJavaClass(returnFieldKind.get(1)), storeTupleInputs.get(1)));
                                        storesWithInputs.put(storeTupleInputs.get(1), newst2);
                                        graph.replaceFixed(st, newst);
                                        graph.addAfterFixed(newst, newst2);

                                        Constant retNextPositionF3 = new RawConstant(2);
                                        ConstantNode retNextIndxOffsetF3 = new ConstantNode(retNextPositionF3, StampFactory.positiveInt());
                                        graph.addWithoutUnique(retNextIndxOffsetF3);
                                        AddNode nextRetTupleIndxF3 = new AddNode(retNextIndxOffsetF3, returnIndexOffset);
                                        graph.addWithoutUnique(nextRetTupleIndxF3);
                                        StoreIndexedNode newst3;
                                        // if (TornadoTupleOffset.differentTypesRet) {
                                        newst3 = graph.addOrUnique(
                                                new StoreIndexedNode(newst2.array(), nextRetTupleIndxF3, null, null, JavaKind.fromJavaClass(returnFieldKind.get(2)), storeTupleInputs.get(2)));
                                        // } else {
                                        // newst3 = graph.addOrUnique(new StoreIndexedNode(newst2.array(),
                                        // nextRetTupleIndxF3, JavaKind.fromJavaClass(int.class),
                                        // storeTupleInputs.get(2)));
                                        // }
                                        storesWithInputs.put(storeTupleInputs.get(2), newst3);
                                        // graph.replaceFixed(st, newst);
                                        graph.addAfterFixed(newst2, newst3);
                                        break;
                                    } else if (returnTupleSize == 4) {
                                        StoreIndexedNode st = (StoreIndexedNode) n;
                                        StoreIndexedNode newst = graph
                                                .addOrUnique(new StoreIndexedNode(st.array(), returnIndexOffset, null, null, JavaKind.fromJavaClass(returnFieldKind.get(0)), storeTupleInputs.get(0)));
                                        storesWithInputs.put(storeTupleInputs.get(0), newst);
                                        Constant retNextPosition = new RawConstant(1);
                                        ConstantNode retNextIndxOffset = new ConstantNode(retNextPosition, StampFactory.positiveInt());
                                        graph.addWithoutUnique(retNextIndxOffset);
                                        AddNode nextRetTupleIndx = new AddNode(retNextIndxOffset, returnIndexOffset);
                                        graph.addWithoutUnique(nextRetTupleIndx);
                                        StoreIndexedNode newst2 = graph
                                                .addOrUnique(new StoreIndexedNode(st.array(), nextRetTupleIndx, null, null, JavaKind.fromJavaClass(returnFieldKind.get(1)), storeTupleInputs.get(1)));
                                        storesWithInputs.put(storeTupleInputs.get(1), newst2);
                                        graph.replaceFixed(st, newst);
                                        graph.addAfterFixed(newst, newst2);
                                        Constant retNextPositionF3 = new RawConstant(2);
                                        ConstantNode retNextIndxOffsetF3 = new ConstantNode(retNextPositionF3, StampFactory.positiveInt());
                                        graph.addWithoutUnique(retNextIndxOffsetF3);
                                        AddNode nextRetTupleIndxF3 = new AddNode(retNextIndxOffsetF3, returnIndexOffset);
                                        graph.addWithoutUnique(nextRetTupleIndxF3);
                                        StoreIndexedNode newst3;
                                        newst3 = graph.addOrUnique(
                                                new StoreIndexedNode(newst2.array(), nextRetTupleIndxF3, null, null, JavaKind.fromJavaClass(returnFieldKind.get(2)), storeTupleInputs.get(2)));
                                        storesWithInputs.put(storeTupleInputs.get(2), newst3);
                                        graph.addAfterFixed(newst2, newst3);
                                        Constant retNextPositionF4 = new RawConstant(3);
                                        ConstantNode retNextIndxOffsetF4 = new ConstantNode(retNextPositionF4, StampFactory.positiveInt());
                                        graph.addWithoutUnique(retNextIndxOffsetF4);
                                        AddNode nextRetTupleIndxF4 = new AddNode(retNextIndxOffsetF4, returnIndexOffset);
                                        graph.addWithoutUnique(nextRetTupleIndxF4);
                                        StoreIndexedNode newst4;
                                        newst4 = graph.addOrUnique(
                                                new StoreIndexedNode(newst3.array(), nextRetTupleIndxF4, null, null, JavaKind.fromJavaClass(returnFieldKind.get(3)), storeTupleInputs.get(3)));
                                        storesWithInputs.put(storeTupleInputs.get(3), newst4);
                                        graph.addAfterFixed(newst3, newst4);
                                        break;
                                    }
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

                    LoadIndexedNode arrayLoadIndx = null;
                    if (arrayField) {
                        for (Node n : graph.getNodes()) {
                            if (n instanceof LoadFieldNode && ((LoadFieldNode) n).field().toString().contains("f" + tupleArrayFieldNo)) {
                                while (n != null && !(n.successors().first() instanceof LoadIndexedNode)) {
                                    n = n.successors().first();
                                }

                                if (n == null)
                                    break;

                                arrayLoadIndx = (LoadIndexedNode) n.successors().first().copyWithInputs();
                                for (Node in : arrayLoadIndx.inputs()) {
                                    if (in instanceof ConstantNode) {
                                        ConstantNode c = (ConstantNode) in;
                                        TornadoTupleOffset.arrayFieldIndex = Integer.parseInt(c.getValue().toValueString());
                                    } else if (in instanceof PhiNode) {
                                        TornadoTupleOffset.arrayIteration = true;
                                    }
                                }
                            }
                        }

                    }
                    ArrayList<LoadIndexedNode> loadindxNodes = new ArrayList<>();

                    // TODO: Create JavaKind types based on the info stored in TypeInfo
                    for (Node n : graph.getNodes()) {
                        if (n instanceof LoadFieldNode) {
                            LoadFieldNode ld = (LoadFieldNode) n;
                            if (ld.field().toString().contains("mdm")) {
                                // WARNING: IF THE ACCESSING OF THE TUPLE FIELDS IS NOT SERIAL THEN THIS CAUSES
                                // A PROBLEM
                                // TODO: Scan graph to see the order the Tuple fields are accessed and place the
                                // LooadIndexedNodes accordingly
                                for (Node successor : ld.successors()) {
                                    if (successor instanceof LoadIndexedNode) {
                                        LoadIndexedNode idx = (LoadIndexedNode) successor;
                                        // Tuples have at least 2 fields
                                        // create loadindexed for the first field (f0) of the tuple
                                        LoadIndexedNode ldf0;
                                        // FIXME: This long value is hardcoded for testing - replace this
                                        // if (copyArrayField && tupleArrayFieldNo == 0) {
                                        // ldf0 = new LoadIndexedNode(null, idx.array(), indexOffset, null,
                                        // JavaKind.fromJavaClass(double.class));
                                        // } else {
                                        ldf0 = new LoadIndexedNode(null, idx.array(), indexOffset, null, JavaKind.fromJavaClass(tupleFieldKind.get(0)));
                                        // }
                                        graph.addWithoutUnique(ldf0);
                                        graph.replaceFixed(idx, ldf0);
                                        loadindxNodes.add(ldf0);
                                        for (int i = 1; i < tupleSize; i++) {
                                            // create nodes to read data for next field of the tuple from the next
                                            // position of the array
                                            LoadIndexedNode ldfn;

                                            Constant nextPosition = new RawConstant(i);
                                            ConstantNode nextIndxOffset = new ConstantNode(nextPosition, StampFactory.positiveInt());
                                            graph.addWithoutUnique(nextIndxOffset);
                                            AddNode nextTupleIndx = new AddNode(nextIndxOffset, indexOffset);
                                            graph.addWithoutUnique(nextTupleIndx);
                                            // create loadindexed for the next field of the tuple
                                            // if (copyArrayField && tupleArrayFieldNo == i) {
                                            // ldfn = new LoadIndexedNode(null, ldf0.array(), nextTupleIndx, null,
                                            // JavaKind.fromJavaClass(double.class));
                                            // } else {
                                            ldfn = new LoadIndexedNode(null, ldf0.array(), nextTupleIndx, null, JavaKind.fromJavaClass(tupleFieldKind.get(i)));
                                            // }
                                            // }
                                            graph.addWithoutUnique(ldfn);
                                            graph.addAfterFixed(loadindxNodes.get(i - 1), ldfn);
                                            loadindxNodes.add(ldfn);
                                        }
                                        break;
                                    }
                                }

                            }
                        }

                    }

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

                    for (LoadFieldNode ldf : fieldToIndex.keySet()) {
                        replaceFieldAtUsages(fieldToIndex.get(ldf), ldf);
                    }

                    if (returnTuple) {
                        for (Node n : storesWithInputs.keySet()) {
                            if (fieldToIndex.containsKey(n)) {
                                StoreIndexedNode st = storesWithInputs.get(n);
                                st.replaceFirstInput(n, fieldToIndex.get(n));
                            }
                        }

                        /*
                         * int k = 0; for (StoreIndexedNode st : storesWithInputs.values()) {
                         * st.replaceFirstInput(st.index(), loadindxNodes.get(k).index()); k++; }
                         */
                    } else {
                        for (Node n : graph.getNodes()) {
                            if (n instanceof StoreIndexedNode) {
                                StoreIndexedNode st = (StoreIndexedNode) n;
                                StoreIndexedNode newst = graph.addOrUnique(new StoreIndexedNode(st.array(), st.index(), null, null, JavaKind.fromJavaClass(storeJavaKind), st.value()));
                                graph.replaceFixed(st, newst);
                                break;
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

                    // if (!returnTuple) {
                    // // replace storeindexednode with a new storeindexednode that has the
                    // appropriate
                    // // javakind
                    //
                    // }

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

                    for (Node n : nodesToDelete) {
                        if (n.successors().first() instanceof StoreIndexedNode) {
                            if (returnTuple) {
                                pred.replaceFirstSuccessor(pred.successors().first(), n);
                                if (n instanceof LoadFieldNode) {
                                    removeFixed(n);
                                }
                            } else {
                                if (arrayField) {
                                    if (n instanceof LoadIndexedNode) {
                                        LoadIndexedNode ldn = (LoadIndexedNode) n;
                                        graph.replaceFixed(ldn, ldn.predecessor());
                                    } else if (n instanceof UnboxNode) {
                                        UnboxNode un = (UnboxNode) n;
                                        graph.replaceFixed(un, pred);
                                    }
                                } else {
                                    UnboxNode un = (UnboxNode) n;
                                    graph.replaceFixed(un, pred);
                                }
                            }
                        } else if (n instanceof BoxNode) {
                            for (Node u : n.usages()) {
                                u.replaceFirstInput(n, n.inputs().first());
                            }
                            Node npred = n.predecessor();
                            Node nsuc = n.successors().first();
                            n.replaceFirstSuccessor(nsuc, null);
                            n.replaceAtPredecessor(nsuc);
                            npred.replaceFirstSuccessor(n, nsuc);
                            n.safeDelete();
                        } else {
                            if (!(n instanceof ConstantNode)) {
                                if (n instanceof FixedNode) {
                                    Node npred = n.predecessor();
                                    Node nsuc = n.successors().first();
                                    n.replaceFirstSuccessor(nsuc, null);
                                    n.replaceAtPredecessor(nsuc);
                                    npred.replaceFirstSuccessor(n, nsuc);
                                    n.safeDelete();
                                } else {
                                    if (!(n instanceof FloatConvertNode)) {
                                        n.safeDelete();
                                    }
                                }
                            }
                        }
                    }

                    if (returnTuple) {
                        for (Node n : graph.getNodes()) {
                            if (n instanceof BoxNode) {
                                for (Node u : n.usages()) {
                                    u.replaceFirstInput(n, n.inputs().first());
                                }
                                Node npred = n.predecessor();
                                Node nsuc = n.successors().first();
                                n.replaceFirstSuccessor(nsuc, null);
                                n.replaceAtPredecessor(nsuc);
                                npred.replaceFirstSuccessor(n, nsuc);
                                n.safeDelete();
                            }
                        }
                    }
                }
            } else {
                if (arrayField) {
                    tupleArrayFieldNo = 0;
                    // exus use case
                    HashSet<LoadIndexedNode> initialLdIndex = new HashSet<>();
                    for (Node n : graph.getNodes()) {
                        if (n instanceof LoadFieldNode && ((LoadFieldNode) n).field().toString().contains("Tuple")) {
                            // for LoadFieldNode that load tuples
                            LoadFieldNode ldf = (LoadFieldNode) n;
                            // locate the LoadIndexNode that is the input of the LoadField
                            LoadIndexedNode param = null;
                            boolean broadcasted = false;

                            Node loadIndexedIndex = null;

                            for (Node in : ldf.inputs()) {
                                if (in instanceof LoadIndexedNode) {
                                    param = (LoadIndexedNode) in;
                                    for (Node indxIn : in.inputs()) {
                                        if (indxIn instanceof ConstantNode || indxIn instanceof PhiNode) {
                                            loadIndexedIndex = indxIn;
                                        }
                                    }
                                    break;
                                }
                            }

                            if (param == null) {
                                for (Node in : ldf.inputs()) {
                                    if (in instanceof PiNode) {
                                        for (Node piIn : in.inputs()) {
                                            if (piIn instanceof LoadIndexedNode) {
                                                param = (LoadIndexedNode) piIn;
                                                for (Node indxIn : in.inputs()) {
                                                    if (indxIn instanceof ParameterNode) {
                                                        broadcasted = true;

                                                    } else if (indxIn instanceof ConstantNode || indxIn instanceof PhiNode) {
                                                        loadIndexedIndex = indxIn;
                                                    }
                                                }
                                                break;
                                            }
                                        }
                                    }
                                }
                            }

                            initialLdIndex.add(param);

                            String field = ldf.field().toString();
                            int fieldNumber = Integer.parseInt(Character.toString(field.substring(field.lastIndexOf(".") + 1).charAt(1)));
                            if (fieldNumber == tupleArrayFieldNo) {
                                // if the loadfield corresponds to the array field, use as index the for loop
                                // that iterates over this array

                                // find the LoadIndexNode of the array field
                                Node arrayFieldIndx = null;
                                LoadIndexedNode arrayFieldLoadIndx = null;
                                for (Node us : ldf.usages()) {
                                    if (us instanceof PiNode) {
                                        for (Node piUs : us.usages()) {
                                            if (piUs instanceof LoadIndexedNode) {
                                                arrayFieldLoadIndx = (LoadIndexedNode) piUs;
                                                for (Node ldxInxIn : piUs.inputs()) {
                                                    if (ldxInxIn instanceof PhiNode) {
                                                        arrayFieldIndx = ldxInxIn;
                                                        break;
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                if (arrayFieldIndx == null) {

                                    // the fields of the array are not accessed
                                } else {
                                    // create new LoadIndexNode
                                    LoadIndexedNode newLdIndx = new LoadIndexedNode(null, param.array(), (ValueNode) arrayFieldIndx, null, JavaKind.fromJavaClass(tupleFieldKind.get(fieldNumber)));
                                    graph.addWithoutUnique(newLdIndx);
                                    // fix the usages of the new LoadIndexNode
                                    arrayFieldLoadIndx.replaceAtUsages(newLdIndx);

                                    // place the LoadIndexNode on the graph and remove LoadField
                                    replaceFixed(ldf, newLdIndx);

                                    Node suc = newLdIndx.successors().first();
                                    if (suc instanceof FixedGuardNode) {
                                        removeFixed(suc);
                                    }

                                    removeFixed(arrayFieldLoadIndx);
                                }

                            } else {
                                // if not, use the index of the input LoadIndexedNode
                                LoadIndexedNode newLdIndx = new LoadIndexedNode(null, param.array(), (ValueNode) loadIndexedIndex, null, JavaKind.fromJavaClass(tupleFieldKind.get(fieldNumber)));
                                graph.addWithoutUnique(newLdIndx);

                                UnboxNode unbox = getUnbox(ldf);

                                if (unbox != null) {
                                    unbox.replaceAtUsages(newLdIndx);

                                    replaceFixed(ldf, newLdIndx);

                                    Node temp = newLdIndx.successors().first();
                                    while (!(temp instanceof UnboxNode)) {
                                        Node tempSuc = temp.successors().first();
                                        removeFixed(temp);
                                        temp = tempSuc;
                                    }
                                    removeFixed(unbox);
                                } else {
                                    System.out.println("ERROR: NO UNBOX FOR LOADFIELD NODE. CASE NOT TAKEN INTO ACCOUNT");
                                }
                            }

                        }
                    }

                    for (Node n : initialLdIndex) {
                        removeFixed(n);
                    }
                } else {
                    // System.out.println("Two for loops!");
                    MulNode indexOffset = null;
                    Constant outerTupleSizeConst = new RawConstant(tupleSize);
                    ConstantNode indexInc = new ConstantNode(outerTupleSizeConst, StampFactory.positiveInt());
                    graph.addWithoutUnique(indexInc);
                    ValuePhiNode parallelPhNode = null;

                    for (Node n : graph.getNodes()) {
                        if (n instanceof ValuePhiNode) {
                            ValuePhiNode ph = (ValuePhiNode) n;
                            for (Node in : ph.inputs()) {
                                if (in instanceof GlobalThreadIdNode) {
                                    parallelPhNode = ph;
                                    indexOffset = new MulNode(indexInc, parallelPhNode);
                                    graph.addWithoutUnique(indexOffset);
                                    break;
                                }
                            }
                            if (parallelPhNode != null)
                                break;
                        }
                    }

                    ArrayList<Node> outerTupleOutputs = new ArrayList<>();
                    LoadIndexedNode idxOuterTuple = null;
                    for (Node n : graph.getNodes()) {
                        if (n instanceof LoadFieldNode) {
                            LoadFieldNode ld = (LoadFieldNode) n;
                            if (ld.field().toString().contains("mdm")) {
                                for (Node successor : ld.successors()) {
                                    if (successor instanceof LoadIndexedNode) {
                                        idxOuterTuple = (LoadIndexedNode) successor;
                                        for (Node us : idxOuterTuple.usages()) {
                                            outerTupleOutputs.add(us);
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Node[] storeInputs = new Node[tupleSize];

                    ArrayList<LoadIndexedNode> loadindxNodesOuterLoop = new ArrayList<>();
                    for (Node n : graph.getNodes()) {
                        if (n instanceof LoadFieldNode) {
                            LoadFieldNode ld = (LoadFieldNode) n;
                            if (ld.field().toString().contains("mdm")) {
                                for (Node successor : ld.successors()) {
                                    if (successor instanceof LoadIndexedNode) {
                                        LoadIndexedNode idx = (LoadIndexedNode) successor;
                                        LoadIndexedNode ldf0;
                                        ldf0 = new LoadIndexedNode(null, idx.array(), indexOffset, null, JavaKind.fromJavaClass(tupleFieldKind.get(0)));
                                        graph.addWithoutUnique(ldf0);
                                        graph.replaceFixed(idx, ldf0);
                                        loadindxNodesOuterLoop.add(ldf0);
                                        storeInputs[0] = ldf0;
                                        for (int i = 1; i < tupleSize; i++) {
                                            LoadIndexedNode ldfn;
                                            Constant nextPosition = new RawConstant(i);
                                            ConstantNode nextIndxOffset = new ConstantNode(nextPosition, StampFactory.positiveInt());
                                            graph.addWithoutUnique(nextIndxOffset);
                                            AddNode nextTupleIndx = new AddNode(nextIndxOffset, indexOffset);
                                            graph.addWithoutUnique(nextTupleIndx);
                                            ldfn = new LoadIndexedNode(null, ldf0.array(), nextTupleIndx, null, JavaKind.fromJavaClass(tupleFieldKind.get(i)));
                                            graph.addWithoutUnique(ldfn);
                                            graph.addAfterFixed(loadindxNodesOuterLoop.get(i - 1), ldfn);
                                            loadindxNodesOuterLoop.add(ldfn);
                                            Node tupleLoadFn = outerTupleOutputs.get(i);
                                            storeInputs[i] = ldfn;
                                            for (Node tupleUsages : idx.usages()) {
                                                if (!(tupleUsages instanceof LoadFieldNode)) {
                                                    tupleUsages.replaceFirstInput(tupleLoadFn, ldfn);
                                                }
                                            }
                                        }
                                        break;
                                    }
                                }
                            }
                        }
                    }

                    // int j = 0;
                    /*
                     * for (int i = 0; i < outerTupleOutputs.size(); i++) { if
                     * (outerTupleOutputs.get(i) instanceof LoadFieldNode) { UnboxNode un =
                     * (UnboxNode)
                     * outerTupleOutputs.get(i).successors().first().successors().first().successors
                     * ().first(); un.replaceAtUsages(loadindxNodesOuterLoop.get(j)); j++; } }
                     */

                    for (Node n : outerTupleOutputs) {
                        if (n instanceof LoadFieldNode) {
                            ArrayList<Node> nodesToBeDeletedOuterLoop = new ArrayList<>();
                            LoadFieldNode ldf = (LoadFieldNode) n;
                            Node pred = getTuplePred(ldf, nodesToBeDeletedOuterLoop);
                            Node unbox = getTupleSuc(ldf, nodesToBeDeletedOuterLoop);

                            // before removing unbox, replace it at usages with the corresponding
                            // LoadIndexNode
                            String field = ldf.field().toString();
                            int fieldNumber = Integer.parseInt(Character.toString(field.substring(field.lastIndexOf(".") + 1).charAt(1)));
                            unbox.replaceAtUsages(loadindxNodesOuterLoop.get(fieldNumber));

                            Node replPred = pred.successors().first();
                            Node suc = unbox.successors().first();
                            unbox.replaceFirstSuccessor(suc, null);
                            replPred.replaceAtPredecessor(suc);
                            pred.replaceFirstSuccessor(replPred, suc);
                            nodesToBeDeletedOuterLoop.add(ldf);
                            for (Node del : nodesToBeDeletedOuterLoop) {
                                del.clearInputs();
                                del.safeDelete();
                            }

                        }
                    }

                    // start by locating the array that contains the broadcasted dataset

                    // --- HACK: In order to be able to access the data we have a dummy
                    // LoadIndex node right after the start node of the graph
                    // The location of this node is fixed because we have an array access
                    // on the Flink map skeleton right before the computation
                    // We need to extract this array in order to use it as input for the
                    // nodes that use the broadcasted dataset (in case of KMeans, the
                    // inner loop Tuple nodes)

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

                    LoadFieldNode[] innerLoadFieldNodes = new LoadFieldNode[tupleSizeSecondDataSet];

                    int i = 0;
                    for (Node n : graph.getNodes()) {
                        if (n instanceof LoadFieldNode && ((LoadFieldNode) n).field().toString().contains("Tuple") && !(n.predecessor().predecessor() instanceof StartNode)) {
                            LoadFieldNode lf = (LoadFieldNode) n;
                            innerLoadFieldNodes[i] = lf;
                            i++;
                        }
                    }

                    ValuePhiNode innerPhi = null;

                    for (Node n : graph.getNodes()) {
                        if (n instanceof ValuePhiNode) {
                            ValuePhiNode ph = (ValuePhiNode) n;
                            if (ph.merge() != parallelPhNode.merge()) {
                                for (Node us : ph.usages()) {
                                    if (us instanceof AddNode) {
                                        innerPhi = ph;
                                    }
                                }
                            }
                        }
                    }

                    if (innerPhi == null) {
                        return;
                    }

                    Constant tupleSizeConstInner = new RawConstant(tupleSizeSecondDataSet);
                    ConstantNode innerIndexInc = new ConstantNode(tupleSizeConstInner, StampFactory.positiveInt());
                    graph.addWithoutUnique(innerIndexInc);

                    MulNode innerIndexOffset = null;

                    innerIndexOffset = new MulNode(innerIndexInc, innerPhi);
                    graph.addWithoutUnique(innerIndexOffset);

                    for (LoadFieldNode ldf : innerLoadFieldNodes) {
                        ArrayList<Node> nodesToBeDeletedInnerLoop = new ArrayList<>();

                        Node pred = getTuplePred(ldf, nodesToBeDeletedInnerLoop);

                        Node replPred = pred.successors().first();

                        Node unbox = getTupleSuc(ldf, nodesToBeDeletedInnerLoop);
                        // nodesToBeDeletedInnerLoop.remove(unbox);
                        Node suc = unbox.successors().first();

                        String field = ldf.field().toString();
                        int fieldNumber = Integer.parseInt(Character.toString(field.substring(field.lastIndexOf(".") + 1).charAt(1)));

                        LoadIndexedNode ld;

                        if (fieldNumber == 0) {
                            ld = new LoadIndexedNode(null, secondInput.array(), innerIndexOffset, null, JavaKind.fromJavaClass(tupleFieldKindSecondDataSet.get(0)));
                        } else {
                            Constant nextPosition = new RawConstant(fieldNumber);
                            ConstantNode nextIndxOffset = new ConstantNode(nextPosition, StampFactory.positiveInt());
                            graph.addWithoutUnique(nextIndxOffset);
                            AddNode nextTupleIndx = new AddNode(nextIndxOffset, innerIndexOffset);
                            graph.addWithoutUnique(nextTupleIndx);
                            ld = new LoadIndexedNode(null, secondInput.array(), nextTupleIndx, null, JavaKind.fromJavaClass(tupleFieldKindSecondDataSet.get(fieldNumber)));
                        }
                        graph.addWithoutUnique(ld);

                        // replace Unbox at usages
                        unbox.replaceAtUsages(ld);

                        // break connection
                        unbox.replaceFirstSuccessor(suc, null);
                        // place in graph
                        replPred.replaceAtPredecessor(ld);
                        ld.replaceFirstSuccessor(ld.successors().first(), suc);

                        // delete nodes
                        nodesToBeDeletedInnerLoop.add(ldf);
                        for (Node del : nodesToBeDeletedInnerLoop) {
                            del.clearInputs();
                            del.safeDelete();
                        }
                    }

                    ArrayList<Node> fixGuard = new ArrayList<>();
                    for (Node n : graph.getNodes()) {
                        if (n instanceof FixedGuardNode) {
                            Node suc = n.successors().first();
                            if (suc instanceof FixedGuardNode) {
                                fixGuard.add(n);
                                Node pred = n.predecessor();
                                Node replPred = pred.successors().first();
                                while (suc instanceof FixedGuardNode) {
                                    fixGuard.add(suc);
                                    suc = suc.successors().first();
                                }
                                Node sucPred = suc.predecessor();

                                sucPred.replaceFirstSuccessor(suc, null);

                                replPred.replaceAtPredecessor(suc);

                                pred.replaceFirstSuccessor(n, suc);

                                for (Node f : fixGuard) {
                                    for (Node in : f.inputs()) {
                                        in.safeDelete();
                                    }
                                    f.safeDelete();
                                }

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
                                    if (!(in instanceof VirtualInstanceNode) && !(in instanceof LoadIndexedNode)) {
                                        storeTupleInputs.add((ValueNode) in);
                                    }
                                }
                            }
                        }

                        for (int k = 0; k < storeInputs.length; k++) {
                            storeTupleInputs.add((ValueNode) storeInputs[k]);
                        }

                        MulNode returnIndexOffset = null;
                        Constant returnTupleSizeConst = null;

                        returnTupleSizeConst = new RawConstant(returnTupleSize);

                        ConstantNode retIndexInc = new ConstantNode(returnTupleSizeConst, StampFactory.positiveInt());
                        graph.addWithoutUnique(retIndexInc);
                        ValuePhiNode retPhNode = null;

                        for (Node n : graph.getNodes()) {
                            if (n instanceof StoreIndexedNode) {
                                for (Node in : n.inputs()) {
                                    if (in instanceof ValuePhiNode) {
                                        retPhNode = (ValuePhiNode) in;
                                        returnIndexOffset = new MulNode(retIndexInc, retPhNode);
                                        graph.addWithoutUnique(returnIndexOffset);
                                    }
                                }
                            }
                        }

                        if (alloc) {
                            for (Node n : graph.getNodes()) {
                                if (n instanceof StoreIndexedNode) {
                                    if (returnTupleSize == 2) {
                                        StoreIndexedNode st = (StoreIndexedNode) n;
                                        StoreIndexedNode newst = graph
                                                .addOrUnique(new StoreIndexedNode(st.array(), returnIndexOffset, null, null, JavaKind.fromJavaClass(returnFieldKind.get(0)), storeTupleInputs.get(0)));
                                        storesWithInputs.put(storeTupleInputs.get(0), newst);
                                        Constant retNextPosition = new RawConstant(1);
                                        ConstantNode retNextIndxOffset = new ConstantNode(retNextPosition, StampFactory.positiveInt());
                                        graph.addWithoutUnique(retNextIndxOffset);
                                        AddNode nextRetTupleIndx = new AddNode(retNextIndxOffset, returnIndexOffset);
                                        graph.addWithoutUnique(nextRetTupleIndx);
                                        StoreIndexedNode newst2 = graph
                                                .addOrUnique(new StoreIndexedNode(st.array(), nextRetTupleIndx, null, null, JavaKind.fromJavaClass(returnFieldKind.get(1)), storeTupleInputs.get(1)));
                                        storesWithInputs.put(storeTupleInputs.get(1), newst2);
                                        graph.replaceFixed(st, newst);
                                        graph.addAfterFixed(newst, newst2);
                                        break;
                                    } else if (returnTupleSize == 3) {
                                        StoreIndexedNode st = (StoreIndexedNode) n;
                                        StoreIndexedNode newst = graph
                                                .addOrUnique(new StoreIndexedNode(st.array(), returnIndexOffset, null, null, JavaKind.fromJavaClass(returnFieldKind.get(0)), storeTupleInputs.get(0)));
                                        storesWithInputs.put(storeTupleInputs.get(0), newst);
                                        Constant retNextPosition = new RawConstant(1);
                                        ConstantNode retNextIndxOffset = new ConstantNode(retNextPosition, StampFactory.positiveInt());
                                        graph.addWithoutUnique(retNextIndxOffset);
                                        AddNode nextRetTupleIndx = new AddNode(retNextIndxOffset, returnIndexOffset);
                                        graph.addWithoutUnique(nextRetTupleIndx);
                                        StoreIndexedNode newst2 = graph
                                                .addOrUnique(new StoreIndexedNode(st.array(), nextRetTupleIndx, null, null, JavaKind.fromJavaClass(returnFieldKind.get(1)), storeTupleInputs.get(1)));
                                        storesWithInputs.put(storeTupleInputs.get(1), newst2);
                                        graph.replaceFixed(st, newst);
                                        graph.addAfterFixed(newst, newst2);
                                        Constant retNextPositionF3 = new RawConstant(2);
                                        ConstantNode retNextIndxOffsetF3 = new ConstantNode(retNextPositionF3, StampFactory.positiveInt());
                                        graph.addWithoutUnique(retNextIndxOffsetF3);
                                        AddNode nextRetTupleIndxF3 = new AddNode(retNextIndxOffsetF3, returnIndexOffset);
                                        graph.addWithoutUnique(nextRetTupleIndxF3);
                                        StoreIndexedNode newst3;
                                        newst3 = graph.addOrUnique(
                                                new StoreIndexedNode(newst2.array(), nextRetTupleIndxF3, null, null, JavaKind.fromJavaClass(returnFieldKind.get(2)), storeTupleInputs.get(2)));
                                        storesWithInputs.put(storeTupleInputs.get(2), newst3);
                                        graph.addAfterFixed(newst2, newst3);
                                        break;
                                    } else if (returnTupleSize == 4) {
                                        StoreIndexedNode st = (StoreIndexedNode) n;
                                        StoreIndexedNode newst = graph
                                                .addOrUnique(new StoreIndexedNode(st.array(), returnIndexOffset, null, null, JavaKind.fromJavaClass(returnFieldKind.get(0)), storeTupleInputs.get(0)));
                                        storesWithInputs.put(storeTupleInputs.get(0), newst);
                                        Constant retNextPosition = new RawConstant(1);
                                        ConstantNode retNextIndxOffset = new ConstantNode(retNextPosition, StampFactory.positiveInt());
                                        graph.addWithoutUnique(retNextIndxOffset);
                                        AddNode nextRetTupleIndx = new AddNode(retNextIndxOffset, returnIndexOffset);
                                        graph.addWithoutUnique(nextRetTupleIndx);
                                        StoreIndexedNode newst2 = graph
                                                .addOrUnique(new StoreIndexedNode(st.array(), nextRetTupleIndx, null, null, JavaKind.fromJavaClass(returnFieldKind.get(1)), storeTupleInputs.get(1)));
                                        storesWithInputs.put(storeTupleInputs.get(1), newst2);
                                        graph.replaceFixed(st, newst);
                                        graph.addAfterFixed(newst, newst2);
                                        Constant retNextPositionF3 = new RawConstant(2);
                                        ConstantNode retNextIndxOffsetF3 = new ConstantNode(retNextPositionF3, StampFactory.positiveInt());
                                        graph.addWithoutUnique(retNextIndxOffsetF3);
                                        AddNode nextRetTupleIndxF3 = new AddNode(retNextIndxOffsetF3, returnIndexOffset);
                                        graph.addWithoutUnique(nextRetTupleIndxF3);
                                        StoreIndexedNode newst3;
                                        newst3 = graph.addOrUnique(
                                                new StoreIndexedNode(newst2.array(), nextRetTupleIndxF3, null, null, JavaKind.fromJavaClass(returnFieldKind.get(2)), storeTupleInputs.get(2)));
                                        storesWithInputs.put(storeTupleInputs.get(2), newst3);
                                        graph.addAfterFixed(newst2, newst3);
                                        Constant retNextPositionF4 = new RawConstant(3);
                                        ConstantNode retNextIndxOffsetF4 = new ConstantNode(retNextPositionF4, StampFactory.positiveInt());
                                        graph.addWithoutUnique(retNextIndxOffsetF4);
                                        AddNode nextRetTupleIndxF4 = new AddNode(retNextIndxOffsetF4, returnIndexOffset);
                                        graph.addWithoutUnique(nextRetTupleIndxF4);
                                        StoreIndexedNode newst4;
                                        newst4 = graph.addOrUnique(
                                                new StoreIndexedNode(newst3.array(), nextRetTupleIndxF4, null, null, JavaKind.fromJavaClass(returnFieldKind.get(3)), storeTupleInputs.get(3)));
                                        storesWithInputs.put(storeTupleInputs.get(3), newst4);
                                        graph.addAfterFixed(newst3, newst4);
                                        break;
                                    }
                                }
                            }

                            // delete nodes related to Tuple allocation

                            for (Node n : graph.getNodes()) {
                                if (n instanceof CommitAllocationNode) {
                                    Node predAlloc = n.predecessor();
                                    for (Node sucAlloc : n.successors()) {
                                        predAlloc.replaceFirstSuccessor(n, sucAlloc);
                                    }
                                    for (Node in : n.inputs()) {
                                        if (in instanceof VirtualInstanceNode) {
                                            in.safeDelete();
                                        }
                                    }
                                    n.safeDelete();
                                }
                            }

                            Node box = null;
                            // remove box before store
                            for (Node n : graph.getNodes()) {
                                if (n instanceof BoxNode) {
                                    box = n;
                                    break;
                                }
                            }

                            if (box != null) {

                                Node boxIn = box.inputs().first();
                                for (Node boxUs : box.usages()) {
                                    boxUs.replaceFirstInput(box, boxIn);
                                }

                                Node pred = box.predecessor();
                                Node suc = box.successors().first();

                                box.replaceFirstSuccessor(suc, null);
                                box.replaceAtPredecessor(suc);
                                pred.replaceFirstSuccessor(box, suc);

                                box.safeDelete();
                            }

                        }

                    }

                    // remove dummy loadIndex node
                    Node pred = secondInput.predecessor();
                    Node lf = secondInput.successors().first();
                    Node fixG = lf.successors().first();
                    Node suc = fixG.successors().first();

                    fixG.replaceFirstSuccessor(suc, null);
                    secondInput.replaceAtPredecessor(suc);
                    pred.replaceFirstSuccessor(secondInput, suc);

                    lf.safeDelete();
                    secondInput.safeDelete();
                    fixG.safeDelete();

                }
            }

            // if tuple contains an array, check if we copy the whole array
            if (arrayField && returnArrayField) {

                ArrayList<StoreIndexedNode> storeNodes = new ArrayList<>();
                for (Node n : graph.getNodes()) {
                    if (n instanceof StoreIndexedNode) {
                        storeNodes.add((StoreIndexedNode) n);
                    }
                }

                for (StoreIndexedNode st : storeNodes) {
                    for (Node stIn : st.inputs()) {
                        if (stIn instanceof LoadIndexedNode) {
                            for (Node ldIn : stIn.inputs()) {
                                if (ldIn instanceof MulNode) {
                                    if (tupleArrayFieldNo == 0) {
                                        TornadoTupleOffset.copyArray = true;
                                    }
                                } else if (ldIn instanceof AddNode) {
                                    if (tupleArrayFieldNo != 0) {
                                        for (Node addIn : ldIn.inputs()) {
                                            if (addIn instanceof ConstantNode) {
                                                int index = Integer.parseInt(((ConstantNode) addIn).getValue().toValueString());
                                                if (index == tupleArrayFieldNo) {
                                                    TornadoTupleOffset.copyArray = true;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

            }
        }

    }

    public Node getTuplePred(Node ldf, ArrayList<Node> nodesToBeDeleted) {
        Node pred = ldf.predecessor();
        while (pred instanceof FixedGuardNode) {
            nodesToBeDeleted.add(pred);
            pred = pred.predecessor();
        }
        return pred;
    }

    public Node getTupleSuc(LoadFieldNode ldf, ArrayList<Node> nodesToBeDeleted) {
        Node suc = ldf.successors().first();
        while (!(suc instanceof UnboxNode)) {
            nodesToBeDeleted.add(suc);
            suc = suc.successors().first();
        }
        nodesToBeDeleted.add(suc);
        return suc;
    }

    public UnboxNode getUnbox(LoadFieldNode ldf) {
        Node suc = ldf.successors().first();
        while (!(suc instanceof UnboxNode)) {
            suc = suc.successors().first();
        }
        return (UnboxNode) suc;
    }

    public void replaceFieldAtUsages(LoadIndexedNode lindx, Node usage) {
        for (Node us : usage.usages()) {
            if (us instanceof InstanceOfNode || us instanceof PiNode || us instanceof FixedGuardNode || us instanceof IsNullNode) {
                replaceFieldAtUsages(lindx, us);
            } else {
                us.replaceAtUsages(lindx);
                return;
            }
        }
    }

    public static void removeFixed(Node n) {
        Node pred = n.predecessor();
        Node suc = n.successors().first();

        n.replaceFirstSuccessor(suc, null);
        n.replaceAtPredecessor(suc);
        pred.replaceFirstSuccessor(n, suc);

        n.safeDelete();

        return;
    }

    public static void replaceFixed(Node n, Node other) {
        Node pred = n.predecessor();
        Node suc = n.successors().first();

        n.replaceFirstSuccessor(suc, null);
        n.replaceAtPredecessor(other);
        pred.replaceFirstSuccessor(n, other);
        other.replaceFirstSuccessor(null, suc);

        n.safeDelete();

        return;
    }
}
