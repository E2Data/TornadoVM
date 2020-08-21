package uk.ac.manchester.tornado.drivers.opencl.graal.phases;

import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.RawConstant;

import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.PhiNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValuePhiNode;
import org.graalvm.compiler.nodes.calc.AddNode;
import org.graalvm.compiler.nodes.calc.LeftShiftNode;
import org.graalvm.compiler.nodes.calc.MulNode;
import org.graalvm.compiler.nodes.calc.SignExtendNode;
import org.graalvm.compiler.nodes.memory.FloatingReadNode;
import org.graalvm.compiler.nodes.memory.WriteNode;
import org.graalvm.compiler.phases.Phase;

import uk.ac.manchester.tornado.api.flink.FlinkCompilerInfo;
import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLAddressNode;
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

    private boolean broadcastedDataset;

    static boolean arrayIteration;
    static int arrayFieldIndex;

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

    @Override
    protected void run(StructuredGraph graph) {

        FlinkCompilerInfo fcomp = FlinkCompilerInfoIntermediate.getFlinkCompilerInfo();
        if (fcomp != null) {
            flinkSetCompInfo(fcomp);
        }

        if (arrayField) {
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
                    System.out.println("NO SIGNEXTEND AFTER PHI!!!");
                    return;
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
                int tupleSize = fieldSizes.size();
                if (tupleSize == 2) {
                    HashMap<Node, Integer[]> nodesToBeDeleted = new HashMap<>();
                    // CASE: Tuple2
                    if (tupleArrayFieldNo == 0) {
                        // if the first field of the Tuple2 is an array
                        // ----- Access Field 0
                        AddNode adNode0 = null;
                        OCLAddressNode ocl0 = readAddressNodes.get(0);
                        for (Node in : ocl0.inputs()) {
                            if (in instanceof AddNode) {
                                adNode0 = (AddNode) in;
                                break;
                            }
                        }

                        if (adNode0 == null) {
                            System.out.println("ERROR: CASE NOT TAKEN INTO ACCOUNT");
                            return;
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
                        AddNode adNode1 = null;
                        OCLAddressNode ocl1 = readAddressNodes.get(1);
                        for (Node in : ocl1.inputs()) {
                            if (in instanceof AddNode) {
                                adNode1 = (AddNode) in;
                                break;
                            }
                        }

                        if (adNode1 == null) {
                            System.out.println("ERROR: CASE NOT TAKEN INTO ACCOUNT");
                            return;
                        }

                        Node adInput1 = null;
                        for (Node adin : adNode1.inputs()) {
                            if (!(adin instanceof ConstantNode)) {
                                adInput1 = adin;
                            }
                        }

                        if (adInput1 != null) {
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

                        for (Node n : nodesToBeDeleted.keySet()) {
                            Integer[] count = nodesToBeDeleted.get(n);
                            // if the usages are as many as the occurrences delete
                            if (count[0] == count[1]) {
                                n.safeDelete();
                            }
                        }
                        return;

                    } else if (tupleArrayFieldNo == 1) {
                        // if the second field of the Tuple2 is an array
                        // ----- Access Field 0
                        AddNode adNode0 = null;
                        OCLAddressNode ocl0 = readAddressNodes.get(0);
                        for (Node in : ocl0.inputs()) {
                            if (in instanceof AddNode) {
                                adNode0 = (AddNode) in;
                                break;
                            }
                        }

                        if (adNode0 == null) {
                            System.out.println("ERROR: CASE NOT TAKEN INTO ACCOUNT");
                            return;
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
                        AddNode adNode1 = null;
                        OCLAddressNode ocl1 = readAddressNodes.get(1);
                        for (Node in : ocl1.inputs()) {
                            if (in instanceof AddNode) {
                                adNode1 = (AddNode) in;
                                break;
                            }
                        }

                        if (adNode1 == null) {
                            System.out.println("ERROR: CASE NOT TAKEN INTO ACCOUNT");
                            return;
                        }

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
                            arrayFieldSizeConst = new RawConstant(fieldSizes.get(0));
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
                            if (count[0] == count[1]) {
                                n.safeDelete();
                            }
                        }

                        return;
                    }
                } else if (tupleSize == 3) {
                    // CASE: Tuple3
                }
            }
            return;
        }
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
