package uk.ac.manchester.tornado.drivers.opencl.graal.phases;

import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.RawConstant;

import org.graalvm.compiler.core.common.type.Stamp;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.*;
import org.graalvm.compiler.nodes.calc.*;
import org.graalvm.compiler.nodes.memory.FloatingReadNode;
import org.graalvm.compiler.nodes.memory.WriteNode;
import org.graalvm.compiler.phases.Phase;

import uk.ac.manchester.tornado.api.flink.FlinkCompilerInfo;
import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLAddressNode;
import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLNullary;
import uk.ac.manchester.tornado.drivers.opencl.graal.nodes.CopyArrayTupleField;
import uk.ac.manchester.tornado.drivers.opencl.graal.nodes.GlobalThreadIdNode;
import uk.ac.manchester.tornado.runtime.FlinkCompilerInfoIntermediate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

public class TornadoTupleOffset extends Phase {

    private boolean differentTypes;
    private ArrayList<Integer> fieldSizes;
    private ArrayList<String> fieldTypes;
    // -- for KMeans
    private boolean differentTypesInner;
    private ArrayList<Integer> fieldSizesInner;
    private ArrayList<String> fieldTypesInner;
    // --
    private boolean differentTypesRet;
    private ArrayList<Integer> fieldSizesRet;
    private ArrayList<String> fieldTypesRet;
    private boolean arrayField;
    private int tupleArrayFieldNo;
    private int arrayFieldTotalBytes;
    private boolean returnArrayField;
    private int returnTupleArrayFieldNo;
    private int returnArrayFieldTotalBytes;
    private String arrayType;
    private int returnTupleSize;
    private int broadcastedArrayFieldTotalBytes;
    private int broadcastedSize;

    private boolean broadcastedDataset;

    static boolean arrayIteration;
    static int arrayFieldIndex;

    static boolean copyArray;
    static boolean twoForLoops;

    void flinkSetCompInfo(FlinkCompilerInfo flinkCompilerInfo) {
        this.differentTypes = flinkCompilerInfo.getDifferentTypes();
        this.fieldSizes = flinkCompilerInfo.getFieldSizes();
        this.fieldTypes = flinkCompilerInfo.getFieldTypes();
        this.differentTypesInner = flinkCompilerInfo.getDifferentTypesInner();
        this.fieldSizesInner = flinkCompilerInfo.getFieldSizesInner();
        this.fieldTypesInner = flinkCompilerInfo.getFieldTypesInner();
        this.differentTypesRet = flinkCompilerInfo.getDifferentTypesRet();
        this.fieldSizesRet = flinkCompilerInfo.getFieldSizesRet();
        this.fieldTypesRet = flinkCompilerInfo.getFieldTypesRet();
        this.broadcastedDataset = flinkCompilerInfo.getBroadcastedDataset();
        this.arrayField = flinkCompilerInfo.getArrayField();
        this.tupleArrayFieldNo = flinkCompilerInfo.getTupleArrayFieldNo();
        this.arrayFieldTotalBytes = flinkCompilerInfo.getArrayFieldTotalBytes();
        this.returnArrayField = flinkCompilerInfo.getReturnArrayField();
        this.returnTupleArrayFieldNo = flinkCompilerInfo.getReturnTupleArrayFieldNo();
        this.returnArrayFieldTotalBytes = flinkCompilerInfo.getReturnArrayFieldTotalBytes();
        this.arrayType = flinkCompilerInfo.getArrayType();
        this.returnTupleSize = flinkCompilerInfo.getReturnTupleSize();
        this.broadcastedArrayFieldTotalBytes = flinkCompilerInfo.getArrayFieldTotalBytes();
        this.broadcastedSize = flinkCompilerInfo.getBroadcastedSize();
    }

    private void retInnerOCL(Node n, ValuePhiNode ph, ArrayList<OCLAddressNode> innerReads, OCLAddressNode ocl) {
        for (Node in : n.inputs()) {
            if (in instanceof AddNode || in instanceof SignExtendNode || in instanceof LeftShiftNode) {
                retInnerOCL(in, ph, innerReads, ocl);
            } else if (in instanceof ValuePhiNode) {
                if (in == ph) {
                    innerReads.add(ocl);
                    return;
                }
            }
        }
    }

    private void returnFieldNumber(Node n, HashMap<Integer, OCLAddressNode> orderedOCL, OCLAddressNode ocl, ValuePhiNode ph) {
        String toBeReturned = null;
        for (Node in : n.inputs()) {
            if (in instanceof SignExtendNode) {
                AddNode ad = (AddNode) in.inputs().first();
                for (Node adin : ad.inputs()) {
                    if (adin instanceof ConstantNode) {
                        ConstantNode c = (ConstantNode) adin;
                        toBeReturned = c.getValue().toValueString();
                        int pos = Integer.parseInt(toBeReturned);
                        orderedOCL.put(pos, ocl);
                        fieldTypesInner.set(pos, "used");
                    }
                }

                if (toBeReturned == null) {
                    orderedOCL.put(0, ocl);
                    fieldTypesInner.set(0, "used");
                }

                return;
            } else {
                returnFieldNumber(in, orderedOCL, ocl, ph);
            }
        }
    }

    private void returnFieldNumberSingleLoop(Node n, HashMap<Integer, OCLAddressNode> orderedOCL, OCLAddressNode ocl, ValuePhiNode ph) {
        String toBeReturned = null;
        for (Node in : n.inputs()) {
            if (in instanceof SignExtendNode) {
                if (in.inputs().first() instanceof AddNode) {
                    AddNode ad = (AddNode) in.inputs().first();
                    for (Node adin : ad.inputs()) {
                        if (adin instanceof ConstantNode) {
                            ConstantNode c = (ConstantNode) adin;
                            toBeReturned = c.getValue().toValueString();
                            int pos = Integer.parseInt(toBeReturned);
                            orderedOCL.put(pos, ocl);
                            fieldTypes.set(pos, "used");
                        }
                    }
                }
                if (toBeReturned == null) {
                    orderedOCL.put(0, ocl);
                    fieldTypes.set(0, "used");
                }

                return;
            } else {
                returnFieldNumberSingleLoop(in, orderedOCL, ocl, ph);
            }
        }
    }

    private void returnFieldNumberSingleLoopWrite(Node n, HashMap<Integer, OCLAddressNode> orderedOCL, OCLAddressNode ocl, ValuePhiNode ph) {
        String toBeReturned = null;
        for (Node in : n.inputs()) {
            if (in instanceof SignExtendNode) {
                if (in.inputs().first() instanceof AddNode) {
                    AddNode ad = (AddNode) in.inputs().first();
                    for (Node adin : ad.inputs()) {
                        if (adin instanceof ConstantNode) {
                            ConstantNode c = (ConstantNode) adin;
                            toBeReturned = c.getValue().toValueString();
                            int pos = Integer.parseInt(toBeReturned);
                            orderedOCL.put(pos, ocl);
                            fieldTypesRet.set(pos, "used");
                        }
                    }
                }
                if (toBeReturned == null) {
                    orderedOCL.put(0, ocl);
                    fieldTypesRet.set(0, "used");
                }

                return;
            } else {
                returnFieldNumberSingleLoopWrite(in, orderedOCL, ocl, ph);
            }
        }
    }

    private void returnFieldNumberMultipleLoops(Node n, HashMap<OCLAddressNode, Integer> orderedOCL, OCLAddressNode ocl, ArrayList<String> fieldTypes) {
        String toBeReturned = null;
        for (Node in : n.inputs()) {
            if (in instanceof SignExtendNode) {
                if (in.inputs().first() instanceof AddNode) {
                    AddNode ad = (AddNode) in.inputs().first();
                    for (Node adin : ad.inputs()) {
                        if (adin instanceof ConstantNode) {
                            ConstantNode c = (ConstantNode) adin;
                            toBeReturned = c.getValue().toValueString();
                            int pos = Integer.parseInt(toBeReturned);
                            orderedOCL.put(ocl, pos);
                            // if (orderedOCL.containsKey(pos)) {
                            // ArrayList<OCLAddressNode> ocls = orderedOCL.get(pos);
                            // ocls.add(ocl);
                            // orderedOCL.put(pos, ocls);
                            // } else {
                            // ArrayList<OCLAddressNode> ocls = new ArrayList<>();
                            // ocls.add(ocl);
                            // orderedOCL.put(pos, ocls);
                            // }
                            fieldTypes.set(pos, "used");
                        }
                    }
                }
                if (toBeReturned == null) {
                    orderedOCL.put(ocl, 0);
                    // if (orderedOCL.containsKey(0)) {
                    // ArrayList<OCLAddressNode> ocls = orderedOCL.get(0);
                    // ocls.add(ocl);
                    // orderedOCL.put(0, ocls);
                    // } else {
                    // ArrayList<OCLAddressNode> ocls = new ArrayList<>();
                    // ocls.add(ocl);
                    // orderedOCL.put(0, ocls);
                    // }
                    fieldTypes.set(0, "used");
                }

                return;
            } else {
                returnFieldNumberMultipleLoops(in, orderedOCL, ocl, fieldTypes);
            }
        }
    }

    private void findPhi(Node n, HashMap<OCLAddressNode, ValuePhiNode> oclPhis, OCLAddressNode ocl) {
        if (n instanceof ValuePhiNode) {
            oclPhis.put(ocl, (ValuePhiNode) n);
            return;
        } else {
            for (Node in : n.inputs()) {
                findPhi(in, oclPhis, ocl);
            }
        }

    }

    private static void identifyNodesToBeDeleted(Node n, HashMap<Node, Integer[]> nodesToBeDeleted) {

        if (n instanceof PhiNode)
            return;

        if (nodesToBeDeleted.containsKey(n)) {
            Integer[] count = nodesToBeDeleted.get(n);
            Integer[] newCount = new Integer[] { count[0]++, count[1] };
            nodesToBeDeleted.replace(n, count, newCount);
        } else {
            // first element of the array is the number of occurrences and the second the
            // number of usages
            Integer[] count = new Integer[] { 1, n.usages().count() };
            nodesToBeDeleted.put(n, count);
        }

        for (Node in : n.inputs()) {
            identifyNodesToBeDeleted(in, nodesToBeDeleted);
        }
    }

    // public static AddNode getAddInput(HashMap<Integer, OCLAddressNode>
    // readAddressNodes, int pos) {
    // AddNode adNode = null;
    // OCLAddressNode ocl = readAddressNodes.get(pos);
    // for (Node in : ocl.inputs()) {
    // if (in instanceof AddNode) {
    // adNode = (AddNode) in;
    // break;
    // }
    // }
    //
    // if (adNode == null) {
    // System.out.println("ERROR: CASE NOT TAKEN INTO ACCOUNT");
    // return null;
    // }
    //
    // return adNode;
    // }

