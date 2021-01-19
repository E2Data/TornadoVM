package uk.ac.manchester.tornado.drivers.opencl.graal.phases;

import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.RawConstant;

import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.graph.iterators.NodeIterable;
import org.graalvm.compiler.nodes.*;
import org.graalvm.compiler.nodes.calc.*;
import org.graalvm.compiler.nodes.extended.BoxNode;
import org.graalvm.compiler.nodes.extended.UnboxNode;
import org.graalvm.compiler.nodes.java.*;
import org.graalvm.compiler.nodes.util.GraphUtil;
import org.graalvm.compiler.nodes.virtual.CommitAllocationNode;
import org.graalvm.compiler.nodes.virtual.VirtualInstanceNode;
import org.graalvm.compiler.phases.BasePhase;

import org.graalvm.compiler.virtual.nodes.VirtualObjectState;
import uk.ac.manchester.tornado.api.flink.FlinkCompilerInfo;
import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLNullary;
import uk.ac.manchester.tornado.drivers.opencl.graal.nodes.GlobalThreadIdNode;
import uk.ac.manchester.tornado.runtime.FlinkCompilerInfoIntermediate;
import uk.ac.manchester.tornado.runtime.graal.phases.TornadoHighTierContext;

import java.util.*;

public class TornadoTupleReplacement extends BasePhase<TornadoHighTierContext> {

