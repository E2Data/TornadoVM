package uk.ac.manchester.tornado.drivers.opencl.graal.phases;

import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.RawConstant;

import org.graalvm.compiler.core.common.type.Stamp;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.*;
import org.graalvm.compiler.nodes.calc.AddNode;
import org.graalvm.compiler.nodes.calc.LeftShiftNode;
import org.graalvm.compiler.nodes.calc.MulNode;
import org.graalvm.compiler.nodes.calc.SignExtendNode;
import org.graalvm.compiler.nodes.memory.FloatingReadNode;
import org.graalvm.compiler.nodes.memory.WriteNode;
import org.graalvm.compiler.phases.Phase;

import uk.ac.manchester.tornado.api.flink.FlinkCompilerInfo;
import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLAddressNode;
import uk.ac.manchester.tornado.drivers.opencl.graal.nodes.CopyArrayTupleField;
import uk.ac.manchester.tornado.drivers.opencl.graal.nodes.GlobalThreadIdNode;
import uk.ac.manchester.tornado.runtime.FlinkCompilerInfoIntermediate;

import java.util.ArrayList;
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

    private boolean broadcastedDataset;

    static boolean arrayIteration;
    static int arrayFieldIndex;

    static boolean copyArray;

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

    public static AddNode getAddInput(HashMap<Integer, OCLAddressNode> readAddressNodes, int pos) {
        AddNode adNode = null;
        OCLAddressNode ocl = readAddressNodes.get(pos);
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
                // TODO
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
                        AddNode adNode = getAddInput(readAddressNodes, 0);

                        int numOfOCL = 0;
                        for (Node addUse : adNode.usages()) {
                            if (addUse instanceof OCLAddressNode) {
                                numOfOCL++;
                            }
                        }
                        AddNode adNode0;
                        if (numOfOCL > 1) {
                            adNode0 = (AddNode) adNode.copyWithInputs();
                            OCLAddressNode ocl = readAddressNodes.get(0);
                            FloatingReadNode fr = null;
                            for (Node us : ocl.usages()) {
                                if (us instanceof FloatingReadNode) {
                                    fr = (FloatingReadNode) us;
                                }
                            }

                            OCLAddressNode ocln = (OCLAddressNode) ocl.copyWithInputs();
                            ocln.replaceFirstInput(adNode, adNode0);
                            if (fr != null) {
                                fr.replaceFirstInput(ocl, ocln);
                            } else {
                                System.out.println("Floating Read Node is NULL");
                            }
                            // update hashmap
                            readAddressNodes.replace(0, ocl, ocln);
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
                        AddNode adNode1 = getAddInput(readAddressNodes, 1);

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
                            System.out.println("differentTypesRet: " + differentTypesRet + " returnArrayField: " + returnArrayField);
                            return;
                        }
                        // return;

                    } else if (tupleArrayFieldNo == 1) {
                        // if the second field of the Tuple2 is an array
                        // ----- Access Field 0
                        AddNode adNode = getAddInput(readAddressNodes, 0);

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
                            System.out.println("More than one OCLNode");
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
                        AddNode adNode1 = getAddInput(readAddressNodes, 1);

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
                        AddNode adNode0 = getAddInput(readAddressNodes, 0);

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
                        AddNode adNode1 = getAddInput(readAddressNodes, 1);

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
                        AddNode adNode2 = getAddInput(readAddressNodes, 2);

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
                        AddNode adNode = getAddInput(readAddressNodes, 0);
                        int numOfOCL = 0;
                        for (Node addUse : adNode.usages()) {
                            if (addUse instanceof OCLAddressNode) {
                                numOfOCL++;
                            }
                        }
                        AddNode adNode0;
                        if (numOfOCL > 1) {
                            System.out.println("More than one OCLNode");
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
                        AddNode adNode1 = getAddInput(readAddressNodes, 1);

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
                        AddNode adNode2 = getAddInput(readAddressNodes, 2);

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
                        AddNode adNode0 = getAddInput(readAddressNodes, 0);

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
                        AddNode adNode1 = getAddInput(readAddressNodes, 1);

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
                        AddNode adNode2 = getAddInput(readAddressNodes, 2);

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
                    AddNode adNode = getAddInput(writeAddressNodes, 0);

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
                    AddNode adNode1 = getAddInput(writeAddressNodes, 1);

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

        // System.out.println("copyArray: " + copyArray);
        if (copyArray && arrayField) {
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
                    AddNode adNode1 = getAddInput(readAddressNodes, 1);

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
                    AddNode adNodeW = getAddInput(writeAddressNodes, 1);
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
                // System.out.println("** Input Tuple2 output Tuple3");

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
                    AddNode adNode1 = getAddInput(writeAddressNodes, 1);

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
                    AddNode adNode2 = getAddInput(writeAddressNodes, 2);

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