    public static AddNode getAddInput(OCLAddressNode ocl) {
        AddNode adNode = null;

        for (Node in : ocl.inputs()) {
            if (in instanceof AddNode) {
                adNode = (AddNode) in;
                break;
            }
        }

        if (adNode == null) {
            System.out.println("ERROR: CASE NOT TAKEN INTO ACCOUNT");
            return null;
        }

        return adNode;
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

    @Override
    protected void run(StructuredGraph graph) {

        FlinkCompilerInfo fcomp = FlinkCompilerInfoIntermediate.getFlinkCompilerInfo();
        if (fcomp != null) {
            flinkSetCompInfo(fcomp);
        }

        if (arrayField && !copyArray) {
            // sanity check
            if (fieldSizes.size() > 3) {
                System.out.println("[TornadoTupleOffset phase WARNING]: We currently only support up to Tuple3 with array field.");
                return;
            }

            if (arrayIteration) {
                // second for loop to iterate array elements
                if (broadcastedDataset) {
                    if (!returnArrayField) {
                        int tupleSize = fieldSizes.size();
                        int broadTupleSize = fieldSizesInner.size();

                        if (tupleSize == 2 && returnTupleSize == 4) {
                            // exus
                            // ---- READNODES:
                            // Locate Phi Node with input GlobalThreadID
                            ValuePhiNode globalPhi = null;
                            for (Node n : graph.getNodes()) {
                                if (n instanceof ValuePhiNode) {
                                    for (Node in : n.inputs()) {
                                        if (in instanceof GlobalThreadIdNode) {
                                            globalPhi = (ValuePhiNode) n;
                                            break;
                                        }
                                    }
                                }
                                if (globalPhi != null)
                                    break;
                            }
                            // Locate & order read address nodes
                            // HashMap<Integer, ArrayList<OCLAddressNode>> readAddressNodes = new HashMap();
                            HashMap<OCLAddressNode, Integer> readAddressNodes = new HashMap<>();
                            for (Node n : graph.getNodes()) {
                                if (n instanceof FloatingReadNode) {
                                    OCLAddressNode ocl = (OCLAddressNode) n.inputs().first();
                                    returnFieldNumberMultipleLoops(ocl, readAddressNodes, ocl, fieldTypes);
                                }
                            }

                            if (readAddressNodes.size() == 0) {
                                // System.out.println("Oops, no elements in readAddressNodes HashMap!");
                                return;
                            }

                            // Make pairs of <OCLAddress, PhiNode> for Read Nodes
                            HashMap<OCLAddressNode, ValuePhiNode> oclReadPhis = new HashMap<>();
                            for (OCLAddressNode ocl : readAddressNodes.keySet()) {
                                findPhi(ocl, oclReadPhis, ocl);
                            }

                            HashMap<ValuePhiNode, SignExtendNode> signExtOfPhi = new HashMap<>();

                            for (OCLAddressNode oclRead : oclReadPhis.keySet()) {
                                ValuePhiNode ph = oclReadPhis.get(oclRead);
                                SignExtendNode signExt = null;

                                for (Node phUse : ph.usages()) {
                                    if (phUse instanceof SignExtendNode) {
                                        signExt = (SignExtendNode) phUse;
                                    }
                                }

                                if (signExt == null) {
                                    SignExtendNode sgnEx = null;
                                    for (Node phUse : ph.usages()) {
                                        if (phUse instanceof LeftShiftNode) {
                                            LeftShiftNode lsh = (LeftShiftNode) phUse;
                                            for (Node lshUse : lsh.usages()) {
                                                if (lshUse instanceof SignExtendNode) {
                                                    sgnEx = (SignExtendNode) lshUse;
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                    if (sgnEx == null) {
                                        return;
                                    } else {
                                        signExt = (SignExtendNode) sgnEx.copyWithInputs();
                                        for (Node in : signExt.inputs()) {
                                            signExt.replaceFirstInput(in, ph);
                                        }
                                    }

                                    if (signExt == null) {
                                        System.out.println("NO SIGNEXTEND AFTER PHI!!!");
                                        return;
                                    }

                                    signExtOfPhi.put(ph, signExt);
                                }
                            }
                            // CASE: ReadNode paired with array Phi node
                            for (OCLAddressNode readAddress : readAddressNodes.keySet()) {
                                if (readAddressNodes.get(readAddress) == arrayFieldIndex && !(oclReadPhis.get(readAddress) == globalPhi)) {
                                    ValuePhiNode innerLoopPhi = oclReadPhis.get(readAddress);
                                    boolean broadcasted = false;
                                    for (Node in : readAddress.inputs()) {
                                        if (in instanceof ParameterNode) {
                                            if (in.toString().contains("(2)")) {
                                                broadcasted = true;
                                            }
                                        }
                                    }

                                    if (tupleArrayFieldNo == 0) {
                                        // if the first field of the Tuple3 is an array
                                        // ----- Access Field 0
                                        AddNode adNode = getAddInput(readAddress);
                                        HashMap<Node, Integer[]> nodesToBeDeleted = new HashMap<>();

                                        int numOfOCL = 0;
                                        for (Node addUse : adNode.usages()) {
                                            if (addUse instanceof OCLAddressNode) {
                                                numOfOCL++;
                                            }
                                        }
                                        AddNode adNode0;
                                        if (numOfOCL > 1) {
                                            adNode0 = (AddNode) adNode.copyWithInputs();

                                            FloatingReadNode fr = null;
                                            for (Node us : readAddress.usages()) {
                                                if (us instanceof FloatingReadNode) {
                                                    fr = (FloatingReadNode) us;
                                                }
                                            }

                                            OCLAddressNode ocln = (OCLAddressNode) readAddress.copyWithInputs();
                                            ocln.replaceFirstInput(adNode, adNode0);
                                            if (fr != null) {
                                                fr.replaceFirstInput(readAddress, ocln);
                                            } else {
                                                System.out.println("ReadNode is NULL");
                                            }

                                        } else {
                                            adNode0 = adNode;
                                        }

                                        Node adInput0 = null;

                                        for (Node adin : adNode0.inputs()) {
                                            if (!(adin instanceof ConstantNode)) {
                                                adInput0 = adin;
                                            }
                                        }

                                        if (adInput0 != null) {
                                            identifyNodesToBeDeleted(adInput0, nodesToBeDeleted);
                                        }

                                        // new nodes
                                        Constant arrayFieldSizeConst;
                                        if (broadcasted) {
                                            if (differentTypesInner) {
                                                // padding
                                                arrayFieldSizeConst = new RawConstant(8);
                                            } else {
                                                arrayFieldSizeConst = new RawConstant(fieldSizesInner.get(0));
                                            }
                                        } else {
                                            if (differentTypes) {
                                                // padding
                                                arrayFieldSizeConst = new RawConstant(8);
                                            } else {
                                                arrayFieldSizeConst = new RawConstant(fieldSizes.get(0));
                                            }
                                        }

                                        ConstantNode arrayFieldSize = new ConstantNode(arrayFieldSizeConst, StampFactory.positiveInt());
                                        graph.addWithoutUnique(arrayFieldSize);

                                        SignExtendNode sn = null;

                                        for (Node us : innerLoopPhi.usages()) {
                                            if (us instanceof SignExtendNode) {
                                                sn = (SignExtendNode) us;
                                            }
                                        }

                                        MulNode m = new MulNode(arrayFieldSize, sn);
                                        graph.addWithoutUnique(m);

                                        int sizeOfFields;

                                        if (broadcasted) {
                                            int numOfBroadFields = fieldSizesInner.size();
                                            if (differentTypesInner) {
                                                sizeOfFields = broadcastedArrayFieldTotalBytes + (numOfBroadFields - 1) * 8;
                                            } else {
                                                int sizeOfNumericFields = 0;
                                                for (int i = 1; i < numOfBroadFields; i++) {
                                                    sizeOfNumericFields += fieldSizesInner.get(i);
                                                }
                                                sizeOfFields = broadcastedArrayFieldTotalBytes + sizeOfNumericFields;
                                            }
                                        } else {
                                            if (differentTypes) {
                                                sizeOfFields = arrayFieldTotalBytes + 8;
                                            } else {
                                                sizeOfFields = arrayFieldTotalBytes + fieldSizes.get(1);
                                            }
                                        }

                                        Constant fieldsSizeConst = new RawConstant(sizeOfFields);
                                        ConstantNode fieldsSize = new ConstantNode(fieldsSizeConst, StampFactory.positiveInt());
                                        graph.addWithoutUnique(fieldsSize);
                                        System.out.println("- Back it up");
                                        MulNode m2;
                                        if (broadcasted && broadcastedSize == 1) {
                                            Constant oneIterationConst = new RawConstant(0);
                                            ConstantNode oneIter = new ConstantNode(oneIterationConst, StampFactory.positiveInt());
                                            graph.addWithoutUnique(oneIter);
                                            m2 = new MulNode(oneIter, fieldsSize);
                                        } else {
                                            SignExtendNode signExt = signExtOfPhi.get(globalPhi);
                                            m2 = new MulNode(signExt, fieldsSize);
                                        }
                                        graph.addWithoutUnique(m2);

                                        AddNode addOffset0 = new AddNode(m, m2);
                                        graph.addWithoutUnique(addOffset0);

                                        adNode0.replaceFirstInput(adInput0, addOffset0);
                                    }

                                } else if (readAddressNodes.get(readAddress) == 1) {
                                    // -- else code for accessing second Tuple field
                                    // CASE: ReadNode paired with other Phi Node
                                    // -- if index == array_index
                                    // -- 24 + 8*phi + (f1 +f2)*GlobalPhi

                                    // ----- Access Field 1
                                    ValuePhiNode innerLoopPhi = oclReadPhis.get(readAddress);
                                    boolean broadcasted = false;
                                    for (Node in : readAddress.inputs()) {
                                        if (in instanceof ParameterNode) {
                                            if (in.toString().contains("(2)")) {
                                                broadcasted = true;
                                            }
                                        }
                                    }

                                    AddNode adNode1 = getAddInput(readAddress);
                                    HashMap<Node, Integer[]> nodesToBeDeleted = new HashMap<>();

                                    int numOfOCL = 0;

                                    Node adInput1 = null;
                                    for (Node adin : adNode1.inputs()) {
                                        if (!(adin instanceof ConstantNode)) {
                                            adInput1 = adin;
                                        }
                                    }

                                    if (adInput1 != null && numOfOCL == 1) {
                                        identifyNodesToBeDeleted(adInput1, nodesToBeDeleted);
                                    }

                                    Constant field0SizeConst = new RawConstant(arrayFieldTotalBytes);
                                    ConstantNode field0Size = new ConstantNode(field0SizeConst, StampFactory.positiveInt());
                                    graph.addWithoutUnique(field0Size);

                                    SignExtendNode signExt = null;

                                    for (Node us : innerLoopPhi.usages()) {
                                        if (us instanceof SignExtendNode) {
                                            signExt = (SignExtendNode) us;
                                        }
                                    }

                                    int sizeOfFields;
                                    if (broadcasted) {
                                        int numOfBroadFields = fieldSizesInner.size();
                                        if (differentTypesInner) {
                                            sizeOfFields = broadcastedArrayFieldTotalBytes + (numOfBroadFields - 1) * 8;
                                        } else {
                                            int sizeOfNumericFields = 0;
                                            for (int i = 1; i < numOfBroadFields; i++) {
                                                sizeOfNumericFields += fieldSizesInner.get(i);
                                            }
                                            sizeOfFields = broadcastedArrayFieldTotalBytes + sizeOfNumericFields;
                                        }
                                    } else {
                                        if (differentTypes) {
                                            sizeOfFields = arrayFieldTotalBytes + 8;
                                        } else {
                                            sizeOfFields = arrayFieldTotalBytes + fieldSizes.get(1);
                                        }
                                    }

                                    Constant fieldsSizeConst = new RawConstant(sizeOfFields);
                                    ConstantNode fieldsSize = new ConstantNode(fieldsSizeConst, StampFactory.positiveInt());
                                    graph.addWithoutUnique(fieldsSize);
                                    MulNode m3;
                                    if (broadcasted && broadcastedSize == 1) {
                                        Constant oneIterationConst = new RawConstant(0);
                                        ConstantNode oneIter = new ConstantNode(oneIterationConst, StampFactory.positiveInt());
                                        graph.addWithoutUnique(oneIter);
                                        m3 = new MulNode(oneIter, fieldsSize);
                                    } else {
                                        m3 = new MulNode(signExt, fieldsSize);
                                    }
                                    graph.addWithoutUnique(m3);
                                    AddNode addOffset1 = new AddNode(field0Size, m3);
                                    graph.addWithoutUnique(addOffset1);

                                    adNode1.replaceFirstInput(adInput1, addOffset1);

                                    if (numOfOCL == 1) {
                                        for (Node n : nodesToBeDeleted.keySet()) {
                                            Integer[] count = nodesToBeDeleted.get(n);
                                            // if the usages are as many as the occurrences delete
                                            if (count[0] == count[1]) {
                                                // System.out.println("= DELETE " + n);
                                                n.safeDelete();
                                            }
                                        }
                                    }
                                }

                            }
                        }
                        if (!differentTypesRet)
                            return;
                    } else {
                        // TODO: implement else
                    }
                }
                // TODO: Implement else
            } else {
                HashMap<Integer, OCLAddressNode> readAddressNodes = new HashMap();

                ValuePhiNode ph = null;
                SignExtendNode signExt = null;

                for (Node n : graph.getNodes()) {
                    if (n instanceof ValuePhiNode) {
                        ph = (ValuePhiNode) n;
                    }
                }

                if (ph == null)
                    return;

                for (Node phUse : ph.usages()) {
                    if (phUse instanceof SignExtendNode) {
                        signExt = (SignExtendNode) phUse;
                    }
                }

                if (signExt == null) {
                    SignExtendNode sgnEx = null;
                    for (Node phUse : ph.usages()) {
                        if (phUse instanceof LeftShiftNode) {
                            LeftShiftNode lsh = (LeftShiftNode) phUse;
                            for (Node lshUse : lsh.usages()) {
                                if (lshUse instanceof SignExtendNode) {
                                    sgnEx = (SignExtendNode) lshUse;
                                    break;
                                }
                            }
                        }
                    }
                    if (sgnEx == null) {
                        return;
                    } else {
                        signExt = (SignExtendNode) sgnEx.copyWithInputs();
                        for (Node in : signExt.inputs()) {
                            signExt.replaceFirstInput(in, ph);
                        }
                    }

                    if (signExt == null) {
                        System.out.println("NO SIGNEXTEND AFTER PHI!!!");
                        return;
                    }
                }

                for (Node n : graph.getNodes()) {
                    if (n instanceof FloatingReadNode /*
                                                       * && !((FloatingReadNode)
                                                       * n).stamp(NodeView.DEFAULT).toString().contains("Lorg/apache/flink/")
                                                       */) {
                        OCLAddressNode ocl = (OCLAddressNode) n.inputs().first();
                        returnFieldNumberSingleLoop(ocl, readAddressNodes, ocl, ph);
                    }
                }

                if (readAddressNodes.size() == 0) {
                    // System.out.println("Oops, no elements in readAddressNodes HashMap!");
                    return;
                }
                int tupleSize = fieldSizes.size();
                HashMap<Node, Integer[]> nodesToBeDeleted = new HashMap<>();

                if (tupleSize == 2) {
                    // CASE: Tuple2
                    if (tupleArrayFieldNo == 0) {
                        // if the first field of the Tuple2 is an array
                        // ----- Access Field 0
                        OCLAddressNode ocl = readAddressNodes.get(0);
                        AddNode adNode = getAddInput(ocl);

                        int numOfOCL = 0;
                        for (Node addUse : adNode.usages()) {
                            if (addUse instanceof OCLAddressNode) {
                                numOfOCL++;
                            }
                        }
                        AddNode adNode0;
                        if (numOfOCL > 1) {
                            adNode0 = (AddNode) adNode.copyWithInputs();
                            OCLAddressNode ocl1 = readAddressNodes.get(0);
                            FloatingReadNode fr = null;
                            for (Node us : ocl1.usages()) {
                                if (us instanceof FloatingReadNode) {
                                    fr = (FloatingReadNode) us;
                                }
                            }

                            OCLAddressNode ocln = (OCLAddressNode) ocl1.copyWithInputs();
                            ocln.replaceFirstInput(adNode, adNode0);
                            if (fr != null) {
                                fr.replaceFirstInput(ocl1, ocln);
                            } else {
                                System.out.println("Floating Read Node is NULL");
                            }
                            // update hashmap
                            readAddressNodes.replace(0, ocl1, ocln);
                            // delete old ocl node
                            ocl1.safeDelete();
                        } else {
                            adNode0 = adNode;
                        }

                        Node adInput0 = null;
                        for (Node adin : adNode0.inputs()) {
                            if (!(adin instanceof ConstantNode)) {
                                adInput0 = adin;
                            }
                        }

                        if (adInput0 != null && numOfOCL == 1) {
                            identifyNodesToBeDeleted(adInput0, nodesToBeDeleted);
                        }
                        // new nodes
                        Constant arrayFieldSizeConst;
                        if (differentTypes) {
                            // padding
                            arrayFieldSizeConst = new RawConstant(8);
                        } else {
                            arrayFieldSizeConst = new RawConstant(fieldSizes.get(0));
                        }
                        ConstantNode arrayFieldSize = new ConstantNode(arrayFieldSizeConst, StampFactory.positiveInt());
                        graph.addWithoutUnique(arrayFieldSize);
                        Constant arrayIndexConst = new RawConstant(arrayFieldIndex);
                        ConstantNode arrayIndex = new ConstantNode(arrayIndexConst, StampFactory.positiveInt());
                        graph.addWithoutUnique(arrayIndex);
                        MulNode m = new MulNode(arrayFieldSize, arrayIndex);
                        graph.addWithoutUnique(m);

                        int sizeOfFields;

                        if (differentTypes) {
                            sizeOfFields = arrayFieldTotalBytes + 8;
                        } else {
                            sizeOfFields = arrayFieldTotalBytes + fieldSizes.get(1);
                        }

                        Constant fieldsSizeConst = new RawConstant(sizeOfFields);
                        ConstantNode fieldsSize = new ConstantNode(fieldsSizeConst, StampFactory.positiveInt());
                        graph.addWithoutUnique(fieldsSize);
                        MulNode m2 = new MulNode(fieldsSize, signExt);
                        graph.addWithoutUnique(m2);

                        AddNode addOffset0 = new AddNode(m, m2);
                        graph.addWithoutUnique(addOffset0);

                        adNode0.replaceFirstInput(adInput0, addOffset0);

                        // ----- Access Field 1
                        OCLAddressNode ocl1 = readAddressNodes.get(1);
                        AddNode adNode1 = getAddInput(ocl1);

                        Node adInput1 = null;
                        for (Node adin : adNode1.inputs()) {
                            if (!(adin instanceof ConstantNode)) {
                                adInput1 = adin;
                            }
                        }

                        if (adInput1 != null && numOfOCL == 1) {
                            identifyNodesToBeDeleted(adInput1, nodesToBeDeleted);
                        }

                        Constant field0SizeConst = new RawConstant(arrayFieldTotalBytes);
                        ConstantNode field0Size = new ConstantNode(field0SizeConst, StampFactory.positiveInt());
                        graph.addWithoutUnique(field0Size);

                        MulNode m3 = new MulNode(signExt, fieldsSize);
                        graph.addWithoutUnique(m3);
                        AddNode addOffset1 = new AddNode(field0Size, m3);
                        graph.addWithoutUnique(addOffset1);

                        adNode1.replaceFirstInput(adInput1, addOffset1);

                        if (numOfOCL == 1) {
                            for (Node n : nodesToBeDeleted.keySet()) {
                                Integer[] count = nodesToBeDeleted.get(n);
                                // if the usages are as many as the occurrences delete
                                if (count[0] == count[1]) {
                                    // System.out.println("= DELETE " + n);
                                    n.safeDelete();
                                }
                            }
                        }
                        if (!differentTypesRet && !returnArrayField) {
                            // System.out.println("differentTypesRet: " + differentTypesRet + "
                            // returnArrayField: " + returnArrayField);
                            return;
                        }
                        // return;

                    } else if (tupleArrayFieldNo == 1) {
                        // if the second field of the Tuple2 is an array
                        // ----- Access Field 0
                        OCLAddressNode ocl = readAddressNodes.get(0);
                        AddNode adNode = getAddInput(ocl);

                        Node adInput0 = null;
                        for (Node adin : adNode.inputs()) {
                            if (!(adin instanceof ConstantNode)) {
                                adInput0 = adin;
                            }
                        }

                        int numOfOCL = 0;
                        for (Node addUse : adNode.usages()) {
                            if (addUse instanceof OCLAddressNode) {
                                numOfOCL++;
                            }
                        }
                        AddNode adNode0;
                        if (numOfOCL > 1) {
                            // System.out.println("More than one OCLNode");
                            adNode0 = (AddNode) adNode.copyWithInputs();
                        } else {
                            adNode0 = adNode;
                        }

                        if (adInput0 != null) {
                            identifyNodesToBeDeleted(adInput0, nodesToBeDeleted);
                        }

                        int sizeOfFields;

                        if (differentTypes) {
                            sizeOfFields = arrayFieldTotalBytes + 8;
                        } else {
                            sizeOfFields = arrayFieldTotalBytes + fieldSizes.get(0);
                        }

                        Constant fieldsSizeConst = new RawConstant(sizeOfFields);
                        ConstantNode fieldsSize = new ConstantNode(fieldsSizeConst, StampFactory.positiveInt());
                        graph.addWithoutUnique(fieldsSize);

                        MulNode multOffset0 = new MulNode(signExt, fieldsSize);
                        graph.addWithoutUnique(multOffset0);

                        adNode0.replaceFirstInput(adInput0, multOffset0);

                        // ----- Access Field 1
                        OCLAddressNode ocl1 = readAddressNodes.get(1);
                        AddNode adNode1 = getAddInput(ocl1);

                        Node adInput1 = null;
                        for (Node adin : adNode1.inputs()) {
                            if (!(adin instanceof ConstantNode)) {
                                adInput1 = adin;
                            }
                        }

                        if (adInput1 != null) {
                            identifyNodesToBeDeleted(adInput1, nodesToBeDeleted);
                        }

                        // new nodes
                        Constant arrayFieldSizeConst;
                        if (differentTypes) {
                            // padding
                            arrayFieldSizeConst = new RawConstant(8);
                        } else {
                            arrayFieldSizeConst = new RawConstant(fieldSizes.get(1));
                        }
                        ConstantNode arrayFieldSize = new ConstantNode(arrayFieldSizeConst, StampFactory.positiveInt());
                        graph.addWithoutUnique(arrayFieldSize);

                        Constant arrayIndexConst = new RawConstant(arrayFieldIndex);
                        ConstantNode arrayIndex = new ConstantNode(arrayIndexConst, StampFactory.positiveInt());
                        graph.addWithoutUnique(arrayIndex);

                        MulNode m = new MulNode(arrayFieldSize, arrayIndex);
                        graph.addWithoutUnique(m);

                        MulNode m2 = new MulNode(fieldsSize, signExt);
                        graph.addWithoutUnique(m2);

                        AddNode addNode = new AddNode(m, m2);
                        graph.addWithoutUnique(addNode);

                        Constant field0SizeConst;
                        if (differentTypes) {
                            field0SizeConst = new RawConstant(8);
                        } else {
                            field0SizeConst = new RawConstant(fieldSizes.get(0));
                        }
                        ConstantNode field0Size = new ConstantNode(field0SizeConst, StampFactory.positiveInt());
                        graph.addWithoutUnique(field0Size);

                        AddNode addOffset1 = new AddNode(field0Size, addNode);
                        graph.addWithoutUnique(addOffset1);

                        adNode1.replaceFirstInput(adInput1, addOffset1);

                        for (Node n : nodesToBeDeleted.keySet()) {
                            Integer[] count = nodesToBeDeleted.get(n);
                            // if the usages are as many as the occurrences delete
                            // System.out.println("DELETE " + n);
                            if (count[0] == count[1]) {
                                n.safeDelete();
                            }
                        }
                        // if (!differentTypesRet) {
                        return;
                        // }
                    }
                } else if (tupleSize == 3) {
                    // CASE: Tuple3
                    if (tupleArrayFieldNo == 0) {
                        // if the first field of the Tuple3 is an array
                        // ----- Access Field 0
                        OCLAddressNode ocl = readAddressNodes.get(0);
                        AddNode adNode0 = getAddInput(ocl);

                        Node adInput0 = null;
                        for (Node adin : adNode0.inputs()) {
                            if (!(adin instanceof ConstantNode)) {
                                adInput0 = adin;
                            }
                        }

                        if (adInput0 != null) {
                            identifyNodesToBeDeleted(adInput0, nodesToBeDeleted);
                        }

                        // new nodes
                        Constant arrayFieldSizeConst;
                        if (differentTypes) {
                            // padding
                            arrayFieldSizeConst = new RawConstant(8);
                        } else {
                            arrayFieldSizeConst = new RawConstant(fieldSizes.get(0));
                        }
                        ConstantNode arrayFieldSize = new ConstantNode(arrayFieldSizeConst, StampFactory.positiveInt());
                        graph.addWithoutUnique(arrayFieldSize);

                        Constant arrayIndexConst = new RawConstant(arrayFieldIndex);
                        ConstantNode arrayIndex = new ConstantNode(arrayIndexConst, StampFactory.positiveInt());
                        graph.addWithoutUnique(arrayIndex);

                        MulNode m = new MulNode(arrayFieldSize, arrayIndex);
                        graph.addWithoutUnique(m);

                        int sizeOfFields;

                        if (differentTypes) {
                            sizeOfFields = arrayFieldTotalBytes + 8 + 8;
                        } else {
                            sizeOfFields = arrayFieldTotalBytes + fieldSizes.get(1) + fieldSizes.get(2);
                        }

                        Constant fieldsSizeConst = new RawConstant(sizeOfFields);
                        ConstantNode fieldsSize = new ConstantNode(fieldsSizeConst, StampFactory.positiveInt());
                        graph.addWithoutUnique(fieldsSize);

                        MulNode m2 = new MulNode(signExt, fieldsSize);
                        graph.addWithoutUnique(m2);

                        AddNode addOffset0 = new AddNode(m, m2);
                        graph.addWithoutUnique(addOffset0);

                        adNode0.replaceFirstInput(adInput0, addOffset0);

                        // ------ Access Field 1
                        OCLAddressNode ocl1 = readAddressNodes.get(1);
                        AddNode adNode1 = getAddInput(ocl1);

                        Node adInput1 = null;
                        for (Node adin : adNode1.inputs()) {
                            if (!(adin instanceof ConstantNode)) {
                                adInput1 = adin;
                            }
                        }

                        if (adInput1 != null) {
                            identifyNodesToBeDeleted(adInput1, nodesToBeDeleted);
                        }

                        // new nodes
                        Constant field0SizeConst = new RawConstant(arrayFieldTotalBytes);
                        ConstantNode field0Size = new ConstantNode(field0SizeConst, StampFactory.positiveInt());
                        graph.addWithoutUnique(field0Size);

                        AddNode addOffset1 = new AddNode(m2, field0Size);
                        graph.addWithoutUnique(addOffset1);

                        adNode1.replaceFirstInput(adInput1, addOffset1);

                        // ----- Access Field 2
                        OCLAddressNode ocl2 = readAddressNodes.get(2);
                        AddNode adNode2 = getAddInput(ocl2);

                        Node adInput2 = null;
                        for (Node adin : adNode2.inputs()) {
                            if (!(adin instanceof ConstantNode)) {
                                adInput2 = adin;
                            }
                        }

                        if (adInput2 != null) {
                            identifyNodesToBeDeleted(adInput2, nodesToBeDeleted);
                        }

                        // new nodes
                        int sizeOfField0Field1;
                        // new nodes
                        if (differentTypes) {
                            sizeOfField0Field1 = arrayFieldTotalBytes + 8;
                        } else {
                            sizeOfField0Field1 = arrayFieldTotalBytes + fieldSizes.get(1);
                        }

                        Constant fields0plus1SizeConst = new RawConstant(sizeOfField0Field1);
                        ConstantNode fields01Size = new ConstantNode(fields0plus1SizeConst, StampFactory.positiveInt());
                        graph.addWithoutUnique(fields01Size);

                        AddNode addOffset2 = new AddNode(fields01Size, m2);
                        graph.addWithoutUnique(addOffset2);
                        adNode2.replaceFirstInput(adInput2, addOffset2);

                        for (Node n : nodesToBeDeleted.keySet()) {
                            Integer[] count = nodesToBeDeleted.get(n);
                            // if the usages are as many as the occurrences delete
                            if (count[0] == count[1]) {
                                n.safeDelete();
                            }
                        }

                        return;

                    } else if (tupleArrayFieldNo == 1) {
                        // if the second field of the Tuple3 is an array
                        // ----- Access Field 0
                        OCLAddressNode ocl = readAddressNodes.get(0);
                        AddNode adNode = getAddInput(ocl);
                        int numOfOCL = 0;
                        for (Node addUse : adNode.usages()) {
                            if (addUse instanceof OCLAddressNode) {
                                numOfOCL++;
                            }
                        }
                        AddNode adNode0;
                        if (numOfOCL > 1) {
                            // System.out.println("More than one OCLNode");
                            adNode0 = (AddNode) adNode.copyWithInputs();
                        } else {
                            adNode0 = adNode;
                        }

                        Node adInput0 = null;
                        for (Node adin : adNode0.inputs()) {
                            if (!(adin instanceof ConstantNode)) {
                                adInput0 = adin;
                            }
                        }

                        if (adInput0 != null) {
                            identifyNodesToBeDeleted(adInput0, nodesToBeDeleted);
                        }

                        // new nodes
                        int sizeOfFields;

                        if (differentTypes) {
                            sizeOfFields = arrayFieldTotalBytes + 8 + 8;
                        } else {
                            sizeOfFields = arrayFieldTotalBytes + fieldSizes.get(0) + fieldSizes.get(2);
                        }

                        Constant fieldsSizeConst = new RawConstant(sizeOfFields);
                        ConstantNode fieldsSize = new ConstantNode(fieldsSizeConst, StampFactory.positiveInt());
                        graph.addWithoutUnique(fieldsSize);

                        MulNode multOffset0 = new MulNode(signExt, fieldsSize);
                        graph.addWithoutUnique(multOffset0);

                        adNode0.replaceFirstInput(adInput0, multOffset0);

                        // ----- Access Field 1
                        OCLAddressNode ocl1 = readAddressNodes.get(1);
                        AddNode adNode1 = getAddInput(ocl1);

                        Node adInput1 = null;
                        for (Node adin : adNode1.inputs()) {
                            if (!(adin instanceof ConstantNode)) {
                                adInput1 = adin;
                            }
                        }

                        if (adInput1 != null) {
                            identifyNodesToBeDeleted(adInput1, nodesToBeDeleted);
                        }

                        // new nodes
                        Constant field0SizeConst;
                        if (differentTypes) {
                            field0SizeConst = new RawConstant(8);
                        } else {
                            field0SizeConst = new RawConstant(fieldSizes.get(0));
                        }
                        ConstantNode field0Size = new ConstantNode(field0SizeConst, StampFactory.positiveInt());
                        graph.addWithoutUnique(field0Size);

                        Constant arrayFieldSizeConst;
                        if (differentTypes) {
                            // padding
                            arrayFieldSizeConst = new RawConstant(8);
                        } else {
                            arrayFieldSizeConst = new RawConstant(fieldSizes.get(1));
                        }
                        ConstantNode arrayFieldSize = new ConstantNode(arrayFieldSizeConst, StampFactory.positiveInt());
                        graph.addWithoutUnique(arrayFieldSize);

                        Constant arrayIndexConst = new RawConstant(arrayFieldIndex);
                        ConstantNode arrayIndex = new ConstantNode(arrayIndexConst, StampFactory.positiveInt());
                        graph.addWithoutUnique(arrayIndex);

                        MulNode m = new MulNode(arrayFieldSize, arrayIndex);
                        graph.addWithoutUnique(m);

                        AddNode adn = new AddNode(field0Size, m);
                        graph.addWithoutUnique(adn);

                        AddNode addOffset1 = new AddNode(multOffset0, adn);
                        graph.addWithoutUnique(addOffset1);

                        adNode1.replaceFirstInput(adInput1, addOffset1);

                        // ----- Access Field 2
                        OCLAddressNode ocl2 = readAddressNodes.get(2);
                        AddNode adNode2 = getAddInput(ocl2);

                        Node adInput2 = null;
                        for (Node adin : adNode2.inputs()) {
                            if (!(adin instanceof ConstantNode)) {
                                adInput2 = adin;
                            }
                        }

                        if (adInput2 != null) {
                            identifyNodesToBeDeleted(adInput2, nodesToBeDeleted);
                        }

                        int sizeOfField0Field1;
                        // new nodes
                        if (differentTypes) {
                            sizeOfField0Field1 = arrayFieldTotalBytes + 8;
                        } else {
                            sizeOfField0Field1 = arrayFieldTotalBytes + fieldSizes.get(0);
                        }

                        Constant fields0plus1SizeConst = new RawConstant(sizeOfField0Field1);
                        ConstantNode fields01Size = new ConstantNode(fields0plus1SizeConst, StampFactory.positiveInt());
                        graph.addWithoutUnique(fields01Size);

                        AddNode addOffset2 = new AddNode(fields01Size, multOffset0);
                        graph.addWithoutUnique(addOffset2);

                        adNode2.replaceFirstInput(adInput2, addOffset2);

                        for (Node n : nodesToBeDeleted.keySet()) {
                            Integer[] count = nodesToBeDeleted.get(n);
                            // if the usages are as many as the occurrences delete
                            if (count[0] == count[1]) {
                                n.safeDelete();
                            }
                        }

                        return;

                    } else if (tupleArrayFieldNo == 2) {
                        // if the third field of the Tuple3 is an array
                        OCLAddressNode ocl = readAddressNodes.get(0);
                        AddNode adNode0 = getAddInput(ocl);

                        Node adInput0 = null;
                        for (Node adin : adNode0.inputs()) {
                            if (!(adin instanceof ConstantNode)) {
                                adInput0 = adin;
                            }
                        }

                        if (adInput0 != null) {
                            identifyNodesToBeDeleted(adInput0, nodesToBeDeleted);
                        }

                        // new nodes
                        int sizeOfFields;

                        if (differentTypes) {
                            sizeOfFields = arrayFieldTotalBytes + 8 + 8;
                        } else {
                            sizeOfFields = arrayFieldTotalBytes + fieldSizes.get(0) + fieldSizes.get(1);
                        }

                        Constant fieldsSizeConst = new RawConstant(sizeOfFields);
                        ConstantNode fieldsSize = new ConstantNode(fieldsSizeConst, StampFactory.positiveInt());
                        graph.addWithoutUnique(fieldsSize);

                        MulNode multOffset0 = new MulNode(signExt, fieldsSize);
                        graph.addWithoutUnique(multOffset0);

                        adNode0.replaceFirstInput(adInput0, multOffset0);

                        // ------ Access Field 1
                        OCLAddressNode ocl1 = readAddressNodes.get(1);
                        AddNode adNode1 = getAddInput(ocl1);

                        Node adInput1 = null;
                        for (Node adin : adNode1.inputs()) {
                            if (!(adin instanceof ConstantNode)) {
                                adInput1 = adin;
                            }
                        }

                        if (adInput1 != null) {
                            identifyNodesToBeDeleted(adInput1, nodesToBeDeleted);
                        }

                        // new nodes
                        Constant field0SizeConst;
                        if (differentTypes) {
                            field0SizeConst = new RawConstant(8);
                        } else {
                            field0SizeConst = new RawConstant(fieldSizes.get(0));
                        }
                        ConstantNode field0Size = new ConstantNode(field0SizeConst, StampFactory.positiveInt());
                        graph.addWithoutUnique(field0Size);

                        AddNode addOffset1 = new AddNode(fieldsSize, field0Size);
                        graph.addWithoutUnique(addOffset1);

                        adNode1.replaceFirstInput(adInput1, addOffset1);

                        // ----- Access Field 2
                        OCLAddressNode ocl2 = readAddressNodes.get(2);
                        AddNode adNode2 = getAddInput(ocl2);

                        Node adInput2 = null;
                        for (Node adin : adNode2.inputs()) {
                            if (!(adin instanceof ConstantNode)) {
                                adInput2 = adin;
                            }
                        }

                        if (adInput2 != null) {
                            identifyNodesToBeDeleted(adInput2, nodesToBeDeleted);
                        }

                        // new nodes
                        Constant arrayFieldSizeConst;
                        if (differentTypes) {
                            // padding
                            arrayFieldSizeConst = new RawConstant(8);
                        } else {
                            arrayFieldSizeConst = new RawConstant(fieldSizes.get(2));
                        }
                        ConstantNode arrayFieldSize = new ConstantNode(arrayFieldSizeConst, StampFactory.positiveInt());
                        graph.addWithoutUnique(arrayFieldSize);

                        Constant arrayIndexConst = new RawConstant(arrayFieldIndex);
                        ConstantNode arrayIndex = new ConstantNode(arrayIndexConst, StampFactory.positiveInt());
                        graph.addWithoutUnique(arrayIndex);

                        MulNode m = new MulNode(arrayFieldSize, arrayIndex);
                        graph.addWithoutUnique(m);

                        AddNode adNode = new AddNode(m, fieldsSize);
                        graph.addWithoutUnique(adNode);

                        int sizeOfField0Field1;

                        if (differentTypes) {
                            sizeOfField0Field1 = 8 + 8;
                        } else {
                            sizeOfField0Field1 = fieldSizes.get(0) + fieldSizes.get(1);
                        }

                        Constant fields0plus1SizeConst = new RawConstant(sizeOfField0Field1);
                        ConstantNode fields01Size = new ConstantNode(fields0plus1SizeConst, StampFactory.positiveInt());
                        graph.addWithoutUnique(fields01Size);

                        AddNode addOffset2 = new AddNode(adNode, fields01Size);
                        graph.addWithoutUnique(addOffset2);

                        adNode2.replaceFirstInput(adInput2, addOffset2);

                        for (Node n : nodesToBeDeleted.keySet()) {
                            Integer[] count = nodesToBeDeleted.get(n);
                            // if the usages are as many as the occurrences delete
                            if (count[0] == count[1]) {
                                n.safeDelete();
                            }
                        }

                        return;

                    }
                }
            }
            // return;
        }

        if (returnArrayField && !copyArray) {
            //
            HashMap<Integer, OCLAddressNode> writeAddressNodes = new HashMap();

            ValuePhiNode ph = null;
            SignExtendNode signExt = null;

            for (Node n : graph.getNodes()) {
                if (n instanceof ValuePhiNode) {
                    ph = (ValuePhiNode) n;
                }
            }

            if (ph == null)
                return;

            for (Node phUse : ph.usages()) {
                if (phUse instanceof SignExtendNode) {
                    signExt = (SignExtendNode) phUse;
                }
            }

            if (signExt == null) {
                SignExtendNode sgnEx = null;
                for (Node phUse : ph.usages()) {
                    if (phUse instanceof LeftShiftNode) {
                        LeftShiftNode lsh = (LeftShiftNode) phUse;
                        for (Node lshUse : lsh.usages()) {
                            if (lshUse instanceof SignExtendNode) {
                                sgnEx = (SignExtendNode) lshUse;
                                break;
                            }
                        }
                    }
                }
                if (sgnEx == null) {
                    return;
                } else {
                    signExt = (SignExtendNode) sgnEx.copyWithInputs();
                    for (Node in : signExt.inputs()) {
                        signExt.replaceFirstInput(in, ph);
                    }
                }

                if (signExt == null) {
                    System.out.println("NO SIGNEXTEND AFTER PHI!!!");
                    return;
                }
            }

            for (Node n : graph.getNodes()) {
                if (n instanceof WriteNode) {
                    OCLAddressNode ocl = (OCLAddressNode) n.inputs().first();
                    returnFieldNumberSingleLoop(ocl, writeAddressNodes, ocl, ph);
                }
            }

            if (writeAddressNodes.size() == 0) {
                // System.out.println("Oops, no elements in readAddressNodes HashMap!");
                return;
            }

            int tupleSize = fieldSizesRet.size();

            HashMap<Node, Integer[]> nodesToBeDeleted = new HashMap<>();

            if (tupleSize == 2) {
                // CASE: Tuple2
                if (returnTupleArrayFieldNo == 0) {
                    // if the first field of the Tuple2 is an array
                    // ----- Access Field 0
                    OCLAddressNode oclw = writeAddressNodes.get(0);
                    AddNode adNode = getAddInput(oclw);

                    int numOfOCL = 0;
                    for (Node addUse : adNode.usages()) {
                        if (addUse instanceof OCLAddressNode) {
                            numOfOCL++;
                        }
                    }
                    AddNode adNode0;
                    if (numOfOCL > 1) {
                        adNode0 = (AddNode) adNode.copyWithInputs();
                        OCLAddressNode ocl = writeAddressNodes.get(0);
                        WriteNode wr = null;
                        for (Node us : ocl.usages()) {
                            if (us instanceof WriteNode) {
                                wr = (WriteNode) us;
                            }
                        }

                        OCLAddressNode ocln = (OCLAddressNode) ocl.copyWithInputs();
                        ocln.replaceFirstInput(adNode, adNode0);
                        if (wr != null) {
                            wr.replaceFirstInput(ocl, ocln);
                        } else {
                            System.out.println("WriteNode is NULL");
                        }
                        // update hashmap
                        writeAddressNodes.replace(0, ocl, ocln);
                        // delete old ocl node
                        ocl.safeDelete();
                    } else {
                        adNode0 = adNode;
                    }

                    Node adInput0 = null;
                    for (Node adin : adNode0.inputs()) {
                        if (!(adin instanceof ConstantNode)) {
                            adInput0 = adin;
                        }
                    }

                    if (adInput0 != null && numOfOCL == 1) {
                        identifyNodesToBeDeleted(adInput0, nodesToBeDeleted);
                    }
                    // new nodes
                    Constant arrayFieldSizeConst;
                    if (differentTypesRet) {
                        // padding
                        arrayFieldSizeConst = new RawConstant(8);
                    } else {
                        arrayFieldSizeConst = new RawConstant(fieldSizesRet.get(0));
                    }
                    ConstantNode arrayFieldSize = new ConstantNode(arrayFieldSizeConst, StampFactory.positiveInt());
                    graph.addWithoutUnique(arrayFieldSize);
                    Constant arrayIndexConst = new RawConstant(arrayFieldIndex);
                    ConstantNode arrayIndex = new ConstantNode(arrayIndexConst, StampFactory.positiveInt());
                    graph.addWithoutUnique(arrayIndex);
                    MulNode m = new MulNode(arrayFieldSize, arrayIndex);
                    graph.addWithoutUnique(m);

                    int sizeOfFields;

                    if (differentTypesRet) {
                        sizeOfFields = returnArrayFieldTotalBytes + 8;
                    } else {
                        sizeOfFields = returnArrayFieldTotalBytes + fieldSizesRet.get(1);
                    }

                    Constant fieldsSizeConst = new RawConstant(sizeOfFields);
                    ConstantNode fieldsSize = new ConstantNode(fieldsSizeConst, StampFactory.positiveInt());
                    graph.addWithoutUnique(fieldsSize);
                    MulNode m2 = new MulNode(fieldsSize, signExt);
                    graph.addWithoutUnique(m2);

                    AddNode addOffset0 = new AddNode(m, m2);
                    graph.addWithoutUnique(addOffset0);

                    adNode0.replaceFirstInput(adInput0, addOffset0);

                    OCLAddressNode ocl = writeAddressNodes.get(0);
                    WriteNode wr = null;
                    for (Node us : ocl.usages()) {
                        if (us instanceof WriteNode) {
                            wr = (WriteNode) us;
                        }
                    }

                    // CopyArrayTupleField cpAr = new CopyArrayTupleField(48, 8, 5, ph, oclRead,
                    // ocl);
                    // graph.addWithoutUnique(cpAr);
                    //
                    // graph.addBeforeFixed(wr, cpAr);

                    // WriteNode cpWr = (WriteNode) wr.copyWithInputs();
                    // // graph.addWithoutUnique(cpWr);
                    //
                    // FloatingReadNode fr = null;
                    // for (Node in : wr.inputs()) {
                    // if (in instanceof FloatingReadNode) {
                    // fr = (FloatingReadNode) in;
                    // break;
                    // }
                    // }
                    //
                    // cpAr.replaceAtUsages(cpWr);
                    //
                    // cpWr.safeDelete();

                    // cpAr.replaceFirstInput(null, fr);

                    // for (Node in : wr.inputs()) {
                    // if (in instanceof FloatingReadNode) {
                    // wr.replaceFirstInput(in, cpAr);
                    // }
                    // }

                    // WriteNode newWr = new WriteNode(wr.getAddress(), wr.getLocationIdentity(),
                    // oclRead, wr.getBarrierType(), wr.isVolatile());
                    // graph.addWithoutUnique(newWr);
                    // graph.replaceFixed(wr, newWr);
                    // ----- Access Field 1
                    OCLAddressNode oclw2 = writeAddressNodes.get(1);
                    AddNode adNode1 = getAddInput(oclw2);

                    Node adInput1 = null;
                    for (Node adin : adNode1.inputs()) {
                        if (!(adin instanceof ConstantNode)) {
                            adInput1 = adin;
                        }
                    }

                    if (adInput1 != null && numOfOCL == 1) {
                        identifyNodesToBeDeleted(adInput1, nodesToBeDeleted);
                    }

                    Constant field0SizeConst = new RawConstant(returnArrayFieldTotalBytes);
                    ConstantNode field0Size = new ConstantNode(field0SizeConst, StampFactory.positiveInt());
                    graph.addWithoutUnique(field0Size);

                    MulNode m3 = new MulNode(signExt, fieldsSize);
                    graph.addWithoutUnique(m3);
                    AddNode addOffset1 = new AddNode(field0Size, m3);
                    graph.addWithoutUnique(addOffset1);

                    adNode1.replaceFirstInput(adInput1, addOffset1);

                    if (numOfOCL == 1) {
                        for (Node n : nodesToBeDeleted.keySet()) {
                            Integer[] count = nodesToBeDeleted.get(n);
                            // if the usages are as many as the occurrences delete
                            if (count[0] == count[1]) {
                                // System.out.println("= DELETE " + n);
                                n.safeDelete();
                            }
                        }
                    }
                    return;
                }
            }

        }

        if (copyArray && arrayField) {

            if (broadcastedDataset) {
                int tupleSize = fieldSizes.size();
                int broadTupleSize = fieldSizesInner.size();

                if (tupleSize == 2 && returnTupleSize == 3) {
                    // exus
                    // ---- READNODES:
                    // Locate Phi Node with input GlobalThreadID
                    ValuePhiNode globalPhi = null;
                    for (Node n : graph.getNodes()) {
                        if (n instanceof ValuePhiNode) {
                            for (Node in : n.inputs()) {
                                if (in instanceof GlobalThreadIdNode) {
                                    globalPhi = (ValuePhiNode) n;
                                    break;
                                }
                            }
                        }
                        if (globalPhi != null)
                            break;
                    }
                    // Locate & order read address nodes
                    // HashMap<Integer, ArrayList<OCLAddressNode>> readAddressNodes = new HashMap();
                    HashMap<OCLAddressNode, Integer> readAddressNodes = new HashMap<>();
                    for (Node n : graph.getNodes()) {
                        if (n instanceof FloatingReadNode) {
                            OCLAddressNode ocl = (OCLAddressNode) n.inputs().first();
                            returnFieldNumberMultipleLoops(ocl, readAddressNodes, ocl, fieldTypes);
                        }
                    }

                    if (readAddressNodes.size() == 0) {
                        // System.out.println("Oops, no elements in readAddressNodes HashMap!");
                        return;
                    }

                    // Locate & order write address nodes
                    HashMap<OCLAddressNode, Integer> writeAddressNodes = new HashMap();

                    for (Node n : graph.getNodes()) {
                        if (n instanceof WriteNode) {
                            OCLAddressNode ocl = (OCLAddressNode) n.inputs().first();
                            returnFieldNumberMultipleLoops(ocl, writeAddressNodes, ocl, fieldTypesRet);
                        }
                    }

                    if (writeAddressNodes.size() == 0) {
                        // System.out.println("Oops, no elements in readAddressNodes HashMap!");
                        return;
                    }

                    // Make pairs of <OCLAddress, PhiNode> for Read Nodes
                    HashMap<OCLAddressNode, ValuePhiNode> oclReadPhis = new HashMap<>();
                    for (OCLAddressNode ocl : readAddressNodes.keySet()) {
                        findPhi(ocl, oclReadPhis, ocl);
                    }

                    // Make pairs of <OCLAddress, PhiNode> for Write Nodes
                    HashMap<OCLAddressNode, ValuePhiNode> oclWritePhis = new HashMap<>();
                    for (OCLAddressNode ocl : writeAddressNodes.keySet()) {
                        findPhi(ocl, oclWritePhis, ocl);
                    }

                    HashMap<ValuePhiNode, SignExtendNode> signExtOfPhi = new HashMap<>();

                    for (OCLAddressNode oclRead : oclReadPhis.keySet()) {
                        ValuePhiNode ph = oclReadPhis.get(oclRead);
                        SignExtendNode signExt = null;

                        for (Node phUse : ph.usages()) {
                            if (phUse instanceof SignExtendNode) {
                                signExt = (SignExtendNode) phUse;
                            }
                        }

                        if (signExt == null) {
                            SignExtendNode sgnEx = null;
                            for (Node phUse : ph.usages()) {
                                if (phUse instanceof LeftShiftNode) {
                                    LeftShiftNode lsh = (LeftShiftNode) phUse;
                                    for (Node lshUse : lsh.usages()) {
                                        if (lshUse instanceof SignExtendNode) {
                                            sgnEx = (SignExtendNode) lshUse;
                                            break;
                                        }
                                    }
                                }
                            }
                            if (sgnEx == null) {
                                return;
                            } else {
                                signExt = (SignExtendNode) sgnEx.copyWithInputs();
                                for (Node in : signExt.inputs()) {
                                    signExt.replaceFirstInput(in, ph);
                                }
                            }

                            if (signExt == null) {
                                System.out.println("NO SIGNEXTEND AFTER PHI!!!");
                                return;
                            }

                            signExtOfPhi.put(ph, signExt);
                        }
                    }
                    // CASE: ReadNode paired with GlobalThreadID Phi
                    // -- if index is same as array_index:
                    // Locate write node
                    for (OCLAddressNode readAddress : readAddressNodes.keySet()) {
                        if (readAddressNodes.get(readAddress) == arrayFieldIndex && oclReadPhis.get(readAddress) == globalPhi) {
                            FloatingReadNode fr = (FloatingReadNode) readAddress.usages().first();

                            HashMap<Node, Integer[]> nodesToBeDeleted = new HashMap<>();

                            WriteNode wr = null;
                            for (Node us : fr.usages()) {
                                if (us instanceof WriteNode) {
                                    wr = (WriteNode) us;
                                }
                            }
                            OCLAddressNode writeAddress = null;
                            for (Node in : wr.inputs()) {
                                if (in instanceof OCLAddressNode) {
                                    writeAddress = (OCLAddressNode) in;
                                }
                            }

                            Stamp readStamp = fr.stamp(NodeView.DEFAULT);

                            Constant headerConst = new RawConstant(24);
                            ConstantNode header = new ConstantNode(headerConst, StampFactory.forKind(JavaKind.Long));
                            graph.addWithoutUnique(header);

                            for (Node in : readAddress.inputs()) {
                                if (in instanceof AddNode) {
                                    readAddress.replaceFirstInput(in, header);
                                }
                            }

                            for (Node in : writeAddress.inputs()) {
                                if (in instanceof AddNode) {
                                    writeAddress.replaceFirstInput(in, header);
                                }
                            }
                            // TODO: check if input is broadcasted (inner) or regular dataset
                            // ParameterNode p = null;
                            // for (Node in : fr.inputs()) {
                            // if (in instanceof ParameterNode) {
                            // p = (ParameterNode) in;
                            // }
                            // }
                            //
                            // if (p == null)
                            // return;

                            int sizeOfFields;
                            if (differentTypes) {
                                sizeOfFields = arrayFieldTotalBytes + 8;
                            } else {
                                sizeOfFields = arrayFieldTotalBytes + fieldSizes.get(1);
                            }

                            int sizeOfRetFields;
                            if (differentTypesRet) {
                                sizeOfRetFields = arrayFieldTotalBytes + 16;
                            } else {
                                sizeOfRetFields = arrayFieldTotalBytes + fieldSizesRet.get(1) + fieldSizesRet.get(2);
                            }

                            int arrayFieldSize;
                            if (differentTypes) {
                                // padding
                                arrayFieldSize = 8;
                            } else {
                                // since we copy the input array this size should be correct
                                arrayFieldSize = fieldSizes.get(0);
                            }
                            int arrayLength = arrayFieldTotalBytes / arrayFieldSize;

                            identifyNodesToBeDeleted(wr, nodesToBeDeleted);

                            Node pred = wr.predecessor();
                            CopyArrayTupleField cpAr = new CopyArrayTupleField(sizeOfFields, sizeOfRetFields, arrayFieldSize, arrayLength, globalPhi, readAddress, writeAddress, "double", readStamp,
                                    returnTupleArrayFieldNo);
                            graph.addWithoutUnique(cpAr);
                            graph.addAfterFixed((FixedWithNextNode) pred, cpAr);

                            wr.replaceAtUsages(cpAr);

                            for (Node n : nodesToBeDeleted.keySet()) {
                                Integer[] count = nodesToBeDeleted.get(n);

                                // if the usages are as many as the occurrences delete
                                if (count[0] == count[1]) {
                                    if (n instanceof FixedNode) {
                                        removeFixed(n);
                                    } else if (!(n instanceof ParameterNode || n instanceof OCLAddressNode)) {
                                        n.safeDelete();
                                    }
                                }
                            }
                            writeAddressNodes.remove(writeAddress);
                            oclWritePhis.remove(writeAddress);
                        } else if (readAddressNodes.get(readAddress) == arrayFieldIndex) {

                            ValuePhiNode innerLoopPhi = oclReadPhis.get(readAddress);
                            boolean broadcasted = false;
                            for (Node in : readAddress.inputs()) {
                                if (in instanceof ParameterNode) {
                                    if (in.toString().contains("(2)")) {
                                        broadcasted = true;
                                    }
                                }
                            }

                            if (tupleArrayFieldNo == 0) {
                                // if the first field of the Tuple3 is an array
                                // ----- Access Field 0
                                AddNode adNode = getAddInput(readAddress);
                                HashMap<Node, Integer[]> nodesToBeDeleted = new HashMap<>();

                                int numOfOCL = 0;
                                for (Node addUse : adNode.usages()) {
                                    if (addUse instanceof OCLAddressNode) {
                                        numOfOCL++;
                                    }
                                }
                                AddNode adNode0;
                                if (numOfOCL > 1) {
                                    adNode0 = (AddNode) adNode.copyWithInputs();

                                    FloatingReadNode fr = null;
                                    for (Node us : readAddress.usages()) {
                                        if (us instanceof FloatingReadNode) {
                                            fr = (FloatingReadNode) us;
                                        }
                                    }

                                    OCLAddressNode ocln = (OCLAddressNode) readAddress.copyWithInputs();
                                    ocln.replaceFirstInput(adNode, adNode0);
                                    if (fr != null) {
                                        fr.replaceFirstInput(readAddress, ocln);
                                    } else {
                                        System.out.println("ReadNode is NULL");
                                    }

                                } else {
                                    adNode0 = adNode;
                                }

                                Node adInput0 = null;

                                for (Node adin : adNode0.inputs()) {
                                    if (!(adin instanceof ConstantNode)) {
                                        adInput0 = adin;
                                    }
                                }

                                if (adInput0 != null) {
                                    identifyNodesToBeDeleted(adInput0, nodesToBeDeleted);
                                }

                                // new nodes
                                Constant arrayFieldSizeConst;
                                if (broadcasted) {
                                    if (differentTypesInner) {
                                        // padding
                                        arrayFieldSizeConst = new RawConstant(8);
                                    } else {
                                        arrayFieldSizeConst = new RawConstant(fieldSizesInner.get(0));
                                    }
                                } else {
                                    if (differentTypes) {
                                        // padding
                                        arrayFieldSizeConst = new RawConstant(8);
                                    } else {
                                        arrayFieldSizeConst = new RawConstant(fieldSizes.get(0));
                                    }
                                }

                                ConstantNode arrayFieldSize = new ConstantNode(arrayFieldSizeConst, StampFactory.positiveInt());
                                graph.addWithoutUnique(arrayFieldSize);

                                // Constant arrayIndexConst = new RawConstant(arrayFieldIndex);
                                // ConstantNode arrayIndex = new ConstantNode(arrayIndexConst,
                                // StampFactory.positiveInt());
                                // graph.addWithoutUnique(arrayIndex);

                                SignExtendNode sn = null;

                                for (Node us : innerLoopPhi.usages()) {
                                    if (us instanceof SignExtendNode) {
                                        sn = (SignExtendNode) us;
                                    }
                                }

                                MulNode m = new MulNode(arrayFieldSize, sn);
                                graph.addWithoutUnique(m);

                                int sizeOfFields;

                                if (broadcasted) {
                                    int numOfBroadFields = fieldSizesInner.size();
                                    if (differentTypesInner) {
                                        sizeOfFields = broadcastedArrayFieldTotalBytes + (numOfBroadFields - 1) * 8;
                                    } else {
                                        int sizeOfNumericFields = 0;
                                        for (int i = 1; i < numOfBroadFields; i++) {
                                            sizeOfNumericFields += fieldSizesInner.get(i);
                                        }
                                        sizeOfFields = broadcastedArrayFieldTotalBytes + sizeOfNumericFields;
                                    }
                                } else {
                                    if (differentTypes) {
                                        sizeOfFields = arrayFieldTotalBytes + 8;
                                    } else {
                                        sizeOfFields = arrayFieldTotalBytes + fieldSizes.get(1);
                                    }
                                }

                                Constant fieldsSizeConst = new RawConstant(sizeOfFields);
                                ConstantNode fieldsSize = new ConstantNode(fieldsSizeConst, StampFactory.positiveInt());
                                graph.addWithoutUnique(fieldsSize);
                                MulNode m2;
                                if (broadcasted && broadcastedSize == 1) {
                                    Constant oneIterationConst = new RawConstant(0);
                                    ConstantNode oneIter = new ConstantNode(oneIterationConst, StampFactory.positiveInt());
                                    graph.addWithoutUnique(oneIter);
                                    m2 = new MulNode(oneIter, fieldsSize);
                                } else {
                                    SignExtendNode signExt = signExtOfPhi.get(globalPhi);
                                    m2 = new MulNode(signExt, fieldsSize);
                                }
                                graph.addWithoutUnique(m2);

                                AddNode addOffset0 = new AddNode(m, m2);
                                graph.addWithoutUnique(addOffset0);

                                adNode0.replaceFirstInput(adInput0, addOffset0);
                            }

                        } else if (readAddressNodes.get(readAddress) == 1) {
                            // -- else code for accessing second Tuple field
                            // CASE: ReadNode paired with other Phi Node
                            // -- if index == array_index
                            // -- 24 + 8*phi + (f1 +f2)*GlobalPhi

                            // ----- Access Field 1
                            ValuePhiNode innerLoopPhi = oclReadPhis.get(readAddress);
                            boolean broadcasted = false;
                            for (Node in : readAddress.inputs()) {
                                if (in instanceof ParameterNode) {
                                    if (in.toString().contains("(2)")) {
                                        broadcasted = true;
                                    }
                                }
                            }

                            AddNode adNode1 = getAddInput(readAddress);
                            HashMap<Node, Integer[]> nodesToBeDeleted = new HashMap<>();

                            int numOfOCL = 0;

                            Node adInput1 = null;
                            for (Node adin : adNode1.inputs()) {
                                if (!(adin instanceof ConstantNode)) {
                                    adInput1 = adin;
                                }
                            }

                            if (adInput1 != null && numOfOCL == 1) {
                                identifyNodesToBeDeleted(adInput1, nodesToBeDeleted);
                            }

                            Constant field0SizeConst = new RawConstant(arrayFieldTotalBytes);
                            ConstantNode field0Size = new ConstantNode(field0SizeConst, StampFactory.positiveInt());
                            graph.addWithoutUnique(field0Size);

                            SignExtendNode signExt = null;

                            for (Node us : innerLoopPhi.usages()) {
                                if (us instanceof SignExtendNode) {
                                    signExt = (SignExtendNode) us;
                                }
                            }

                            int sizeOfFields;
                            if (broadcasted) {
                                int numOfBroadFields = fieldSizesInner.size();
                                if (differentTypesInner) {
                                    sizeOfFields = broadcastedArrayFieldTotalBytes + (numOfBroadFields - 1) * 8;
                                } else {
                                    int sizeOfNumericFields = 0;
                                    for (int i = 1; i < numOfBroadFields; i++) {
                                        sizeOfNumericFields += fieldSizesInner.get(i);
                                    }
                                    sizeOfFields = broadcastedArrayFieldTotalBytes + sizeOfNumericFields;
                                }
                            } else {
                                if (differentTypes) {
                                    sizeOfFields = arrayFieldTotalBytes + 8;
                                } else {
                                    sizeOfFields = arrayFieldTotalBytes + fieldSizes.get(1);
                                }
                            }

                            Constant fieldsSizeConst = new RawConstant(sizeOfFields);
                            ConstantNode fieldsSize = new ConstantNode(fieldsSizeConst, StampFactory.positiveInt());
                            graph.addWithoutUnique(fieldsSize);

                            MulNode m3;
                            if (broadcasted && broadcastedSize == 1) {
                                Constant oneIterationConst = new RawConstant(0);
                                ConstantNode oneIter = new ConstantNode(oneIterationConst, StampFactory.positiveInt());
                                graph.addWithoutUnique(oneIter);
                                m3 = new MulNode(oneIter, fieldsSize);
                            } else {
                                m3 = new MulNode(signExt, fieldsSize);
                            }

                            graph.addWithoutUnique(m3);
                            AddNode addOffset1 = new AddNode(field0Size, m3);
                            graph.addWithoutUnique(addOffset1);

                            adNode1.replaceFirstInput(adInput1, addOffset1);

                            if (numOfOCL == 1) {
                                for (Node n : nodesToBeDeleted.keySet()) {
                                    Integer[] count = nodesToBeDeleted.get(n);
                                    // if the usages are as many as the occurrences delete
                                    if (count[0] == count[1]) {
                                        // System.out.println("= DELETE " + n);
                                        n.safeDelete();
                                    }
                                }
                            }
                        }

                    }

                    // ---- WRITENODES:
                    if (returnTupleSize == 3) {
                        for (OCLAddressNode writeAddress : writeAddressNodes.keySet()) {
                            if (writeAddressNodes.get(writeAddress) == arrayFieldIndex && !(oclWritePhis.get(writeAddress) == globalPhi)) {
                                ValuePhiNode innerLoopPhi = oclWritePhis.get(writeAddress);
                                HashMap<Node, Integer[]> nodesToBeDeleted = new HashMap<>();

                                boolean input = false;
                                for (Node in : writeAddress.inputs()) {
                                    if (in instanceof ParameterNode) {
                                        if (in.toString().contains("(1)")) {
                                            input = true;
                                        }
                                    }
                                }

                                AddNode adNode = getAddInput(writeAddress);

                                int numOfOCL = 0;
                                for (Node addUse : adNode.usages()) {
                                    if (addUse instanceof OCLAddressNode) {
                                        numOfOCL++;
                                    }
                                }
                                AddNode adNode0;
                                if (numOfOCL > 1) {
                                    adNode0 = (AddNode) adNode.copyWithInputs();
                                    WriteNode wr = null;
                                    for (Node us : writeAddress.usages()) {
                                        if (us instanceof WriteNode) {
                                            wr = (WriteNode) us;
                                        }
                                    }

                                    OCLAddressNode ocln = (OCLAddressNode) writeAddress.copyWithInputs();
                                    ocln.replaceFirstInput(adNode, adNode0);
                                    if (wr != null) {
                                        wr.replaceFirstInput(writeAddress, ocln);
                                    } else {
                                        System.out.println("WriteNode is NULL");
                                    }

                                } else {
                                    adNode0 = adNode;
                                }

                                Node adInput0 = null;
                                for (Node adin : adNode0.inputs()) {
                                    if (!(adin instanceof ConstantNode)) {
                                        adInput0 = adin;
                                    }
                                }

                                if (adInput0 != null && numOfOCL == 1) {
                                    identifyNodesToBeDeleted(adInput0, nodesToBeDeleted);
                                }
                                // new nodes
                                Constant arrayFieldSizeConst;
                                if (input) {
                                    if (differentTypes) {
                                        // padding
                                        arrayFieldSizeConst = new RawConstant(8);
                                    } else {
                                        arrayFieldSizeConst = new RawConstant(fieldSizes.get(0));
                                    }
                                } else {
                                    if (differentTypesRet) {
                                        // padding
                                        arrayFieldSizeConst = new RawConstant(8);
                                    } else {
                                        arrayFieldSizeConst = new RawConstant(fieldSizesRet.get(0));
                                    }
                                }

                                ConstantNode arrayFieldSize = new ConstantNode(arrayFieldSizeConst, StampFactory.positiveInt());
                                graph.addWithoutUnique(arrayFieldSize);

                                SignExtendNode sn = null;

                                for (Node in : innerLoopPhi.usages()) {
                                    if (in instanceof SignExtendNode) {
                                        sn = (SignExtendNode) in;
                                    }
                                }

                                MulNode m = new MulNode(arrayFieldSize, sn);
                                graph.addWithoutUnique(m);

                                int sizeOfFields;

                                if (input) {
                                    if (differentTypes) {
                                        sizeOfFields = returnArrayFieldTotalBytes + 8;
                                    } else {
                                        sizeOfFields = returnArrayFieldTotalBytes + fieldSizes.get(1);
                                    }
                                } else {
                                    if (differentTypesRet) {
                                        sizeOfFields = returnArrayFieldTotalBytes + 8 + 8;
                                    } else {
                                        sizeOfFields = returnArrayFieldTotalBytes + fieldSizesRet.get(1) + fieldSizesRet.get(2);
                                    }
                                }

                                SignExtendNode globalSn = null;
                                for (Node in : globalPhi.usages()) {
                                    if (in instanceof SignExtendNode) {
                                        globalSn = (SignExtendNode) in;
                                    }
                                }
                                Constant fieldsSizeConst = new RawConstant(sizeOfFields);
                                ConstantNode fieldsSize = new ConstantNode(fieldsSizeConst, StampFactory.positiveInt());
                                graph.addWithoutUnique(fieldsSize);
                                MulNode m2 = new MulNode(fieldsSize, globalSn);
                                graph.addWithoutUnique(m2);

                                AddNode addOffset0 = new AddNode(m, m2);
                                graph.addWithoutUnique(addOffset0);

                                adNode0.replaceFirstInput(adInput0, addOffset0);

                                if (numOfOCL == 1) {
                                    for (Node n : nodesToBeDeleted.keySet()) {
                                        Integer[] count = nodesToBeDeleted.get(n);
                                        // if the usages are as many as the occurrences delete
                                        if (count[0] == count[1]) {
                                            // System.out.println("= DELETE " + n);
                                            n.safeDelete();
                                        }
                                    }
                                }

                            } else if (writeAddressNodes.get(writeAddress) == 1) {
                                ValuePhiNode loopPhi = oclWritePhis.get(writeAddress);
                                HashMap<Node, Integer[]> nodesToBeDeleted = new HashMap<>();

                                SignExtendNode signExt = null;
                                for (Node us : loopPhi.usages()) {
                                    if (us instanceof SignExtendNode) {
                                        signExt = (SignExtendNode) us;
                                    }
                                }

                                AddNode adNode1 = getAddInput(writeAddress);

                                Node adInput1 = null;
                                for (Node adin : adNode1.inputs()) {
                                    if (!(adin instanceof ConstantNode)) {
                                        adInput1 = adin;
                                    }
                                }

                                if (adInput1 != null) {
                                    identifyNodesToBeDeleted(adInput1, nodesToBeDeleted);
                                }

                                // new nodes
                                int sizeOfFields;
                                if (differentTypesRet) {
                                    sizeOfFields = returnArrayFieldTotalBytes + 8 + 8;
                                } else {
                                    sizeOfFields = returnArrayFieldTotalBytes + fieldSizesRet.get(1) + fieldSizesRet.get(2);
                                }
                                Constant fieldsSizeConst = new RawConstant(sizeOfFields);
                                ConstantNode fieldsSize = new ConstantNode(fieldsSizeConst, StampFactory.positiveInt());
                                graph.addWithoutUnique(fieldsSize);

                                MulNode m2 = new MulNode(signExt, fieldsSize);
                                graph.addWithoutUnique(m2);

                                Constant field0SizeConst = new RawConstant(arrayFieldTotalBytes);
                                ConstantNode field0Size = new ConstantNode(field0SizeConst, StampFactory.positiveInt());
                                graph.addWithoutUnique(field0Size);

                                AddNode addOffset1 = new AddNode(m2, field0Size);
                                graph.addWithoutUnique(addOffset1);

                                adNode1.replaceFirstInput(adInput1, addOffset1);

                                for (Node n : nodesToBeDeleted.keySet()) {
                                    Integer[] count = nodesToBeDeleted.get(n);
                                    // if the usages are as many as the occurrences delete
                                    if (count[0] == count[1]) {
                                        n.safeDelete();
                                    }
                                }
                            } else if (writeAddressNodes.get(writeAddress) == 2) {
                                ValuePhiNode loopPhi = oclWritePhis.get(writeAddress);
                                HashMap<Node, Integer[]> nodesToBeDeleted = new HashMap<>();

                                SignExtendNode signExt = null;
                                for (Node us : loopPhi.usages()) {
                                    if (us instanceof SignExtendNode) {
                                        signExt = (SignExtendNode) us;
                                    }
                                }

                                AddNode adNode2 = getAddInput(writeAddress);

                                Node adInput2 = null;
                                for (Node adin : adNode2.inputs()) {
                                    if (!(adin instanceof ConstantNode)) {
                                        adInput2 = adin;
                                    }
                                }

                                if (adInput2 != null) {
                                    identifyNodesToBeDeleted(adInput2, nodesToBeDeleted);
                                }

                                // new nodes
                                int sizeOfField0Field1;
                                // new nodes
                                if (differentTypes) {
                                    sizeOfField0Field1 = arrayFieldTotalBytes + 8;
                                } else {
                                    sizeOfField0Field1 = arrayFieldTotalBytes + fieldSizes.get(1);
                                }

                                int sizeOfFields;
                                if (differentTypesRet) {
                                    sizeOfFields = returnArrayFieldTotalBytes + 8 + 8;
                                } else {
                                    sizeOfFields = returnArrayFieldTotalBytes + fieldSizesRet.get(1) + fieldSizesRet.get(2);
                                }
                                Constant fieldsSizeConst = new RawConstant(sizeOfFields);
                                ConstantNode fieldsSize = new ConstantNode(fieldsSizeConst, StampFactory.positiveInt());
                                graph.addWithoutUnique(fieldsSize);

                                MulNode m2 = new MulNode(signExt, fieldsSize);
                                graph.addWithoutUnique(m2);

                                Constant fields0plus1SizeConst = new RawConstant(sizeOfField0Field1);
                                ConstantNode fields01Size = new ConstantNode(fields0plus1SizeConst, StampFactory.positiveInt());
                                graph.addWithoutUnique(fields01Size);

                                AddNode addOffset2 = new AddNode(fields01Size, m2);
                                graph.addWithoutUnique(addOffset2);
                                adNode2.replaceFirstInput(adInput2, addOffset2);

                                for (Node n : nodesToBeDeleted.keySet()) {
                                    Integer[] count = nodesToBeDeleted.get(n);
                                    // if the usages are as many as the occurrences delete
                                    if (count[0] == count[1]) {
                                        n.safeDelete();
                                    }
                                }

                            }
                        }
                    }
                }
                return;
            } else if (twoForLoops) {
                // System.out.println("> Two for loops!");
                int tupleSize = fieldSizes.size();

                if (tupleSize == 3 && returnTupleSize == 2) {
                    // exus
                    // System.out.println("Input size 3, output size 2");
                    // ---- READNODES:
                    // Locate Phi Node with input GlobalThreadID
                    ValuePhiNode globalPhi = null;
                    for (Node n : graph.getNodes()) {
                        if (n instanceof ValuePhiNode) {
                            for (Node in : n.inputs()) {
                                if (in instanceof GlobalThreadIdNode) {
                                    globalPhi = (ValuePhiNode) n;
                                    break;
                                }
                            }
                        }
                        if (globalPhi != null)
                            break;
                    }
                    // Locate & order read address nodes
                    // HashMap<Integer, ArrayList<OCLAddressNode>> readAddressNodes = new HashMap();
                    HashMap<OCLAddressNode, Integer> readAddressNodes = new HashMap<>();
                    for (Node n : graph.getNodes()) {
                        if (n instanceof FloatingReadNode) {
                            OCLAddressNode ocl = (OCLAddressNode) n.inputs().first();
                            returnFieldNumberMultipleLoops(ocl, readAddressNodes, ocl, fieldTypes);
                        }
                    }

                    // for (OCLAddressNode ocl : readAddressNodes.keySet()) {
                    // System.out.println("- OCLRead: " + ocl + " index: " +
                    // readAddressNodes.get(ocl));
                    // }

                    if (readAddressNodes.size() == 0) {
                        // System.out.println("Oops, no elements in readAddressNodes HashMap!");
                        return;
                    }

                    // Locate & order write address nodes
                    HashMap<OCLAddressNode, Integer> writeAddressNodes = new HashMap();

                    for (Node n : graph.getNodes()) {
                        if (n instanceof WriteNode) {
                            OCLAddressNode ocl = (OCLAddressNode) n.inputs().first();
                            returnFieldNumberMultipleLoops(ocl, writeAddressNodes, ocl, fieldTypesRet);
                        }
                    }

                    if (writeAddressNodes.size() == 0) {
                        // System.out.println("Oops, no elements in readAddressNodes HashMap!");
                        return;
                    }

                    // for (OCLAddressNode ocl : writeAddressNodes.keySet()) {
                    // System.out.println("+ OCLWrite: " + ocl + " index: " +
                    // writeAddressNodes.get(ocl));
                    // }

                    // Make pairs of <OCLAddress, PhiNode> for Read Nodes
                    HashMap<OCLAddressNode, ValuePhiNode> oclReadPhis = new HashMap<>();
                    for (OCLAddressNode ocl : readAddressNodes.keySet()) {
                        findPhi(ocl, oclReadPhis, ocl);
                    }

                    // for (OCLAddressNode ocl : oclReadPhis.keySet()) {
                    // System.out.println("- OCLReadPhis: " + ocl + " phi: " +
                    // oclReadPhis.get(ocl));
                    // }

                    // Make pairs of <OCLAddress, PhiNode> for Write Nodes
                    HashMap<OCLAddressNode, ValuePhiNode> oclWritePhis = new HashMap<>();
                    for (OCLAddressNode ocl : writeAddressNodes.keySet()) {
                        findPhi(ocl, oclWritePhis, ocl);
                    }

                    // for (OCLAddressNode ocl : oclWritePhis.keySet()) {
                    // System.out.println("+ OCLWritePhis: " + ocl + " phi: " +
                    // oclWritePhis.get(ocl));
                    // }

                    HashMap<ValuePhiNode, SignExtendNode> signExtOfPhi = new HashMap<>();

                    for (OCLAddressNode oclRead : oclReadPhis.keySet()) {
                        ValuePhiNode ph = oclReadPhis.get(oclRead);
                        SignExtendNode signExt = null;

                        for (Node phUse : ph.usages()) {
                            if (phUse instanceof SignExtendNode) {
                                signExt = (SignExtendNode) phUse;
                            }
                        }

                        if (signExt == null) {
                            SignExtendNode sgnEx = null;
                            for (Node phUse : ph.usages()) {
                                if (phUse instanceof LeftShiftNode) {
                                    LeftShiftNode lsh = (LeftShiftNode) phUse;
                                    if (lsh.usages().filter(SignExtendNode.class).isNotEmpty()) {
                                        for (Node lshUse : lsh.usages()) {
                                            if (lshUse instanceof SignExtendNode) {
                                                sgnEx = (SignExtendNode) lshUse;
                                                break;
                                            }
                                        }
                                    } else {
                                        for (Node lshUse : lsh.usages()) {
                                            if (lshUse instanceof AddNode) {
                                                for (Node adus : lshUse.usages()) {
                                                    sgnEx = (SignExtendNode) adus;
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (sgnEx == null) {
                                return;
                            } else {
                                signExt = (SignExtendNode) sgnEx.copyWithInputs();
                                for (Node in : signExt.inputs()) {
                                    signExt.replaceFirstInput(in, ph);
                                }
                            }

                            if (signExt == null) {
                                System.out.println("NO SIGNEXTEND AFTER PHI!!!");
                                return;
                            }

                            signExtOfPhi.put(ph, signExt);
                        }
                    }

                    // for (ValuePhiNode phi : signExtOfPhi.keySet()) {
                    // System.out.println("* Phi: " + phi + " signExt: " + signExtOfPhi.get(phi));
                    // }

                    // CASE: ReadNode paired with GlobalThreadID Phi
                    // -- if index is same as array_index:
                    // Locate write node
                    for (OCLAddressNode readAddress : readAddressNodes.keySet()) {
                        if (readAddressNodes.get(readAddress) == arrayFieldIndex && oclReadPhis.get(readAddress) == globalPhi) {
                            FloatingReadNode fr = (FloatingReadNode) readAddress.usages().first();

                            HashMap<Node, Integer[]> nodesToBeDeleted = new HashMap<>();

                            WriteNode wr = null;
                            for (Node us : fr.usages()) {
                                if (us instanceof WriteNode) {
                                    wr = (WriteNode) us;
                                }
                            }
                            OCLAddressNode writeAddress = null;
                            for (Node in : wr.inputs()) {
                                if (in instanceof OCLAddressNode) {
                                    writeAddress = (OCLAddressNode) in;
                                }
                            }

                            Stamp readStamp = fr.stamp(NodeView.DEFAULT);

                            Constant headerConst = new RawConstant(24);
                            ConstantNode header = new ConstantNode(headerConst, StampFactory.forKind(JavaKind.Long));
                            graph.addWithoutUnique(header);

                            for (Node in : readAddress.inputs()) {
                                if (in instanceof AddNode) {
                                    readAddress.replaceFirstInput(in, header);
                                }
                            }

                            for (Node in : writeAddress.inputs()) {
                                if (in instanceof AddNode) {
                                    writeAddress.replaceFirstInput(in, header);
                                }
                            }

                            int sizeOfFields;
                            if (differentTypes) {
                                sizeOfFields = arrayFieldTotalBytes + 8 + 8;
                                // System.out.println("## Different types 1 : " + sizeOfFields);
                            } else {
                                sizeOfFields = arrayFieldTotalBytes + fieldSizes.get(1) + fieldSizes.get(2);
                                // System.out.println("## Same types 1: " + sizeOfFields);
                            }

                            int sizeOfRetFields;
                            if (differentTypesRet) {
                                // System.out.println("## Different ret types 1");
                                sizeOfRetFields = arrayFieldTotalBytes + 8;
                            } else {
                                // System.out.println("## Same ret types 1");
                                sizeOfRetFields = arrayFieldTotalBytes + fieldSizesRet.get(1);
                            }

                            int arrayFieldSize;
                            if (differentTypes) {
                                // padding
                                arrayFieldSize = 8;
                            } else {
                                // since we copy the input array this size should be correct
                                arrayFieldSize = fieldSizes.get(0);
                            }
                            int arrayLength = arrayFieldTotalBytes / arrayFieldSize;

                            identifyNodesToBeDeleted(wr, nodesToBeDeleted);

                            Node pred = wr.predecessor();
                            CopyArrayTupleField cpAr = new CopyArrayTupleField(sizeOfFields, sizeOfRetFields, arrayFieldSize, arrayLength, globalPhi, readAddress, writeAddress, "double", readStamp,
                                    returnTupleArrayFieldNo);
                            graph.addWithoutUnique(cpAr);
                            graph.addAfterFixed((FixedWithNextNode) pred, cpAr);

                            wr.replaceAtUsages(cpAr);

                            for (Node n : nodesToBeDeleted.keySet()) {
                                Integer[] count = nodesToBeDeleted.get(n);
                                // System.out.println("==== CASE1: delete " + n);

                                // if the usages are as many as the occurrences delete
                                if (count[0] == count[1]) {
                                    if (n instanceof FixedNode) {
                                        removeFixed(n);
                                    } else if (!(n instanceof ParameterNode || n instanceof OCLAddressNode)) {
                                        n.safeDelete();
                                    }
                                }
                            }
                            writeAddressNodes.remove(writeAddress);
                            oclWritePhis.remove(writeAddress);
                        } else if (readAddressNodes.get(readAddress) == arrayFieldIndex) {

                            ValuePhiNode innerLoopPhi = oclReadPhis.get(readAddress);

                            if (tupleArrayFieldNo == 0) {
                                // if the first field of the Tuple3 is an array
                                // ----- Access Field 0
                                AddNode adNode = getAddInput(readAddress);
                                HashMap<Node, Integer[]> nodesToBeDeleted = new HashMap<>();

                                int numOfOCL = 0;
                                for (Node addUse : adNode.usages()) {
                                    if (addUse instanceof OCLAddressNode) {
                                        numOfOCL++;
                                    }
                                }
                                AddNode adNode0;
                                if (numOfOCL > 1) {
                                    adNode0 = (AddNode) adNode.copyWithInputs();

                                    FloatingReadNode fr = null;
                                    for (Node us : readAddress.usages()) {
                                        if (us instanceof FloatingReadNode) {
                                            fr = (FloatingReadNode) us;
                                        }
                                    }

                                    OCLAddressNode ocln = (OCLAddressNode) readAddress.copyWithInputs();
                                    ocln.replaceFirstInput(adNode, adNode0);
                                    if (fr != null) {
                                        fr.replaceFirstInput(readAddress, ocln);
                                    } else {
                                        System.out.println("ReadNode is NULL");
                                    }

                                } else {
                                    adNode0 = adNode;
                                }

                                Node adInput0 = null;

                                for (Node adin : adNode0.inputs()) {
                                    if (!(adin instanceof ConstantNode)) {
                                        adInput0 = adin;
                                    }
                                }

                                if (adInput0 != null) {
                                    identifyNodesToBeDeleted(adInput0, nodesToBeDeleted);
                                }

                                // new nodes
                                Constant arrayFieldSizeConst;

                                if (differentTypes) {
                                    // padding
                                    // System.out.println("## Different types arr 2");
                                    arrayFieldSizeConst = new RawConstant(8);
                                } else {
                                    // System.out.println("## Same types arr 2");
                                    arrayFieldSizeConst = new RawConstant(fieldSizes.get(0));
                                }

                                ConstantNode arrayFieldSize = new ConstantNode(arrayFieldSizeConst, StampFactory.positiveInt());
                                graph.addWithoutUnique(arrayFieldSize);

                                // Constant arrayIndexConst = new RawConstant(arrayFieldIndex);
                                // ConstantNode arrayIndex = new ConstantNode(arrayIndexConst,
                                // StampFactory.positiveInt());
                                // graph.addWithoutUnique(arrayIndex);

                                SignExtendNode sn = null;

                                for (Node us : innerLoopPhi.usages()) {
                                    if (us instanceof SignExtendNode) {
                                        sn = (SignExtendNode) us;
                                    }
                                }

                                MulNode m = new MulNode(arrayFieldSize, sn);
                                graph.addWithoutUnique(m);

                                int sizeOfFields;

                                if (differentTypes) {
                                    // System.out.println("## Different types 2");
                                    sizeOfFields = arrayFieldTotalBytes + 8 + 8;
                                } else {
                                    // System.out.println("## Same types 2");
                                    sizeOfFields = arrayFieldTotalBytes + fieldSizes.get(1) + fieldSizes.get(2);
                                }

                                Constant fieldsSizeConst = new RawConstant(sizeOfFields);
                                ConstantNode fieldsSize = new ConstantNode(fieldsSizeConst, StampFactory.positiveInt());
                                graph.addWithoutUnique(fieldsSize);

                                SignExtendNode signExt = signExtOfPhi.get(globalPhi);
                                MulNode m2 = new MulNode(signExt, fieldsSize);
                                graph.addWithoutUnique(m2);

                                AddNode addOffset0 = new AddNode(m, m2);
                                graph.addWithoutUnique(addOffset0);

                                adNode0.replaceFirstInput(adInput0, addOffset0);
                            }

                        } else if (readAddressNodes.get(readAddress) == 1) {
                            // -- else code for accessing second Tuple field
                            // CASE: ReadNode paired with other Phi Node
                            // -- if index == array_index
                            // -- 24 + 8*phi + (f1 + f2 + f3)*GlobalPhi

                            // ----- Access Field 1
                            ValuePhiNode innerLoopPhi = oclReadPhis.get(readAddress);

                            AddNode adNode1 = getAddInput(readAddress);
                            HashMap<Node, Integer[]> nodesToBeDeleted = new HashMap<>();

                            int numOfOCL = 0;

                            Node adInput1 = null;
                            for (Node adin : adNode1.inputs()) {
                                if (!(adin instanceof ConstantNode)) {
                                    adInput1 = adin;
                                }
                            }

                            if (adInput1 != null && numOfOCL == 1) {
                                identifyNodesToBeDeleted(adInput1, nodesToBeDeleted);
                            }

                            Constant field0SizeConst = new RawConstant(arrayFieldTotalBytes);
                            ConstantNode field0Size = new ConstantNode(field0SizeConst, StampFactory.positiveInt());
                            graph.addWithoutUnique(field0Size);

                            SignExtendNode signExt = null;

                            for (Node us : innerLoopPhi.usages()) {
                                if (us instanceof SignExtendNode) {
                                    signExt = (SignExtendNode) us;
                                }
                            }

                            int sizeOfFields;

                            if (differentTypes) {
                                // System.out.println("## Different types 3");
                                sizeOfFields = arrayFieldTotalBytes + 8 + 8;
                            } else {
                                // System.out.println("## Same types 3");
                                sizeOfFields = arrayFieldTotalBytes + fieldSizes.get(1) + fieldSizes.get(2);
                            }

                            Constant fieldsSizeConst = new RawConstant(sizeOfFields);
                            ConstantNode fieldsSize = new ConstantNode(fieldsSizeConst, StampFactory.positiveInt());
                            graph.addWithoutUnique(fieldsSize);

                            MulNode m3 = new MulNode(signExt, fieldsSize);
                            graph.addWithoutUnique(m3);
                            AddNode addOffset1 = new AddNode(field0Size, m3);
                            graph.addWithoutUnique(addOffset1);

                            adNode1.replaceFirstInput(adInput1, addOffset1);

                            if (numOfOCL == 1) {
                                for (Node n : nodesToBeDeleted.keySet()) {
                                    // System.out.println("==== CASE2: delete " + n);
                                    Integer[] count = nodesToBeDeleted.get(n);
                                    // if the usages are as many as the occurrences delete
                                    if (count[0] == count[1]) {
                                        // System.out.println("= DELETE " + n);
                                        n.safeDelete();
                                    }
                                }
                            }
                        } else if (readAddressNodes.get(readAddress) == 2) {
                            // ----- Access Field 2
                            ValuePhiNode innerLoopPhi = oclReadPhis.get(readAddress);

                            AddNode adNode1 = getAddInput(readAddress);
                            HashMap<Node, Integer[]> nodesToBeDeleted = new HashMap<>();

                            int numOfOCL = 0;

                            Node adInput1 = null;
                            for (Node adin : adNode1.inputs()) {
                                if (!(adin instanceof ConstantNode)) {
                                    adInput1 = adin;
                                }
                            }

                            if (adInput1 != null && numOfOCL == 1) {
                                identifyNodesToBeDeleted(adInput1, nodesToBeDeleted);
                            }

                            int sizeOfField0Field1;

                            if (differentTypes) {
                                sizeOfField0Field1 = arrayFieldTotalBytes + 8;
                            } else {
                                sizeOfField0Field1 = arrayFieldTotalBytes + fieldSizes.get(1);
                            }

                            Constant field01SizeConst = new RawConstant(sizeOfField0Field1);
                            ConstantNode field01Size = new ConstantNode(field01SizeConst, StampFactory.positiveInt());
                            graph.addWithoutUnique(field01Size);

                            SignExtendNode signExt = null;

                            for (Node us : innerLoopPhi.usages()) {
                                if (us instanceof SignExtendNode) {
                                    signExt = (SignExtendNode) us;
                                }
                            }

                            int sizeOfFields;

                            if (differentTypes) {
                                // System.out.println("## Different types 4");
                                sizeOfFields = arrayFieldTotalBytes + 8 + 8;
                            } else {
                                // System.out.println("## Same types 4");
                                sizeOfFields = arrayFieldTotalBytes + fieldSizes.get(1) + fieldSizes.get(2);
                            }

                            Constant fieldsSizeConst = new RawConstant(sizeOfFields);
                            ConstantNode fieldsSize = new ConstantNode(fieldsSizeConst, StampFactory.positiveInt());
                            graph.addWithoutUnique(fieldsSize);

                            MulNode m3 = new MulNode(signExt, fieldsSize);
                            graph.addWithoutUnique(m3);
                            AddNode addOffset1 = new AddNode(field01Size, m3);
                            graph.addWithoutUnique(addOffset1);

                            adNode1.replaceFirstInput(adInput1, addOffset1);

                            if (numOfOCL == 1) {
                                for (Node n : nodesToBeDeleted.keySet()) {
                                    // System.out.println("==== CASE3: delete " + n);
                                    Integer[] count = nodesToBeDeleted.get(n);
                                    // if the usages are as many as the occurrences delete
                                    if (count[0] == count[1]) {
                                        // System.out.println("= DELETE " + n);
                                        n.safeDelete();
                                    }
                                }
                            }
                        }

                    }

                    // WRITE ADDRESS NODES
                    for (OCLAddressNode writeAddress : writeAddressNodes.keySet()) {
                        if (writeAddressNodes.get(writeAddress) == arrayFieldIndex && !(oclWritePhis.get(writeAddress) == globalPhi)) {
                            ValuePhiNode innerLoopPhi = oclWritePhis.get(writeAddress);
                            HashMap<Node, Integer[]> nodesToBeDeleted = new HashMap<>();

                            boolean input = false;
                            for (Node in : writeAddress.inputs()) {
                                if (in instanceof ParameterNode) {
                                    if (in.toString().contains("(1)")) {
                                        input = true;
                                    }
                                }
                            }

                            AddNode adNode = getAddInput(writeAddress);

                            int numOfOCL = 0;
                            for (Node addUse : adNode.usages()) {
                                if (addUse instanceof OCLAddressNode) {
                                    numOfOCL++;
                                }
                            }
                            AddNode adNode0;
                            if (numOfOCL > 1) {
                                adNode0 = (AddNode) adNode.copyWithInputs();
                                WriteNode wr = null;
                                for (Node us : writeAddress.usages()) {
                                    if (us instanceof WriteNode) {
                                        wr = (WriteNode) us;
                                    }
                                }

                                OCLAddressNode ocln = (OCLAddressNode) writeAddress.copyWithInputs();
                                ocln.replaceFirstInput(adNode, adNode0);
                                if (wr != null) {
                                    wr.replaceFirstInput(writeAddress, ocln);
                                } else {
                                    System.out.println("WriteNode is NULL");
                                }

                            } else {
                                adNode0 = adNode;
                            }

                            Node adInput0 = null;
                            for (Node adin : adNode0.inputs()) {
                                if (!(adin instanceof ConstantNode)) {
                                    adInput0 = adin;
                                }
                            }

                            if (adInput0 != null && numOfOCL == 1) {
                                identifyNodesToBeDeleted(adInput0, nodesToBeDeleted);
                            }
                            // new nodes
                            Constant arrayFieldSizeConst;
                            if (input) {
                                if (differentTypes) {
                                    // padding
                                    // System.out.println("## Different types (in r) ar 5");
                                    arrayFieldSizeConst = new RawConstant(8);
                                } else {
                                    // System.out.println("## Same types (in r) ar 5");
                                    arrayFieldSizeConst = new RawConstant(fieldSizes.get(0));
                                }
                            } else {
                                if (differentTypesRet) {
                                    // padding
                                    // System.out.println("## Different types ret ar 5");
                                    arrayFieldSizeConst = new RawConstant(8);
                                } else {
                                    // System.out.println("## Same types ret ar 5");
                                    arrayFieldSizeConst = new RawConstant(fieldSizesRet.get(0));
                                }
                            }

                            ConstantNode arrayFieldSize = new ConstantNode(arrayFieldSizeConst, StampFactory.positiveInt());
                            graph.addWithoutUnique(arrayFieldSize);

                            SignExtendNode sn = null;

                            for (Node in : innerLoopPhi.usages()) {
                                if (in instanceof SignExtendNode) {
                                    sn = (SignExtendNode) in;
                                }
                            }

                            MulNode m = new MulNode(arrayFieldSize, sn);
                            graph.addWithoutUnique(m);

                            int sizeOfFields;

                            if (input) {
                                if (differentTypes) {
                                    // System.out.println("## Different types (in) 5");
                                    sizeOfFields = returnArrayFieldTotalBytes + 8 + 8;
                                } else {
                                    // System.out.println("## Same types (in) 5");
                                    sizeOfFields = returnArrayFieldTotalBytes + fieldSizes.get(1) + fieldSizes.get(2);
                                }
                            } else {
                                if (differentTypesRet) {
                                    // System.out.println("## Different types ret 5");
                                    sizeOfFields = returnArrayFieldTotalBytes + 8;
                                } else {
                                    // System.out.println("## Same types ret 5");
                                    sizeOfFields = returnArrayFieldTotalBytes + fieldSizesRet.get(1);
                                }
                            }

                            SignExtendNode globalSn = null;
                            for (Node in : globalPhi.usages()) {
                                if (in instanceof SignExtendNode) {
                                    globalSn = (SignExtendNode) in;
                                }
                            }
                            Constant fieldsSizeConst = new RawConstant(sizeOfFields);
                            ConstantNode fieldsSize = new ConstantNode(fieldsSizeConst, StampFactory.positiveInt());
                            graph.addWithoutUnique(fieldsSize);
                            MulNode m2 = new MulNode(fieldsSize, globalSn);
                            graph.addWithoutUnique(m2);

                            AddNode addOffset0 = new AddNode(m, m2);
                            graph.addWithoutUnique(addOffset0);

                            adNode0.replaceFirstInput(adInput0, addOffset0);

                            if (numOfOCL == 1) {
                                for (Node n : nodesToBeDeleted.keySet()) {
                                    if (!(n instanceof SignExtendNode)) {
                                        // System.out.println("==== CASE4: delete " + n);
                                        Integer[] count = nodesToBeDeleted.get(n);
                                        // if the usages are as many as the occurrences delete
                                        if (count[0] == count[1]) {
                                            // System.out.println("= DELETE " + n);
                                            n.safeDelete();
                                        }
                                    }
                                }
                            }

                        } else if (writeAddressNodes.get(writeAddress) == 1) {
                            ValuePhiNode loopPhi = oclWritePhis.get(writeAddress);
                            HashMap<Node, Integer[]> nodesToBeDeleted = new HashMap<>();

                            SignExtendNode signExt = null;
                            for (Node us : loopPhi.usages()) {
                                if (us instanceof SignExtendNode) {
                                    signExt = (SignExtendNode) us;
                                }
                            }

                            AddNode adNode1 = getAddInput(writeAddress);

                            Node adInput1 = null;
                            for (Node adin : adNode1.inputs()) {
                                if (!(adin instanceof ConstantNode)) {
                                    adInput1 = adin;
                                }
                            }

                            if (adInput1 != null) {
                                identifyNodesToBeDeleted(adInput1, nodesToBeDeleted);
                            }

                            // new nodes
                            int sizeOfFields;
                            if (differentTypesRet) {
                                // System.out.println("## Different ret types 6");
                                sizeOfFields = returnArrayFieldTotalBytes + 8;
                            } else {
                                // System.out.println("## Same ret types 6");
                                sizeOfFields = returnArrayFieldTotalBytes + fieldSizesRet.get(1);
                            }
                            Constant fieldsSizeConst = new RawConstant(sizeOfFields);
                            ConstantNode fieldsSize = new ConstantNode(fieldsSizeConst, StampFactory.positiveInt());
                            graph.addWithoutUnique(fieldsSize);

                            MulNode m2 = new MulNode(signExt, fieldsSize);
                            graph.addWithoutUnique(m2);

                            Constant field0SizeConst = new RawConstant(arrayFieldTotalBytes);
                            ConstantNode field0Size = new ConstantNode(field0SizeConst, StampFactory.positiveInt());
                            graph.addWithoutUnique(field0Size);

                            AddNode addOffset1 = new AddNode(m2, field0Size);
                            graph.addWithoutUnique(addOffset1);

                            adNode1.replaceFirstInput(adInput1, addOffset1);

                            for (Node n : nodesToBeDeleted.keySet()) {
                                // System.out.println("==== CASE5: delete " + n);
                                Integer[] count = nodesToBeDeleted.get(n);
                                // if the usages are as many as the occurrences delete
                                if (count[0] == count[1]) {
                                    n.safeDelete();
                                }
                            }
                        }
                    }

                }

                return;
            }

            HashMap<Integer, OCLAddressNode> writeAddressNodes = new HashMap();
            HashMap<Integer, OCLAddressNode> readAddressNodes = new HashMap();

            ValuePhiNode ph = null;
            SignExtendNode signExt = null;

            for (Node n : graph.getNodes()) {
                if (n instanceof ValuePhiNode) {
                    ph = (ValuePhiNode) n;
                }
            }

            if (ph == null)
                return;

            for (Node phUse : ph.usages()) {
                if (phUse instanceof SignExtendNode) {
                    signExt = (SignExtendNode) phUse;
                }
            }

            if (signExt == null) {
                SignExtendNode sgnEx = null;
                for (Node phUse : ph.usages()) {
                    if (phUse instanceof LeftShiftNode) {
                        LeftShiftNode lsh = (LeftShiftNode) phUse;
                        for (Node lshUse : lsh.usages()) {
                            if (lshUse instanceof SignExtendNode) {
                                sgnEx = (SignExtendNode) lshUse;
                                break;
                            }
                        }
                    }
                }
                if (sgnEx == null) {
                    return;
                } else {
                    signExt = (SignExtendNode) sgnEx.copyWithInputs();
                    for (Node in : signExt.inputs()) {
                        signExt.replaceFirstInput(in, ph);
                    }
                }

                if (signExt == null) {
                    System.out.println("NO SIGNEXTEND AFTER PHI!!!");
                    return;
                }
            }

            for (Node n : graph.getNodes()) {
                if (n instanceof FloatingReadNode /*
                                                   * && !((FloatingReadNode)
                                                   * n).stamp(NodeView.DEFAULT).toString().contains("Lorg/apache/flink/")
                                                   */) {
                    OCLAddressNode ocl = (OCLAddressNode) n.inputs().first();
                    returnFieldNumberSingleLoop(ocl, readAddressNodes, ocl, ph);
                }
            }

            if (readAddressNodes.size() == 0) {
                // System.out.println("Oops, no elements in readAddressNodes HashMap!");
                return;
            }

            for (Node n : graph.getNodes()) {
                if (n instanceof WriteNode) {
                    OCLAddressNode ocl = (OCLAddressNode) n.inputs().first();
                    returnFieldNumberSingleLoopWrite(ocl, writeAddressNodes, ocl, ph);
                }
            }

            if (writeAddressNodes.size() == 0) {
                // System.out.println("Oops, no elements in readAddressNodes HashMap!");
                return;
            }

            int tupleSize = fieldSizes.size();

            HashMap<Node, Integer[]> nodesToBeDeleted = new HashMap<>();

            if (tupleSize == 2 && returnTupleSize == 2) {
                // CASE: Tuple2
                if (returnTupleArrayFieldNo == 0) {
                    OCLAddressNode writeAddress = writeAddressNodes.get(0);
                    OCLAddressNode readAddress = null;
                    // TODO: MAKE SURE THIS IS ALWAYS CORRECT
                    WriteNode wr = (WriteNode) writeAddress.usages().first();
                    FloatingReadNode fr = null;
                    for (Node in : wr.inputs()) {
                        if (in instanceof FloatingReadNode) {
                            fr = (FloatingReadNode) in;
                            readAddress = (OCLAddressNode) fr.inputs().first();
                        }
                    }

                    Stamp readStamp = fr.stamp(NodeView.DEFAULT);

                    Constant headerConst = new RawConstant(24);
                    ConstantNode header = new ConstantNode(headerConst, StampFactory.forKind(JavaKind.Long));
                    graph.addWithoutUnique(header);

                    for (Node in : readAddress.inputs()) {
                        if (in instanceof AddNode) {
                            readAddress.replaceFirstInput(in, header);
                        }
                    }

                    for (Node in : writeAddress.inputs()) {
                        if (in instanceof AddNode) {
                            writeAddress.replaceFirstInput(in, header);
                        }
                    }

                    int sizeOfFields;
                    if (differentTypes) {
                        sizeOfFields = arrayFieldTotalBytes + 8;
                    } else {
                        sizeOfFields = arrayFieldTotalBytes + fieldSizes.get(1);
                    }

                    int arrayFieldSize;
                    if (differentTypes) {
                        // padding
                        arrayFieldSize = 8;
                    } else {
                        // since we copy the input array this size should be correct
                        arrayFieldSize = fieldSizes.get(0);
                    }
                    int arrayLength = arrayFieldTotalBytes / arrayFieldSize;

                    identifyNodesToBeDeleted(wr, nodesToBeDeleted);

                    Node pred = wr.predecessor();
                    CopyArrayTupleField cpAr = new CopyArrayTupleField(sizeOfFields, sizeOfFields, arrayFieldSize, arrayLength, ph, readAddress, writeAddress, arrayType, readStamp,
                            returnTupleArrayFieldNo);
                    graph.addWithoutUnique(cpAr);
                    graph.addAfterFixed((FixedWithNextNode) pred, cpAr);

                    wr.replaceAtUsages(cpAr);

                    for (Node n : nodesToBeDeleted.keySet()) {
                        Integer[] count = nodesToBeDeleted.get(n);

                        // if the usages are as many as the occurrences delete
                        if (count[0] == count[1]) {
                            if (n instanceof FixedNode) {
                                removeFixed(n);
                            } else if (!(n instanceof ParameterNode || n instanceof OCLAddressNode)) {
                                n.safeDelete();
                            }
                        }
                    }

                    // --- field 1

                    Constant fieldsSizeConst = new RawConstant(sizeOfFields);
                    ConstantNode fieldsSize = new ConstantNode(fieldsSizeConst, StampFactory.positiveInt());
                    graph.addWithoutUnique(fieldsSize);

                    HashMap<Node, Integer[]> nodesToBeDeleted2 = new HashMap<>();
                    OCLAddressNode ocl1 = readAddressNodes.get(1);
                    AddNode adNode1 = getAddInput(ocl1);

                    Node adInput1 = null;
                    for (Node adin : adNode1.inputs()) {
                        if (!(adin instanceof ConstantNode)) {
                            adInput1 = adin;
                        }
                    }

                    identifyNodesToBeDeleted(adInput1, nodesToBeDeleted2);

                    Constant field0SizeConst = new RawConstant(arrayFieldTotalBytes);
                    ConstantNode field0Size = new ConstantNode(field0SizeConst, StampFactory.positiveInt());
                    graph.addWithoutUnique(field0Size);

                    MulNode m3 = new MulNode(signExt, fieldsSize);
                    graph.addWithoutUnique(m3);
                    AddNode addOffset1 = new AddNode(field0Size, m3);
                    graph.addWithoutUnique(addOffset1);

                    adNode1.replaceFirstInput(adInput1, addOffset1);

                    for (Node n : nodesToBeDeleted2.keySet()) {
                        Integer[] count = nodesToBeDeleted2.get(n);
                        // if the usages are as many as the occurrences delete
                        if (count[0] == count[1]) {
                            // System.out.println("= DELETE " + n);
                            n.safeDelete();
                        }
                    }

                    // -------------
                    OCLAddressNode oclw1 = writeAddressNodes.get(1);
                    AddNode adNodeW = getAddInput(oclw1);
                    HashMap<Node, Integer[]> nodesToBeDeleted3 = new HashMap<>();

                    Node adInputW = null;
                    for (Node adin : adNodeW.inputs()) {
                        if (!(adin instanceof ConstantNode)) {
                            adInputW = adin;
                        }
                    }

                    if (adInputW != null) {
                        identifyNodesToBeDeleted(adInput1, nodesToBeDeleted3);
                    }

                    Constant field0SizeConstW = new RawConstant(returnArrayFieldTotalBytes);
                    ConstantNode field0SizeW = new ConstantNode(field0SizeConstW, StampFactory.positiveInt());
                    graph.addWithoutUnique(field0SizeW);

                    MulNode m3W = new MulNode(signExt, fieldsSize);
                    graph.addWithoutUnique(m3W);
                    AddNode addOffset1W = new AddNode(field0SizeW, m3W);
                    graph.addWithoutUnique(addOffset1W);

                    adNodeW.replaceFirstInput(adInputW, addOffset1W);

                    for (Node n : nodesToBeDeleted3.keySet()) {
                        Integer[] count = nodesToBeDeleted3.get(n);
                        // if the usages are as many as the occurrences delete
                        if (count[0] == count[1]) {
                            n.safeDelete();
                        }
                    }
                    return;
                }
            } else if (tupleSize == 2 && returnTupleSize == 3) {

                if (returnTupleArrayFieldNo == 0) {
                    OCLAddressNode writeAddress = writeAddressNodes.get(0);
                    OCLAddressNode readAddress = null;
                    // TODO: MAKE SURE THIS IS ALWAYS CORRECT
                    WriteNode wr = (WriteNode) writeAddress.usages().first();
                    FloatingReadNode fr = null;
                    for (Node in : wr.inputs()) {
                        if (in instanceof FloatingReadNode) {
                            fr = (FloatingReadNode) in;
                            readAddress = (OCLAddressNode) fr.inputs().first();
                        }
                    }

                    Stamp readStamp = fr.stamp(NodeView.DEFAULT);

                    Constant headerConst = new RawConstant(24);
                    ConstantNode header = new ConstantNode(headerConst, StampFactory.forKind(JavaKind.Long));
                    graph.addWithoutUnique(header);

                    for (Node in : readAddress.inputs()) {
                        if (in instanceof AddNode) {
                            readAddress.replaceFirstInput(in, header);
                        }
                    }

                    for (Node in : writeAddress.inputs()) {
                        if (in instanceof AddNode) {
                            writeAddress.replaceFirstInput(in, header);
                        }
                    }

                    int sizeOfFields;
                    if (differentTypes) {
                        sizeOfFields = arrayFieldTotalBytes + 8;
                    } else {
                        sizeOfFields = arrayFieldTotalBytes + fieldSizes.get(1);
                    }

                    int sizeOfRetFields;
                    if (differentTypesRet) {
                        sizeOfRetFields = arrayFieldTotalBytes + 16;
                    } else {
                        sizeOfRetFields = arrayFieldTotalBytes + fieldSizesRet.get(1) + fieldSizesRet.get(2);
                    }

                    int arrayFieldSize;
                    if (differentTypes) {
                        // padding
                        arrayFieldSize = 8;
                    } else {
                        // since we copy the input array this size should be correct
                        arrayFieldSize = fieldSizes.get(0);
                    }
                    int arrayLength = arrayFieldTotalBytes / arrayFieldSize;

                    identifyNodesToBeDeleted(wr, nodesToBeDeleted);

                    Node pred = wr.predecessor();
                    CopyArrayTupleField cpAr = new CopyArrayTupleField(sizeOfFields, sizeOfRetFields, arrayFieldSize, arrayLength, ph, readAddress, writeAddress, "double", readStamp,
                            returnTupleArrayFieldNo);
                    graph.addWithoutUnique(cpAr);
                    graph.addAfterFixed((FixedWithNextNode) pred, cpAr);

                    wr.replaceAtUsages(cpAr);

                    for (Node n : nodesToBeDeleted.keySet()) {
                        Integer[] count = nodesToBeDeleted.get(n);

                        // if the usages are as many as the occurrences delete
                        if (count[0] == count[1]) {
                            if (n instanceof FixedNode) {
                                removeFixed(n);
                            } else if (!(n instanceof ParameterNode || n instanceof OCLAddressNode)) {
                                n.safeDelete();
                            }
                        }
                    }

                    // ------ Access Field 1
                    Constant fieldsSizeConst = new RawConstant(sizeOfRetFields);
                    ConstantNode fieldsSize = new ConstantNode(fieldsSizeConst, StampFactory.positiveInt());
                    graph.addWithoutUnique(fieldsSize);

                    MulNode m2 = new MulNode(signExt, fieldsSize);
                    graph.addWithoutUnique(m2);
                    HashMap<Node, Integer[]> nodesToBeDeleted2 = new HashMap<>();
                    OCLAddressNode oclw1 = writeAddressNodes.get(1);
                    AddNode adNode1 = getAddInput(oclw1);

                    Node adInput1 = null;
                    for (Node adin : adNode1.inputs()) {
                        if (!(adin instanceof ConstantNode)) {
                            adInput1 = adin;
                        }
                    }

                    if (adInput1 != null) {
                        identifyNodesToBeDeleted(adInput1, nodesToBeDeleted2);
                    }

                    // new nodes
                    Constant field0SizeConst = new RawConstant(arrayFieldTotalBytes);
                    ConstantNode field0Size = new ConstantNode(field0SizeConst, StampFactory.positiveInt());
                    graph.addWithoutUnique(field0Size);

                    AddNode addOffset1 = new AddNode(m2, field0Size);
                    graph.addWithoutUnique(addOffset1);

                    adNode1.replaceFirstInput(adInput1, addOffset1);

                    // ----- Access Field 2
                    HashMap<Node, Integer[]> nodesToBeDeleted3 = new HashMap<>();
                    OCLAddressNode oclw2 = writeAddressNodes.get(2);
                    AddNode adNode2 = getAddInput(oclw2);

                    Node adInput2 = null;
                    for (Node adin : adNode2.inputs()) {
                        if (!(adin instanceof ConstantNode)) {
                            adInput2 = adin;
                        }
                    }

                    if (adInput2 != null) {
                        identifyNodesToBeDeleted(adInput2, nodesToBeDeleted3);
                    }

                    // new nodes
                    int sizeOfField0Field1;
                    // new nodes
                    if (differentTypes) {
                        sizeOfField0Field1 = arrayFieldTotalBytes + 8;
                    } else {
                        sizeOfField0Field1 = arrayFieldTotalBytes + fieldSizes.get(1);
                    }

                    Constant fields0plus1SizeConst = new RawConstant(sizeOfField0Field1);
                    ConstantNode fields01Size = new ConstantNode(fields0plus1SizeConst, StampFactory.positiveInt());
                    graph.addWithoutUnique(fields01Size);

                    AddNode addOffset2 = new AddNode(fields01Size, m2);
                    graph.addWithoutUnique(addOffset2);
                    adNode2.replaceFirstInput(adInput2, addOffset2);

                    for (Node n : nodesToBeDeleted3.keySet()) {
                        Integer[] count = nodesToBeDeleted3.get(n);
                        // if the usages are as many as the occurrences delete
                        if (count[0] == count[1]) {
                            n.safeDelete();
                        }
                    }
                }
            }
        }

        if (!arrayField) {
            if (broadcastedDataset) {
                if (differentTypesInner) {

                    if (fieldSizesInner.size() > 4) {
                        System.out.println("[TornadoTupleOffset phase WARNING]: We currently only support up to Tuple4.");
                        return;
                    }

                    // find outer loop phi node
                    ValuePhiNode parallelPhNode = null;

                    for (Node n : graph.getNodes()) {
                        if (n instanceof ValuePhiNode) {
                            ValuePhiNode ph = (ValuePhiNode) n;
                            for (Node in : ph.inputs()) {
                                if (in instanceof GlobalThreadIdNode) {
                                    parallelPhNode = ph;
                                    break;
                                }
                            }
                            if (parallelPhNode != null)
                                break;
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
                                        break;
                                    }
                                }
                            }
                        }
                    }

                    // ArrayList<OCLAddressNode> readAddressNodes = new ArrayList<>();
                    HashMap<Integer, OCLAddressNode> readAddressNodes = new HashMap();

                    // first, we need to identify the read nodes of the inner loop
                    ArrayList<OCLAddressNode> innerOCLNodes = new ArrayList<>();

                    for (Node n : graph.getNodes()) {
                        if (n instanceof FloatingReadNode && !((FloatingReadNode) n).stamp(NodeView.DEFAULT).toString().contains("Lorg/apache/flink/")) {
                            OCLAddressNode ocl = (OCLAddressNode) n.inputs().first();
                            retInnerOCL(ocl, innerPhi, innerOCLNodes, ocl);
                        }
                    }

                    for (OCLAddressNode n : innerOCLNodes) {
                        returnFieldNumber(n, readAddressNodes, n, innerPhi);
                    }

                    if (readAddressNodes.size() == 0) {
                        // System.out.println("Oops, no elements in readAddressNodes HashMap!");
                        return;
                    }
                    for (int i = 0; i < readAddressNodes.size(); i++) {
                        if (fieldSizesInner.get(i) == 4) {
                            AddNode addNode = null;
                            for (Node oclin : readAddressNodes.get(i).inputs()) {
                                if (oclin instanceof AddNode) {
                                    addNode = (AddNode) oclin;
                                }
                            }

                            LeftShiftNode sh = null;

                            for (Node in : addNode.inputs()) {
                                if (in instanceof LeftShiftNode) {
                                    sh = (LeftShiftNode) in;
                                }
                            }

                            ConstantNode c = null;
                            for (Node in : sh.inputs()) {
                                if (in instanceof ConstantNode) {
                                    // sn2 = (SignExtendNode) in;
                                    c = (ConstantNode) in;
                                }
                            }

                            Constant offset;
                            ConstantNode constOffset;
                            offset = new RawConstant(3);
                            constOffset = new ConstantNode(offset, StampFactory.forKind(JavaKind.Int));
                            graph.addWithoutUnique(constOffset);

                            sh.replaceFirstInput(c, constOffset);

                        }
                    }

                }
            }

            if (differentTypes) {

                // System.out.println("Different Types for outer loop");

                if (fieldSizes.size() > 4) {
                    System.out.println("Input [TornadoTupleOffset phase WARNING]: We currently only support up to Tuple4.");
                    return;
                }

                // ArrayList<OCLAddressNode> readAddressNodes = new ArrayList<>();
                HashMap<Integer, OCLAddressNode> readAddressNodes = new HashMap();

                ValuePhiNode ph = null;

                for (Node n : graph.getNodes()) {
                    if (n instanceof ValuePhiNode) {
                        ph = (ValuePhiNode) n;
                    }
                }

                for (Node n : graph.getNodes()) {
                    if (n instanceof FloatingReadNode && !((FloatingReadNode) n).stamp(NodeView.DEFAULT).toString().contains("Lorg/apache/flink/")) {
                        OCLAddressNode ocl = (OCLAddressNode) n.inputs().first();
                        returnFieldNumberSingleLoop(ocl, readAddressNodes, ocl, ph);
                    }
                }

                if (readAddressNodes.size() == 0) {
                    // System.out.println("Oops, no elements in readAddressNodes HashMap!");
                    return;
                }

                for (int i = 0; i < readAddressNodes.size(); i++) {
                    if (fieldSizes.get(i) == 4) {
                        AddNode addNode = null;
                        for (Node oclin : readAddressNodes.get(i).inputs()) {
                            if (oclin instanceof AddNode) {
                                addNode = (AddNode) oclin;
                            }
                        }

                        LeftShiftNode sh = null;

                        for (Node in : addNode.inputs()) {
                            if (in instanceof LeftShiftNode) {
                                sh = (LeftShiftNode) in;
                            }
                        }

                        ConstantNode c = null;
                        for (Node in : sh.inputs()) {
                            if (in instanceof ConstantNode) {
                                // sn2 = (SignExtendNode) in;
                                c = (ConstantNode) in;
                            }
                        }

                        Constant offset;
                        ConstantNode constOffset;
                        offset = new RawConstant(3);
                        constOffset = new ConstantNode(offset, StampFactory.forKind(JavaKind.Int));
                        graph.addWithoutUnique(constOffset);

                        sh.replaceFirstInput(c, constOffset);

                    }
                }
            }
        }
        if (differentTypesRet) {
            // System.out.println("Return type fields are different!");

            if (fieldSizesRet.size() > 4) {
                System.out.println("Return [TornadoTupleOffset phase WARNING]: We currently only support up to Tuple3.");
                return;
            }

            // ArrayList<OCLAddressNode> readAddressNodes = new ArrayList<>();
            HashMap<Integer, OCLAddressNode> writeAddressNodes = new HashMap();

            for (Node n : graph.getNodes()) {
                if (n instanceof WriteNode) {
                    WriteNode w = (WriteNode) n;
                    String writeFieldType = w.getLocationIdentity().toString();
                    for (int i = 0; i < fieldTypesRet.size(); i++) {
                        if (writeFieldType.contains(fieldTypesRet.get(i))) {
                            writeAddressNodes.put(i, (OCLAddressNode) n.inputs().first());
                            fieldTypesRet.set(i, "used");
                            break;
                        }
                    }
                }
            }

            if (writeAddressNodes.size() == 0) {
                // System.out.println("Oops, no elements in writeAddressNodes HashMap!");
                return;
            }
            for (int i = 0; i < writeAddressNodes.size(); i++) {
                if (fieldSizesRet.get(i) == 4) {
                    AddNode addNode = null;
                    for (Node oclin : writeAddressNodes.get(i).inputs()) {
                        if (oclin instanceof AddNode) {
                            addNode = (AddNode) oclin;
                        }
                    }

                    LeftShiftNode sh = null;

                    for (Node in : addNode.inputs()) {
                        if (in instanceof LeftShiftNode) {
                            sh = (LeftShiftNode) in;
                        }
                    }

                    ConstantNode c = null;
                    for (Node in : sh.inputs()) {
                        if (in instanceof ConstantNode) {
                            // sn2 = (SignExtendNode) in;
                            c = (ConstantNode) in;
                        }
                    }

                    Constant offset;
                    ConstantNode constOffset;
                    offset = new RawConstant(3);
                    constOffset = new ConstantNode(offset, StampFactory.forKind(JavaKind.Int));
                    graph.addWithoutUnique(constOffset);

                    sh.replaceFirstInput(c, constOffset);

                }
            }

        }

    }
}