    private boolean hasTuples;
    private int tupleSize;
    private int tupleSizeSecondDataSet;
    private ArrayList<Class> tupleFieldKind;
    private ArrayList<Class> tupleFieldKindSecondDataSet;
    private ArrayList<Integer> fieldSizes;
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
    private int arrayFieldTotalBytes;
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
        this.arrayFieldTotalBytes = flinkCompilerInfo.getArrayFieldTotalBytes();
        this.fieldSizes = flinkCompilerInfo.getFieldSizes();
    }

    @Override
    protected void run(StructuredGraph graph, TornadoHighTierContext context) {
        FlinkCompilerInfo fcomp = FlinkCompilerInfoIntermediate.getFlinkCompilerInfo();
        if (fcomp != null) {
            flinkSetCompInfo(fcomp);
        }

        if (hasTuples) {
            TornadoTupleOffset.copyArray = false;
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

                    if (loadFieldNodesUnordered.size() > tupleSize && !arrayField) {
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
                        if (arrayField) {
                            // EXUS
                            System.out.println("Array nested loops");
                            // handle store nodes
                            // 1) Case: Store node doesn't have a Parameter node as input
                            boolean hasParam = false;
                            StoreIndexedNode noParamStore = null;
                            for (Node n : graph.getNodes()) {
                                if (n instanceof StoreIndexedNode) {
                                    StoreIndexedNode st = (StoreIndexedNode) n;

                                    for (Node in : st.inputs()) {
                                        if (in instanceof ParameterNode) {
                                            hasParam = true;
                                        }
                                    }
                                    if (!hasParam) {
                                        noParamStore = st;
                                        break;
                                    }
                                    hasParam = false;
                                }
                            }

                            if (noParamStore != null) {
                                for (Node in : noParamStore.inputs()) {
                                    if (in instanceof PiNode) {
                                        ParameterNode p = getParameterFromPi((PiNode) in);
                                        noParamStore.replaceFirstInput(in, p);
                                        MulNode returnIndexOffset = null;
                                        Constant returnTupleSizeConst = null;
                                        if (p.toString().contains("(2)")) {
                                            returnTupleSizeConst = new RawConstant(returnTupleSize);
                                        } else {
                                            returnTupleSizeConst = new RawConstant(tupleSize);
                                        }

                                        ConstantNode retIndexInc = new ConstantNode(returnTupleSizeConst, StampFactory.positiveInt());
                                        graph.addWithoutUnique(retIndexInc);
                                        ValueNode retPhNode = noParamStore.index();

                                        returnIndexOffset = new MulNode(retIndexInc, retPhNode);
                                        graph.addWithoutUnique(returnIndexOffset);

                                        noParamStore.replaceFirstInput(retPhNode, returnIndexOffset);

                                        break;
                                    }
                                }
                            }

                            // 2) Case: Regular Tuple store node, where is it preceded by a
                            // CommitAllocationNode

                            ArrayList<ValueNode> storeTupleInputs = new ArrayList<>();
                            LinkedHashMap<ValueNode, StoreIndexedNode> storesWithInputs = new LinkedHashMap<>();
                            boolean alloc = false;
                            if (returnTuple) {
                                for (Node n : graph.getNodes()) {
                                    if (n instanceof CommitAllocationNode) {
                                        alloc = true;
                                        CommitAllocationNode cm = (CommitAllocationNode) n;
                                        List<ValueNode> commitAllocValues = cm.getValues();
                                        // List<VirtualObjectNode> commitAllocVirtualOb = cm.getVirtualObjects();
                                        int nestedPos = -1;
                                        int sizeOfNested = -1;
                                        for (int i = 0; i < commitAllocValues.size(); i++) {
                                            ValueNode val = commitAllocValues.get(i);
                                            if (val instanceof VirtualInstanceNode) {
                                                nestedPos = i;
                                                VirtualInstanceNode vinst = (VirtualInstanceNode) val;
                                                sizeOfNested = vinst.getFields().length;
                                            }
                                        }
                                        int i = 0;
                                        int k = 0;
                                        int numOfVal = commitAllocValues.size();
                                        if (nestedPos != -1) {
                                            while (k < returnTupleSize) {
                                                if (i == nestedPos) {
                                                    for (int j = 0; j < sizeOfNested; j++) {
                                                        ValueNode storeIn = commitAllocValues.get(numOfVal - sizeOfNested + j);
                                                        ValueNode newStoreIn;
                                                        if (storeIn instanceof BoxNode) {
                                                            newStoreIn = (ValueNode) storeIn.inputs().first();
                                                            removeFixed(storeIn, graph);
                                                        } else {
                                                            newStoreIn = storeIn;
                                                        }
                                                        storeTupleInputs.add(i + j, newStoreIn);
                                                    }
                                                    i++;
                                                    k += sizeOfNested;
                                                } else {
                                                    ValueNode storeIn = commitAllocValues.get(i);
                                                    ValueNode newStoreIn;
                                                    if (storeIn instanceof BoxNode) {
                                                        newStoreIn = (ValueNode) storeIn.inputs().first();
                                                        removeFixed(storeIn, graph);
                                                    } else {
                                                        newStoreIn = storeIn;
                                                    }
                                                    storeTupleInputs.add(k, newStoreIn);
                                                    i++;
                                                    k++;
                                                }
                                            }
                                        } else {
                                            for (Node stin : commitAllocValues) {
                                                if (stin instanceof ValueNode) {
                                                    storeTupleInputs.add((ValueNode) stin);
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
                                        break;
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
                                                newst = graph.addOrUnique(
                                                        new StoreIndexedNode(st.array(), returnIndexOffset, null, null, JavaKind.fromJavaClass(returnFieldKind.get(0)), storeTupleInputs.get(0)));
                                                // }
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
                                            // removeFromFrameState(n, graph);
                                            n.safeDelete();
                                        }
                                    }

                                }
                            }

                            NodeIterable<ArrayLengthNode> arrayLenghNodes = graph.getNodes().filter(ArrayLengthNode.class);
                            for (ArrayLengthNode lngth : arrayLenghNodes) {
                                // TODO: Currently we only support arrays that are the first Tuple
                                // TODO cont: field...generalize this
                                // System.out.println("ArrayFieldTotalBytes: " + arrayFieldTotalBytes);
                                // System.out.println("FieldSizes.get(0): " + fieldSizes.get(0));
                                int length = arrayFieldTotalBytes / fieldSizes.get(0);
                                // System.out.println("Graph contains ArrayLength, this will be replaced with "
                                // + length);
                                Constant lengthConst = new RawConstant(length);
                                ConstantNode arrayLength = new ConstantNode(lengthConst, StampFactory.forKind(JavaKind.Int));
                                graph.addWithoutUnique(arrayLength);
                                lngth.replaceAtUsages(arrayLength);
                            }

                            for (ArrayLengthNode lngth : arrayLenghNodes) {
                                Node pred = lngth.predecessor();
                                while (pred instanceof FixedGuardNode) {
                                    Node ppred = pred.predecessor();
                                    removeFixed(pred, graph);
                                    pred = ppred;
                                }
                                removeFixed(lngth, graph);
                            }

                            LoadIndexedNode loadIndx = null;
                            ValuePhiNode loadIndexedIndex = null;
                            ValuePhiNode arrayIndex = null;
                            for (LoadIndexedNode ldinx : graph.getNodes().filter(LoadIndexedNode.class)) {
                                if (ldinx.inputs().filter(ParameterNode.class).isNotEmpty()) {
                                    loadIndx = ldinx;
                                    loadIndexedIndex = ldinx.inputs().filter(ValuePhiNode.class).first();
                                } else {
                                    arrayIndex = ldinx.inputs().filter(ValuePhiNode.class).first();
                                }
                            }

                            if (arrayIndex != null) {
                                TornadoTupleOffset.twoForLoops = true;
                            }

                            // System.out.println("* loadIndex: " + loadIndx);
                            // System.out.println("* loadIndexedIndex: " + loadIndexedIndex);
                            // System.out.println("* arrayIndex: " + arrayIndex);
                            ArrayList<LoadFieldNode> tupleLoadFields = new ArrayList<>();

                            if (loadIndx == null)
                                return;

                            HashMap<Node, ArrayList<Node>> replaceUsage = new HashMap<>();

                            for (LoadFieldNode ldf : graph.getNodes().filter(LoadFieldNode.class)) {
                                if (ldf.field().toString().contains("Tuple") && !(ldf.field().toString().contains("udf"))) {
                                    String field = ldf.field().toString();
                                    int fieldNumber = Integer.parseInt(Character.toString(field.substring(field.lastIndexOf(".") + 1).charAt(1)));
                                    // System.out.println("== LoadFieldNode: " + ldf + " fieldno: " + fieldNumber);
                                    // LoadField number is same as nested
                                    if (fieldNumber == nestedTupleField) {
                                        // If input is LoadIndexedNode this is the initial reference
                                        if (ldf.inputs().first() instanceof LoadIndexedNode) {
                                            // System.out.println("Initial LoadField " + ldf + " fieldno == nestedfield");
                                        } else {
                                            // else, this is the actual usage of the nested field
                                            // System.out.println("tupleArrayFieldNo " + tupleArrayFieldNo);
                                            if (fieldNumber == tupleArrayFieldNo) {
                                                // System.out.println("Nested LoadField " + ldf + " corresponds to an array");
                                                for (Node us : ldf.usages()) {
                                                    // if (us instanceof PiNode) {
                                                    // System.out.println("PI Usage: " + us);
                                                    // } else {
                                                    // System.out.println("--> Usage of " + ldf + ": " + us);
                                                    if (us instanceof PiNode) {
                                                        // System.out.println("************ Pi Usage: " + us);
                                                        for (Node uus : us.usages()) {
                                                            // System.out.println("USAGE uus: " + uus);
                                                            if (uus instanceof LoadIndexedNode) {
                                                                LoadIndexedNode newLdIndx;
                                                                Constant constIndxInc = new RawConstant(fieldSizes.size());
                                                                ConstantNode loadIndexInc = new ConstantNode(constIndxInc, StampFactory.positiveInt());
                                                                graph.addWithoutUnique(loadIndexInc);
                                                                MulNode loadIndexOffset = new MulNode(loadIndexInc, arrayIndex);
                                                                graph.addWithoutUnique(loadIndexOffset);
                                                                if (fieldNumber > 0) {
                                                                    Constant constIndx = new RawConstant(fieldNumber);
                                                                    ConstantNode indx = new ConstantNode(constIndx, StampFactory.positiveInt());
                                                                    graph.addWithoutUnique(indx);
                                                                    AddNode newLoadIndx = new AddNode(indx, loadIndexOffset);
                                                                    graph.addWithoutUnique(newLoadIndx);
                                                                    newLdIndx = new LoadIndexedNode(null, loadIndx.array(), newLoadIndx, null, JavaKind.fromJavaClass(tupleFieldKind.get(fieldNumber)));
                                                                } else {
                                                                    newLdIndx = new LoadIndexedNode(null, loadIndx.array(), loadIndexOffset, null,
                                                                            JavaKind.fromJavaClass(tupleFieldKind.get(fieldNumber)));
                                                                }
                                                                LoadIndexedNode ld = (LoadIndexedNode) uus;
                                                                // Node pred = ld.predecessor();
                                                                graph.addWithoutUnique(newLdIndx);
                                                                newLdIndx.replaceAtUsages(ld);
                                                                graph.replaceFixed(ld, newLdIndx);

                                                                // graph.addAfterFixed((FixedWithNextNode) pred, newLdIndx);
                                                                // removeFixed(ld, graph);
                                                                // for (Node in : newLdIndx.inputs()) {
                                                                // if (in instanceof PiNode) {
                                                                // in.safeDelete();
                                                                // }
                                                                // }
                                                            }
                                                        }
                                                    } else if (us instanceof FixedNode) {
                                                        // System.out.println("Hey!");
                                                        LoadIndexedNode newLdIndx;
                                                        Constant constIndxInc = new RawConstant(fieldSizes.size());
                                                        ConstantNode loadIndexInc = new ConstantNode(constIndxInc, StampFactory.positiveInt());
                                                        graph.addWithoutUnique(loadIndexInc);
                                                        MulNode loadIndexOffset = new MulNode(loadIndexInc, loadIndexedIndex);
                                                        graph.addWithoutUnique(loadIndexOffset);
                                                        if (fieldNumber > 0) {
                                                            Constant constIndx = new RawConstant(fieldNumber);
                                                            ConstantNode indx = new ConstantNode(constIndx, StampFactory.positiveInt());
                                                            graph.addWithoutUnique(indx);
                                                            AddNode newLoadIndx = new AddNode(indx, loadIndexOffset);
                                                            graph.addWithoutUnique(newLoadIndx);
                                                            newLdIndx = new LoadIndexedNode(null, loadIndx.array(), newLoadIndx, null, JavaKind.fromJavaClass(tupleFieldKind.get(fieldNumber)));
                                                        } else {
                                                            newLdIndx = new LoadIndexedNode(null, loadIndx.array(), loadIndexOffset, null, JavaKind.fromJavaClass(tupleFieldKind.get(fieldNumber)));
                                                        }
                                                        graph.addWithoutUnique(newLdIndx);
                                                        // System.out.println(">>> New loadindex: " + newLdIndx + " phi node: " +
                                                        // loadIndexedIndex);

                                                        ArrayList<Node> usages = new ArrayList<>();
                                                        usages.add(ldf);
                                                        usages.add(newLdIndx);
                                                        replaceUsage.put(us, usages);
                                                        // us.replaceFirstInput(ldf, newLdIndx);
                                                        Node pred = ldf.predecessor();
                                                        graph.addAfterFixed((FixedWithNextNode) pred, newLdIndx);
                                                        TornadoTupleOffset.copyArray = true;
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        // if input is LoadIndexedNode this is a regular field
                                        if (ldf.inputs().first() instanceof LoadIndexedNode) {
                                            // System.out.println("Initial LoadField " + ldf + " fieldno != nestedfield");
                                            LoadIndexedNode newLdIndx;
                                            Constant constIndxInc = new RawConstant(fieldSizes.size());
                                            ConstantNode loadIndexInc = new ConstantNode(constIndxInc, StampFactory.positiveInt());
                                            graph.addWithoutUnique(loadIndexInc);
                                            MulNode loadIndexOffset = new MulNode(loadIndexInc, loadIndexedIndex);
                                            graph.addWithoutUnique(loadIndexOffset);
                                            if (fieldNumber > 0) {
                                                int offset = fieldNumber + sizeOfNestedTuple - 1;
                                                Constant constIndx = new RawConstant(offset);
                                                ConstantNode indx = new ConstantNode(constIndx, StampFactory.positiveInt());
                                                graph.addWithoutUnique(indx);
                                                AddNode newLoadIndx = new AddNode(indx, loadIndexOffset);
                                                graph.addWithoutUnique(newLoadIndx);
                                                newLdIndx = new LoadIndexedNode(null, loadIndx.array(), newLoadIndx, null, JavaKind.fromJavaClass(tupleFieldKind.get(fieldNumber)));
                                            } else {
                                                newLdIndx = new LoadIndexedNode(null, loadIndx.array(), loadIndexOffset, null, JavaKind.fromJavaClass(tupleFieldKind.get(fieldNumber)));
                                            }
                                            graph.addWithoutUnique(newLdIndx);

                                            for (Node us : ldf.usages()) {
                                                if (us instanceof PiNode) {
                                                    PiNode pius = getPiWithUsages((PiNode) us);
                                                    // System.out.println("Replace pi node " + pius + " at usages");
                                                    pius.replaceAtUsages(newLdIndx);
                                                } else if (us instanceof FixedNode) {
                                                    // System.out.println("Fixed usage: " + us);
                                                    // System.out.println("Replace " + ldf + " at usages");
                                                    ldf.replaceAtUsages(newLdIndx);
                                                } else if (us instanceof InstanceOfNode) {
                                                    us.safeDelete();
                                                }
                                            }

                                            graph.replaceFixed(ldf, newLdIndx);

                                            for (Node us : newLdIndx.usages()) {
                                                if (us instanceof PiNode) {
                                                    us.safeDelete();
                                                }
                                            }

                                        } else {
                                            // System.out.println("Nested LoadField " + ldf);
                                            // for (Node us : ldf.usages()) {
                                            // System.out.println("Usage of " + ldf + ": " + us);
                                            // }
                                            LoadIndexedNode newLdIndx;
                                            Constant constIndxInc = new RawConstant(fieldSizes.size());
                                            ConstantNode loadIndexInc = new ConstantNode(constIndxInc, StampFactory.positiveInt());
                                            graph.addWithoutUnique(loadIndexInc);
                                            MulNode loadIndexOffset = new MulNode(loadIndexInc, loadIndexedIndex);
                                            graph.addWithoutUnique(loadIndexOffset);
                                            if (fieldNumber > 0) {
                                                // int offset = fieldNumber + sizeOfNestedTuple - 1;
                                                Constant constIndx = new RawConstant(fieldNumber);
                                                ConstantNode indx = new ConstantNode(constIndx, StampFactory.positiveInt());
                                                graph.addWithoutUnique(indx);
                                                AddNode newLoadIndx = new AddNode(indx, loadIndexOffset);
                                                graph.addWithoutUnique(newLoadIndx);
                                                newLdIndx = new LoadIndexedNode(null, loadIndx.array(), newLoadIndx, null, JavaKind.fromJavaClass(tupleFieldKind.get(fieldNumber)));
                                            } else {
                                                newLdIndx = new LoadIndexedNode(null, loadIndx.array(), loadIndexOffset, null, JavaKind.fromJavaClass(tupleFieldKind.get(fieldNumber)));
                                            }
                                            graph.addWithoutUnique(newLdIndx);

                                            // newLdIndx.replaceAtUsages(ldf);
                                            graph.replaceFixed(ldf, newLdIndx);
                                            // else this is a nested field
                                        }
                                    }
                                    if (!ldf.isDeleted()) {
                                        tupleLoadFields.add(ldf);
                                    }
                                }
                            }

                            for (Node us : replaceUsage.keySet()) {
                                // System.out.println("Replace usage " + replaceUsage.get(us).get(0) + " of node
                                // " + us + " with new usage " + replaceUsage.get(us).get(1));
                                us.replaceFirstInput(replaceUsage.get(us).get(0), replaceUsage.get(us).get(1));
                            }

                            for (LoadFieldNode ldf : tupleLoadFields) {
                                removeFixed(ldf, graph);
                            }

                            removeFixed(loadIndx, graph);

                            ArrayList<UnboxNode> unboxNodes = new ArrayList<>();

                            for (UnboxNode unbox : graph.getNodes().filter(UnboxNode.class)) {
                                unbox.replaceAtUsages(unbox.inputs().first());
                                unboxNodes.add(unbox);
                            }

                            for (UnboxNode unbox : unboxNodes) {
                                removeFixed(unbox, graph);
                            }

                            return;

                        }

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
                                CommitAllocationNode cm = (CommitAllocationNode) n;
                                List<ValueNode> commitAllocValues = cm.getValues();
                                // List<VirtualObjectNode> commitAllocVirtualOb = cm.getVirtualObjects();
                                int nestedPos = -1;
                                int sizeOfNested = -1;
                                for (int i = 0; i < commitAllocValues.size(); i++) {
                                    ValueNode val = commitAllocValues.get(i);
                                    if (val instanceof VirtualInstanceNode) {
                                        nestedPos = i;
                                        VirtualInstanceNode vinst = (VirtualInstanceNode) val;
                                        sizeOfNested = vinst.getFields().length;
                                    }
                                }
                                int i = 0;
                                int k = 0;
                                int numOfVal = commitAllocValues.size();
                                if (nestedPos != -1) {
                                    while (k < returnTupleSize) {
                                        if (i == nestedPos) {
                                            for (int j = 0; j < sizeOfNested; j++) {
                                                storeTupleInputs.add(i + j, commitAllocValues.get(numOfVal - sizeOfNested + j));
                                            }
                                            i++;
                                            k += sizeOfNested;
                                        } else {
                                            storeTupleInputs.add(k, commitAllocValues.get(i));
                                            i++;
                                            k++;
                                        }
                                    }
                                } else {
                                    for (Node stin : commitAllocValues) {
                                        if (stin instanceof ValueNode) {
                                            storeTupleInputs.add((ValueNode) stin);
                                        }
                                    }
                                }

                                // for (Node in : n.inputs()) {
                                // if (!(in instanceof VirtualInstanceNode)) {
                                // storeTupleInputs.add((ValueNode) in);
                                // }
                                // }
                            }
                        }

                        // for (int i = 0; i < storeTupleInputs.size() - 1; i++) {
                        // ValueNode n = storeTupleInputs.get(i);
                        // ValueNode nnext = storeTupleInputs.get(i + 1);
                        // if (n.getId() > nnext.getId()) {
                        // storeTupleInputs.set(i, nnext);
                        // storeTupleInputs.set(i + 1, n);
                        // }
                        // }
                        // if (returnNestedTuple) {

                        // for (Node n : storeTupleInputs) {
                        // System.out.println("-- sorted " + n);
                        // }
                        // }

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

                    for (Node n : graph.getNodes()) {
                        if (n instanceof LoadFieldNode) {
                            if (!(((LoadFieldNode) n).field().toString().contains("mdm") || ((LoadFieldNode) n).field().toString().contains("udf"))) {
                                LoadFieldNode ld = (LoadFieldNode) n;
                                String field = ld.field().toString();
                                int fieldNumber = Integer.parseInt(Character.toString(field.substring(field.lastIndexOf(".") + 1).charAt(1)));
                                if (ld.field().toString().contains("org.apache.flink.api.java.tuple.Tuple")) {
                                    fieldToIndex.put(ld, loadindxNodes.get(fieldNumber));
                                }
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
                            if (!(n instanceof BinaryArithmeticNode || n instanceof FloatConvertNode)) {
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

                    }

                    for (Node n : nodesToDelete) {
                        if (n.successors().first() instanceof StoreIndexedNode) {
                            if (returnTuple) {
                                pred.replaceFirstSuccessor(pred.successors().first(), n);
                                if (n instanceof LoadFieldNode) {
                                    removeFixed(n, graph);
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
                    boolean code = false;
                    for (Node n : graph.getNodes()) {
                        if (n instanceof ValuePhiNode) {
                            code = true;
                            break;
                        }
                    }
                    if (!code) {
                        System.out.println("Not running actual code");
                        return;
                    }

                    // remove all framestate nodes

                    // exus use case
                    // handle store nodes
                    // 1) Case: Store node doesn't have a Parameter node as input
                    boolean hasParam = false;
                    StoreIndexedNode noParamStore = null;
                    for (Node n : graph.getNodes()) {
                        if (n instanceof StoreIndexedNode) {
                            StoreIndexedNode st = (StoreIndexedNode) n;

                            for (Node in : st.inputs()) {
                                if (in instanceof ParameterNode) {
                                    hasParam = true;
                                }
                            }
                            if (!hasParam) {
                                noParamStore = st;
                                break;
                            }
                            hasParam = false;
                        }
                    }

                    if (noParamStore != null) {
                        for (Node in : noParamStore.inputs()) {
                            if (in instanceof PiNode) {
                                ParameterNode p = getParameterFromPi((PiNode) in);
                                noParamStore.replaceFirstInput(in, p);
                                MulNode returnIndexOffset = null;
                                Constant returnTupleSizeConst = null;

                                returnTupleSizeConst = new RawConstant(returnTupleSize);

                                ConstantNode retIndexInc = new ConstantNode(returnTupleSizeConst, StampFactory.positiveInt());
                                graph.addWithoutUnique(retIndexInc);
                                ValueNode retPhNode = noParamStore.index();

                                returnIndexOffset = new MulNode(retIndexInc, retPhNode);
                                graph.addWithoutUnique(returnIndexOffset);

                                noParamStore.replaceFirstInput(retPhNode, returnIndexOffset);

                                break;
                            }
                        }
                    }

                    // 2) Case: Regular Tuple store node, where is it preceded by a
                    // CommitAllocationNode

                    ArrayList<ValueNode> storeTupleInputs = new ArrayList<>();
                    LinkedHashMap<ValueNode, StoreIndexedNode> storesWithInputs = new LinkedHashMap<>();
                    boolean alloc = false;
                    if (returnTuple) {
                        for (Node n : graph.getNodes()) {
                            if (n instanceof CommitAllocationNode) {
                                alloc = true;
                                CommitAllocationNode cm = (CommitAllocationNode) n;
                                List<ValueNode> commitAllocValues = cm.getValues();
                                // List<VirtualObjectNode> commitAllocVirtualOb = cm.getVirtualObjects();
                                int nestedPos = -1;
                                int sizeOfNested = -1;
                                for (int i = 0; i < commitAllocValues.size(); i++) {
                                    ValueNode val = commitAllocValues.get(i);
                                    if (val instanceof VirtualInstanceNode) {
                                        nestedPos = i;
                                        VirtualInstanceNode vinst = (VirtualInstanceNode) val;
                                        sizeOfNested = vinst.getFields().length;
                                    }
                                }
                                int i = 0;
                                int k = 0;
                                int numOfVal = commitAllocValues.size();
                                if (nestedPos != -1) {
                                    while (k < returnTupleSize) {
                                        if (i == nestedPos) {
                                            for (int j = 0; j < sizeOfNested; j++) {
                                                ValueNode storeIn = commitAllocValues.get(numOfVal - sizeOfNested + j);
                                                ValueNode newStoreIn;
                                                if (storeIn instanceof BoxNode) {
                                                    newStoreIn = (ValueNode) storeIn.inputs().first();
                                                    removeFixed(storeIn, graph);
                                                } else {
                                                    newStoreIn = storeIn;
                                                }
                                                storeTupleInputs.add(i + j, newStoreIn);
                                            }
                                            i++;
                                            k += sizeOfNested;
                                        } else {
                                            ValueNode storeIn = commitAllocValues.get(i);
                                            ValueNode newStoreIn;
                                            if (storeIn instanceof BoxNode) {
                                                newStoreIn = (ValueNode) storeIn.inputs().first();
                                                removeFixed(storeIn, graph);
                                            } else {
                                                newStoreIn = storeIn;
                                            }
                                            storeTupleInputs.add(k, newStoreIn);
                                            i++;
                                            k++;
                                        }
                                    }
                                } else {
                                    for (Node stin : commitAllocValues) {
                                        if (stin instanceof ValueNode) {
                                            storeTupleInputs.add((ValueNode) stin);
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
                                break;
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
                                    // removeFromFrameState(n, graph);
                                    n.safeDelete();
                                }
                            }

                        }
                    }

                    if (arrayField) {
                        for (Node n : graph.getNodes()) {
                            if (n instanceof LoadFieldNode && ((LoadFieldNode) n).field().toString().contains("f" + tupleArrayFieldNo)) {
                                while (n != null && !(n.successors().first() instanceof LoadIndexedNode)) {
                                    n = n.successors().first();
                                }

                                if (n == null)
                                    break;

                                for (Node in : n.successors().first().inputs()) {
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

                                    Constant constIndxInc = new RawConstant(2);
                                    ConstantNode indexInc = new ConstantNode(constIndxInc, StampFactory.positiveInt());
                                    graph.addWithoutUnique(indexInc);
                                    MulNode indexOffset = new MulNode(indexInc, (ValueNode) loadIndexedIndex);
                                    graph.addWithoutUnique(indexOffset);
                                    LoadIndexedNode newLdIndx;
                                    if (fieldNumber > 0) {
                                        Constant constIndx = new RawConstant(fieldNumber);
                                        ConstantNode indx = new ConstantNode(constIndx, StampFactory.positiveInt());
                                        graph.addWithoutUnique(indx);
                                        AddNode newLoadIndx = new AddNode(indx, indexOffset);
                                        graph.addWithoutUnique(newLoadIndx);
                                        newLdIndx = new LoadIndexedNode(null, param.array(), newLoadIndx, null, JavaKind.fromJavaClass(tupleFieldKind.get(fieldNumber)));
                                    } else {
                                        newLdIndx = new LoadIndexedNode(null, param.array(), indexOffset, null, JavaKind.fromJavaClass(tupleFieldKind.get(fieldNumber)));
                                    }
                                    graph.addWithoutUnique(newLdIndx);

                                    ldf.replaceAtUsages(newLdIndx);

                                    replaceFixed(ldf, newLdIndx, graph);
                                    // the fields of the array are not accessed

                                } else {
                                    // create new LoadIndexNode
                                    Constant constIndxInc = new RawConstant(2);
                                    ConstantNode indexInc = new ConstantNode(constIndxInc, StampFactory.positiveInt());
                                    graph.addWithoutUnique(indexInc);
                                    MulNode indexOffset = new MulNode(indexInc, (ValueNode) arrayFieldIndx);
                                    graph.addWithoutUnique(indexOffset);
                                    LoadIndexedNode newLdIndx;
                                    if (fieldNumber > 0) {
                                        Constant constIndx = new RawConstant(fieldNumber);
                                        ConstantNode indx = new ConstantNode(constIndx, StampFactory.positiveInt());
                                        graph.addWithoutUnique(indx);
                                        AddNode newLoadIndx = new AddNode(indx, indexOffset);
                                        graph.addWithoutUnique(newLoadIndx);
                                        newLdIndx = new LoadIndexedNode(null, param.array(), newLoadIndx, null, JavaKind.fromJavaClass(tupleFieldKind.get(fieldNumber)));
                                    } else {
                                        newLdIndx = new LoadIndexedNode(null, param.array(), indexOffset, null, JavaKind.fromJavaClass(tupleFieldKind.get(fieldNumber)));
                                    }
                                    graph.addWithoutUnique(newLdIndx);
                                    // fix the usages of the new LoadIndexNode
                                    arrayFieldLoadIndx.replaceAtUsages(newLdIndx);

                                    // place the LoadIndexNode on the graph and remove LoadField
                                    replaceFixed(ldf, newLdIndx, graph);

                                    Node suc = newLdIndx.successors().first();
                                    if (suc instanceof FixedGuardNode) {
                                        removeFixed(suc, graph);
                                    }

                                    removeFixed(arrayFieldLoadIndx, graph);
                                }

                            } else {
                                // if not, use the index of the input LoadIndexedNode
                                Constant constIndxInc = new RawConstant(2);
                                ConstantNode indexInc = new ConstantNode(constIndxInc, StampFactory.positiveInt());
                                graph.addWithoutUnique(indexInc);
                                MulNode indexOffset = new MulNode(indexInc, (ValueNode) loadIndexedIndex);
                                graph.addWithoutUnique(indexOffset);
                                LoadIndexedNode newLdIndx;
                                if (fieldNumber > 0) {
                                    Constant constIndx = new RawConstant(fieldNumber);
                                    ConstantNode indx = new ConstantNode(constIndx, StampFactory.positiveInt());
                                    graph.addWithoutUnique(indx);
                                    AddNode newLoadIndx = new AddNode(indx, indexOffset);
                                    graph.addWithoutUnique(newLoadIndx);
                                    newLdIndx = new LoadIndexedNode(null, param.array(), newLoadIndx, null, JavaKind.fromJavaClass(tupleFieldKind.get(fieldNumber)));
                                } else {
                                    newLdIndx = new LoadIndexedNode(null, param.array(), indexOffset, null, JavaKind.fromJavaClass(tupleFieldKind.get(fieldNumber)));
                                }
                                graph.addWithoutUnique(newLdIndx);

                                UnboxNode unbox = getUnbox(ldf);

                                if (unbox != null) {
                                    unbox.replaceAtUsages(newLdIndx);

                                    replaceFixed(ldf, newLdIndx, graph);

                                    Node temp = newLdIndx.successors().first();
                                    while (!(temp instanceof UnboxNode)) {
                                        Node tempSuc = temp.successors().first();
                                        removeFixed(temp, graph);
                                        temp = tempSuc;
                                    }
                                    removeFixed(unbox, graph);
                                } else {
                                    System.out.println("ERROR: NO UNBOX FOR LOADFIELD NODE. CASE NOT TAKEN INTO ACCOUNT");
                                }
                            }

                        }
                    }

                    for (Node n : initialLdIndex) {
                        for (Node us : n.usages()) {
                            n.removeUsage(us);
                        }
                        n.clearInputs();
                        removeFixed(n, graph);
                    }

                    // FrameState f = null;

                    HashSet<ValuePhiNode> BoxPh = new HashSet<>();
                    FrameState frVirt = null;
                    for (Node n : graph.getNodes()) {
                        if (n instanceof BoxNode) {
                            for (Node us : n.usages()) {
                                if (us instanceof ValuePhiNode) {
                                    BoxPh.add((ValuePhiNode) us);
                                }
                            }
                            Node constValue = n.inputs().first();
                            n.replaceAtUsages(constValue);
                            removeFixed(n, graph);
                        }

                        if (n instanceof VirtualObjectState || n instanceof VirtualInstanceNode) {
                            // removeFromFrameState(n, graph);
                            for (Node us : n.usages()) {
                                if (n instanceof VirtualObjectState) {
                                    if (us instanceof FrameState) {
                                        // for (Node uus : us.usages()) {
                                        // System.out.println("-> Remove usage " + uus + " from " + us);
                                        // us.removeUsage(uus);
                                        // for (Node uusin : uus.inputs()) {
                                        // if (uusin == us) {
                                        // System.out.println("+-> Replace input " + uusin + " of " + uus + " with
                                        // null");
                                        // uus.replaceFirstInput(uusin, null);
                                        // }
                                        // }
                                        // }
                                        // us.safeDelete();
                                        frVirt = (FrameState) us;
                                    }
                                    // if (us instanceof FrameState) {
                                    // f = (FrameState) us;
                                    // f.virtualObjectMappings().remove(n);
                                    // }
                                }
                                n.removeUsage(us);
                            }
                            n.clearInputs();
                            n.safeDelete();
                        }
                    }

                    for (ValuePhiNode ph : BoxPh) {
                        // System.out.println("Box phi: " + ph);
                        ValueNode[] values = new ValueNode[ph.valueCount()];
                        int i = 0;
                        for (ValueNode v : ph.values()) {
                            values[i] = v;
                            i++;
                        }
                        ValuePhiNode newPh = new ValuePhiNode(StampFactory.positiveInt(), ph.merge(), values);
                        graph.addWithoutUnique(newPh);
                        ph.replaceAtUsages(newPh);
                        if (frVirt != null) {
                            frVirt.values().add(newPh);
                        }
                        for (Node in : ph.inputs()) {
                            for (Node usin : in.usages()) {
                                if (usin == ph) {
                                    usin.replaceAtUsages(newPh);
                                }
                            }
                        }
                        // removeFromFrameState(ph, graph);
                        ph.safeDelete();
                    }

                    // System.out.println("==== Removing unused inputs/usages....");
                    // for (Node n : graph.getNodes()) {
                    // for (Node us : n.usages()) {
                    // if (us.isDeleted()) {
                    // n.removeUsage(us);
                    // }
                    // }
                    //
                    // for (Node in : n.inputs()) {
                    // if (in.isDeleted()) {
                    // n.replaceFirstInput(in, null);
                    // }
                    // }
                    // }
                    // System.out.println("==== Unused inputs/usages removed!");

                    // System.out.println("==== Deleting Framestate nodes....");
                    // NodeIterable<FrameState> filter = graph.getNodes().filter(FrameState.class);
                    // for (FrameState n : filter) {
                    // // if (n instanceof FrameState) {
                    // // for (Node us : n.usages()) {
                    // // n.removeUsage(us);
                    // // }
                    // // for (Node v : n.values()) {
                    // // n.values().remove(v);
                    // // }
                    //
                    // // n.clearInputs();
                    // if (!n.virtualObjectMappings().isEmpty()) {
                    // // if (!(n.usages().first() instanceof LoopExitNode)) {
                    //
                    // n.safeDelete();
                    // // GraphUtil.killWithUnusedFloatingInputs(n);
                    // }
                    // // }
                    // }
                    // System.out.println("==== Framestate nodes deleted!");

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

    public static void removeFixed(Node n, StructuredGraph graph) {
        Node pred = n.predecessor();
        Node suc = n.successors().first();

        n.replaceFirstSuccessor(suc, null);
        n.replaceAtPredecessor(suc);
        pred.replaceFirstSuccessor(n, suc);

        // removeFromFrameState(n, graph);

        for (Node us : n.usages()) {
            n.removeUsage(us);
        }
        n.clearInputs();

        n.safeDelete();

        return;
    }

    public static void replaceFixed(Node n, Node other, StructuredGraph graph) {
        Node pred = n.predecessor();
        Node suc = n.successors().first();

        n.replaceFirstSuccessor(suc, null);
        n.replaceAtPredecessor(other);
        pred.replaceFirstSuccessor(n, other);
        other.replaceFirstSuccessor(null, suc);

        // removeFromFrameState(n, graph);
        for (Node us : n.usages()) {
            n.removeUsage(us);
        }
        n.clearInputs();
        n.safeDelete();

        return;
    }

    public static ParameterNode getParameterFromPi(PiNode pi) {
        ParameterNode param = null;

        for (LoadFieldNode lf : pi.inputs().filter(LoadFieldNode.class)) {
            PiNode piNext = null;
            for (Node lfin : lf.inputs()) {
                if (lfin instanceof LoadIndexedNode) {
                    for (Node indxin : lfin.inputs()) {
                        if (indxin instanceof ParameterNode) {
                            param = (ParameterNode) indxin;
                            break;
                        }
                    }
                } else if (lfin instanceof PiNode) {
                    piNext = (PiNode) lfin;
                }
            }
            if (param != null) {
                // System.out.println("Param!=null, return " + param);
                return param;
            }

            if (piNext != null) {
                // System.out.println("piNext!=null, recursion");
                param = getParameterFromPi(piNext);
            }
        }
        // System.out.println("Return " + param);
        return param;
    }

    public static PiNode getPiWithUsages(PiNode pi) {

        if (pi.usages().filter(PiNode.class).isEmpty())
            return pi;

        PiNode piUs = null;
        for (Node us : pi.usages()) {
            if (us instanceof PiNode) {
                piUs = getPiWithUsages((PiNode) us);
                break;
            }
        }
        if (piUs == null)
            return pi;
        else
            return piUs;
    }
    // public static void removeFromFrameState(Node del, StructuredGraph graph) {
    // for (Node n : graph.getNodes()) {
    // if (n instanceof FrameState) {
    // FrameState f = (FrameState) n;
    // if (f.values().contains(del)) {
    // f.values().remove(del);
    // }
    // // } else if (f.inputs().contains(del)) {
    // // f.replaceFirstInput(del, null);
    // // }
    // }
    // }
    // }
}
