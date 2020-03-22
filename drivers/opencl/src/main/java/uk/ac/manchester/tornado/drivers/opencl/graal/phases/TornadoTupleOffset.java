package uk.ac.manchester.tornado.drivers.opencl.graal.phases;

import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.RawConstant;

import org.graalvm.compiler.core.common.type.ArithmeticOpTable;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.graph.iterators.NodeIterable;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValuePhiNode;
import org.graalvm.compiler.nodes.calc.AddNode;
import org.graalvm.compiler.nodes.calc.LeftShiftNode;
import org.graalvm.compiler.nodes.calc.MulNode;
import org.graalvm.compiler.nodes.calc.SignExtendNode;
import org.graalvm.compiler.nodes.memory.FloatingReadNode;
import org.graalvm.compiler.nodes.memory.MemoryPhiNode;
import org.graalvm.compiler.nodes.memory.WriteNode;
import org.graalvm.compiler.phases.Phase;

import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLAddressNode;
import uk.ac.manchester.tornado.drivers.opencl.graal.nodes.GlobalThreadIdNode;

import java.util.ArrayList;
import java.util.HashMap;

public class TornadoTupleOffset extends Phase {

    public static boolean differentTypes = false;
    public static ArrayList<Integer> fieldSizes = new ArrayList<>();
    public static ArrayList<String> fieldTypes = new ArrayList<>();
    // -- for KMeans
    public static boolean differentTypesInner = false;
    public static ArrayList<Integer> fieldSizesInner = new ArrayList<>();
    public static ArrayList<String> fieldTypesInner = new ArrayList<>();
    // --
    public static boolean differentTypesRet = false;
    public static ArrayList<Integer> fieldSizesRet = new ArrayList<>();
    public static ArrayList<String> fieldTypesRet = new ArrayList<>();

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
                } // else {
                in.replaceFirstInput(in.inputs().first(), ph);

                /*
                 * for (Node adin : ad.inputs()) { if (!(adin instanceof ConstantNode)) {
                 * in.replaceFirstInput(ad, adin); } } for (Node adin : ad.inputs()) {
                 * adin.safeDelete(); } ad.safeDelete();
                 */
                // }
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
                } // else {
                in.replaceFirstInput(in.inputs().first(), ph);

                /*
                 * for (Node adin : ad.inputs()) { if (!(adin instanceof ConstantNode)) {
                 * in.replaceFirstInput(ad, adin); } } for (Node adin : ad.inputs()) {
                 * adin.safeDelete(); } ad.safeDelete();
                 */
                // }
                return;
            } else {
                returnFieldNumberSingleLoop(in, orderedOCL, ocl, ph);
            }
        }
    }

    @Override
    protected void run(StructuredGraph graph) {

        if (TornadoCollectionElimination.broadcastedDataset) {
            if (differentTypesInner) {
                boolean isTuple2 = false;
                boolean isTuple3 = false;
                boolean isTuple4 = false;

                if (fieldSizesInner.size() == 2) {
                    isTuple2 = true;
                } else if (fieldSizesInner.size() == 3) {
                    isTuple3 = true;
                } else if (fieldSizesInner.size() == 4) {
                    // return;
                    isTuple4 = true;
                } else {
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

                AddNode add = null;
                AddNode add2 = null;

                for (Node oclin : readAddressNodes.get(0).inputs()) {
                    if (oclin instanceof AddNode) {
                        add = (AddNode) oclin;
                    }
                }

                for (Node oclin : readAddressNodes.get(1).inputs()) {
                    if (oclin instanceof AddNode) {
                        add2 = (AddNode) oclin;
                    }
                }

                // if input is Tuple3 get input of 3rd readnode
                AddNode add3 = null;
                if (isTuple3 || isTuple4) {
                    for (Node oclin : readAddressNodes.get(2).inputs()) {
                        if (oclin instanceof AddNode) {
                            add3 = (AddNode) oclin;
                        }
                    }
                }

                AddNode add4 = null;
                if (isTuple4) {
                    for (Node oclin : readAddressNodes.get(3).inputs()) {
                        if (oclin instanceof AddNode) {
                            add4 = (AddNode) oclin;
                        }
                    }
                }

                if (isTuple2) {
                    Constant firstOffset;
                    ConstantNode firstConstOffset;
                    firstOffset = new RawConstant(fieldSizesInner.get(0));
                    firstConstOffset = new ConstantNode(firstOffset, StampFactory.forKind(JavaKind.Byte));
                    graph.addWithoutUnique(firstConstOffset);

                    Constant secondOffset;
                    ConstantNode secondConstOffset;
                    secondOffset = new RawConstant(fieldSizesInner.get(1));
                    secondConstOffset = new ConstantNode(secondOffset, StampFactory.forKind(JavaKind.Byte));
                    graph.addWithoutUnique(secondConstOffset);

                    // first offset: oclAddress + i*sizeOfSecondField
                    MulNode multOffFirst = new MulNode(innerPhi, secondConstOffset);
                    graph.addWithoutUnique(multOffFirst);

                    AddNode addOffFirst = new AddNode(multOffFirst, add);
                    graph.addWithoutUnique(addOffFirst);

                    readAddressNodes.get(0).replaceFirstInput(add, addOffFirst);
                    // ----

                    // second offset: oclAddress + (sizeOfFirstField + i*sizeOfFirstField)
                    MulNode mulOffSec = new MulNode(innerPhi, firstConstOffset);
                    graph.addWithoutUnique(mulOffSec);

                    AddNode addExtraOffSecond = new AddNode(firstConstOffset, mulOffSec);
                    graph.addWithoutUnique(addExtraOffSecond);

                    AddNode addOffSec = new AddNode(addExtraOffSecond, add2);
                    graph.addWithoutUnique(addOffSec);

                    readAddressNodes.get(1).replaceFirstInput(add2, addOffSec);
                } else if (isTuple3) {
                    // if tuple3

                    // ----- (sizeOf(field1) + sizeOf(field2))
                    // constant for (sizeOf(field1) + sizeOf(field2))
                    Constant firstOffset;
                    ConstantNode firstConstOffset;
                    firstOffset = new RawConstant(fieldSizesInner.get(1) + fieldSizesInner.get(2));
                    firstConstOffset = new ConstantNode(firstOffset, StampFactory.forKind(JavaKind.Byte));
                    graph.addWithoutUnique(firstConstOffset);

                    // ----- sizeOf(field0) + (sizeOf(field0) + sizeOf(field2))
                    // constant for sizeOf(field0)
                    Constant secondOffset;
                    ConstantNode secondConstOffset;
                    secondOffset = new RawConstant(fieldSizesInner.get(0));
                    secondConstOffset = new ConstantNode(secondOffset, StampFactory.forKind(JavaKind.Byte));
                    graph.addWithoutUnique(secondConstOffset);
                    // constant for (sizeOf(field0) + sizeOf(field2))
                    Constant secondOffsetMul;
                    ConstantNode secondConstOffsetMul;
                    secondOffsetMul = new RawConstant(fieldSizesInner.get(0) + fieldSizesInner.get(2));
                    secondConstOffsetMul = new ConstantNode(secondOffsetMul, StampFactory.forKind(JavaKind.Byte));
                    graph.addWithoutUnique(secondConstOffsetMul);

                    // ----- sizeOf(fieldO) + sizeOf(field1) + (sizeOf(field0) + sizeOf(field1))
                    // create sizeOf(field0) + sizeOf(field1)
                    Constant thirdOffset;
                    ConstantNode thirdConstOffset;
                    thirdOffset = new RawConstant(fieldSizesInner.get(0) + fieldSizesInner.get(1));
                    thirdConstOffset = new ConstantNode(thirdOffset, StampFactory.forKind(JavaKind.Byte));
                    graph.addWithoutUnique(thirdConstOffset);
                    // --------------------------------------

                    // first offset: oclAddress + (sizeOf(field1) + sizeOf(field2))*i
                    MulNode multOffFirst = new MulNode(innerPhi, firstConstOffset);
                    graph.addWithoutUnique(multOffFirst);

                    AddNode addOffFirst = new AddNode(multOffFirst, add);
                    graph.addWithoutUnique(addOffFirst);

                    readAddressNodes.get(0).replaceFirstInput(add, addOffFirst);
                    // ----

                    // second offset: oclAddress + (sizeOf(field0) + (sizeOf(field0) +
                    // sizeOf(field2))*i)
                    MulNode mulOffSec = new MulNode(innerPhi, secondConstOffsetMul);
                    graph.addWithoutUnique(mulOffSec);

                    AddNode addExtraOffSecond = new AddNode(secondConstOffset, mulOffSec);
                    graph.addWithoutUnique(addExtraOffSecond);

                    AddNode addOffSec = new AddNode(addExtraOffSecond, add2);
                    graph.addWithoutUnique(addOffSec);

                    readAddressNodes.get(1).replaceFirstInput(add2, addOffSec);

                    // third offset: oclAddress + (sizeOf(fieldO) + sizeOf(field1) + (sizeOf(field0)
                    // + sizeOf(field1)*i)
                    MulNode mulOffThird = new MulNode(innerPhi, thirdConstOffset);
                    graph.addWithoutUnique(mulOffThird);

                    AddNode addExtraOffThird = new AddNode(thirdConstOffset, mulOffThird);
                    graph.addWithoutUnique(addExtraOffThird);

                    AddNode addOffThird = new AddNode(addExtraOffThird, add3);
                    graph.addWithoutUnique(addOffThird);

                    readAddressNodes.get(2).replaceFirstInput(add3, addOffThird);

                } else {
                    // if Tuple4
                    // ----- (sizeOf(field1) + sizeOf(field2) + sizeOf(field3))
                    // constant for (sizeOf(field1) + sizeOf(field2) + sizeOf(field3))
                    Constant firstOffset;
                    ConstantNode firstConstOffset;
                    firstOffset = new RawConstant(fieldSizesInner.get(1) + fieldSizesInner.get(2) + fieldSizesInner.get(3));
                    firstConstOffset = new ConstantNode(firstOffset, StampFactory.forKind(JavaKind.Byte));
                    graph.addWithoutUnique(firstConstOffset);

                    // ----- sizeOf(field0) + (sizeOf(field0) + sizeOf(field2) + sizeOf(field3))
                    // constant for sizeOf(field0)
                    Constant secondOffset;
                    ConstantNode secondConstOffset;
                    secondOffset = new RawConstant(fieldSizesInner.get(0));
                    secondConstOffset = new ConstantNode(secondOffset, StampFactory.forKind(JavaKind.Byte));
                    graph.addWithoutUnique(secondConstOffset);
                    // constant for (sizeOf(field0) + sizeOf(field2) + sizeOf(field3))
                    Constant secondOffsetMul;
                    ConstantNode secondConstOffsetMul;
                    secondOffsetMul = new RawConstant(fieldSizesInner.get(0) + fieldSizesInner.get(2) + fieldSizesInner.get(3));
                    secondConstOffsetMul = new ConstantNode(secondOffsetMul, StampFactory.forKind(JavaKind.Byte));
                    graph.addWithoutUnique(secondConstOffsetMul);

                    // ----- sizeOf(fieldO) + sizeOf(field1) + (sizeOf(field3) + sizeOf(field0) +
                    // sizeOf(field1))
                    // create sizeOf(field0) + sizeOf(field1)
                    Constant thirdOffset;
                    ConstantNode thirdConstOffset;
                    thirdOffset = new RawConstant(fieldSizesInner.get(0) + fieldSizesInner.get(1));
                    thirdConstOffset = new ConstantNode(thirdOffset, StampFactory.forKind(JavaKind.Byte));
                    graph.addWithoutUnique(thirdConstOffset);
                    Constant thirdOffsetMul;
                    // create sizeOf(field3) + sizeOf(field0) + sizeOf(field1)
                    ConstantNode thirdConstOffsetMul;
                    thirdOffsetMul = new RawConstant(fieldSizesInner.get(3) + fieldSizesInner.get(0) + fieldSizesInner.get(1));
                    thirdConstOffsetMul = new ConstantNode(thirdOffsetMul, StampFactory.forKind(JavaKind.Byte));
                    graph.addWithoutUnique(thirdConstOffsetMul);

                    // ----- sizeOf(fieldO) + sizeOf(field1) + sizeOf(field2) + (sizeOf(fieldO) +
                    // sizeOf(field1) + sizeOf(field2))
                    // create sizeOf(fieldO) + sizeOf(field1) + sizeOf(field2)
                    Constant fourthOffset;
                    ConstantNode fourthConstOffset;
                    fourthOffset = new RawConstant(fieldSizesInner.get(0) + fieldSizesInner.get(1) + fieldSizesInner.get(2));
                    fourthConstOffset = new ConstantNode(fourthOffset, StampFactory.forKind(JavaKind.Byte));
                    graph.addWithoutUnique(fourthConstOffset);
                    // --------------------------------------

                    // first offset: oclAddress + (sizeOf(field1) + sizeOf(field2) +
                    // sizeOf(field3))*i
                    MulNode multOffFirst = new MulNode(innerPhi, firstConstOffset);
                    graph.addWithoutUnique(multOffFirst);

                    AddNode addOffFirst = new AddNode(multOffFirst, add);
                    graph.addWithoutUnique(addOffFirst);

                    readAddressNodes.get(0).replaceFirstInput(add, addOffFirst);
                    // ----

                    // second offset: oclAddress + (sizeOf(field0) + (sizeOf(field0) +
                    // sizeOf(field2) + field3)*i)
                    MulNode mulOffSec = new MulNode(innerPhi, secondConstOffsetMul);
                    graph.addWithoutUnique(mulOffSec);

                    AddNode addExtraOffSecond = new AddNode(secondConstOffset, mulOffSec);
                    graph.addWithoutUnique(addExtraOffSecond);

                    AddNode addOffSec = new AddNode(addExtraOffSecond, add2);
                    graph.addWithoutUnique(addOffSec);

                    readAddressNodes.get(1).replaceFirstInput(add2, addOffSec);

                    // third offset: oclAddress + (sizeOf(fieldO) + sizeOf(field1) + (sizeOf(field3)
                    // + sizeOf(field0)
                    // + sizeOf(field1)*i)
                    MulNode mulOffThird = new MulNode(innerPhi, thirdConstOffsetMul);
                    graph.addWithoutUnique(mulOffThird);

                    AddNode addExtraOffThird = new AddNode(thirdConstOffset, mulOffThird);
                    graph.addWithoutUnique(addExtraOffThird);

                    AddNode addOffThird = new AddNode(addExtraOffThird, add3);
                    graph.addWithoutUnique(addOffThird);

                    readAddressNodes.get(2).replaceFirstInput(add3, addOffThird);

                    // fourth offset: oclAddress + (sizeOf(fieldO) + sizeOf(field1) + sizeOf(field2)
                    // + (sizeOf(fieldO) + sizeOf(field1) + sizeOf(field2))*i)
                    MulNode mulOffFourth = new MulNode(innerPhi, fourthConstOffset);
                    graph.addWithoutUnique(mulOffFourth);

                    AddNode addExtraOffFourth = new AddNode(fourthConstOffset, mulOffFourth);
                    graph.addWithoutUnique(addExtraOffFourth);

                    AddNode addOffFourth = new AddNode(addExtraOffFourth, add4);
                    graph.addWithoutUnique(addOffFourth);

                    readAddressNodes.get(3).replaceFirstInput(add4, addOffFourth);

                }
            }
        }

        if (differentTypes) {

            // System.out.println("Different Types for outer loop");
            boolean isTuple2 = false;
            boolean isTuple3 = false;
            boolean isTuple4 = false;

            if (fieldSizes.size() == 2) {
                isTuple2 = true;
            } else if (fieldSizes.size() == 3) {
                isTuple3 = true;
            } else if (fieldSizes.size() == 4) {
                // return;
                isTuple4 = true;
            } else {
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

            AddNode add = null;
            AddNode add2 = null;

            for (Node oclin : readAddressNodes.get(0).inputs()) {
                if (oclin instanceof AddNode) {
                    add = (AddNode) oclin;
                }
            }

            for (Node oclin : readAddressNodes.get(1).inputs()) {
                if (oclin instanceof AddNode) {
                    add2 = (AddNode) oclin;
                }
            }

            // if input is Tuple3 get input of 3rd readnode
            AddNode add3 = null;
            if (isTuple3 || isTuple4) {
                for (Node oclin : readAddressNodes.get(2).inputs()) {
                    if (oclin instanceof AddNode) {
                        add3 = (AddNode) oclin;
                    }
                }
            }

            AddNode add4 = null;
            if (isTuple4) {
                for (Node oclin : readAddressNodes.get(3).inputs()) {
                    if (oclin instanceof AddNode) {
                        add4 = (AddNode) oclin;
                    }
                }
            }

            if (isTuple2) {
                Constant firstOffset;
                ConstantNode firstConstOffset;
                firstOffset = new RawConstant(fieldSizes.get(0));
                firstConstOffset = new ConstantNode(firstOffset, StampFactory.forKind(JavaKind.Byte));
                graph.addWithoutUnique(firstConstOffset);

                Constant secondOffset;
                ConstantNode secondConstOffset;
                secondOffset = new RawConstant(fieldSizes.get(1));
                secondConstOffset = new ConstantNode(secondOffset, StampFactory.forKind(JavaKind.Byte));
                graph.addWithoutUnique(secondConstOffset);

                // first offset: oclAddress + i*sizeOfSecondField
                MulNode multOffFirst = new MulNode(ph, secondConstOffset);
                graph.addWithoutUnique(multOffFirst);

                AddNode addOffFirst = new AddNode(multOffFirst, add);
                graph.addWithoutUnique(addOffFirst);

                readAddressNodes.get(0).replaceFirstInput(add, addOffFirst);
                // ----

                // second offset: oclAddress + (sizeOfFirstField + i*sizeOfFirstField)
                MulNode mulOffSec = new MulNode(ph, firstConstOffset);
                graph.addWithoutUnique(mulOffSec);

                AddNode addExtraOffSecond = new AddNode(firstConstOffset, mulOffSec);
                graph.addWithoutUnique(addExtraOffSecond);

                AddNode addOffSec = new AddNode(addExtraOffSecond, add2);
                graph.addWithoutUnique(addOffSec);

                readAddressNodes.get(1).replaceFirstInput(add2, addOffSec);
            } else if (isTuple3) {
                // if tuple3

                // ----- (sizeOf(field1) + sizeOf(field2))
                // constant for (sizeOf(field1) + sizeOf(field2))
                Constant firstOffset;
                ConstantNode firstConstOffset;
                firstOffset = new RawConstant(fieldSizes.get(1) + fieldSizes.get(2));
                firstConstOffset = new ConstantNode(firstOffset, StampFactory.forKind(JavaKind.Byte));
                graph.addWithoutUnique(firstConstOffset);

                // ----- sizeOf(field0) + (sizeOf(field0) + sizeOf(field2))
                // constant for sizeOf(field0)
                Constant secondOffset;
                ConstantNode secondConstOffset;
                secondOffset = new RawConstant(fieldSizes.get(0));
                secondConstOffset = new ConstantNode(secondOffset, StampFactory.forKind(JavaKind.Byte));
                graph.addWithoutUnique(secondConstOffset);
                // constant for (sizeOf(field0) + sizeOf(field2))
                Constant secondOffsetMul;
                ConstantNode secondConstOffsetMul;
                secondOffsetMul = new RawConstant(fieldSizes.get(0) + fieldSizes.get(2));
                secondConstOffsetMul = new ConstantNode(secondOffsetMul, StampFactory.forKind(JavaKind.Byte));
                graph.addWithoutUnique(secondConstOffsetMul);

                // ----- sizeOf(fieldO) + sizeOf(field1) + (sizeOf(field0) + sizeOf(field1))
                // create sizeOf(field0) + sizeOf(field1)
                Constant thirdOffset;
                ConstantNode thirdConstOffset;
                thirdOffset = new RawConstant(fieldSizes.get(0) + fieldSizes.get(1));
                thirdConstOffset = new ConstantNode(thirdOffset, StampFactory.forKind(JavaKind.Byte));
                graph.addWithoutUnique(thirdConstOffset);
                // --------------------------------------

                // first offset: oclAddress + (sizeOf(field1) + sizeOf(field2))*i
                MulNode multOffFirst = new MulNode(ph, firstConstOffset);
                graph.addWithoutUnique(multOffFirst);

                AddNode addOffFirst = new AddNode(multOffFirst, add);
                graph.addWithoutUnique(addOffFirst);

                readAddressNodes.get(0).replaceFirstInput(add, addOffFirst);
                // ----

                // second offset: oclAddress + (sizeOf(field0) + (sizeOf(field0) +
                // sizeOf(field2))*i)
                MulNode mulOffSec = new MulNode(ph, secondConstOffsetMul);
                graph.addWithoutUnique(mulOffSec);

                AddNode addExtraOffSecond = new AddNode(secondConstOffset, mulOffSec);
                graph.addWithoutUnique(addExtraOffSecond);

                AddNode addOffSec = new AddNode(addExtraOffSecond, add2);
                graph.addWithoutUnique(addOffSec);

                readAddressNodes.get(1).replaceFirstInput(add2, addOffSec);

                // third offset: oclAddress + (sizeOf(fieldO) + sizeOf(field1) + (sizeOf(field0)
                // + sizeOf(field1)*i)
                MulNode mulOffThird = new MulNode(ph, thirdConstOffset);
                graph.addWithoutUnique(mulOffThird);

                AddNode addExtraOffThird = new AddNode(thirdConstOffset, mulOffThird);
                graph.addWithoutUnique(addExtraOffThird);

                AddNode addOffThird = new AddNode(addExtraOffThird, add3);
                graph.addWithoutUnique(addOffThird);

                readAddressNodes.get(2).replaceFirstInput(add3, addOffThird);

                /*
                 * for (Node n : graph.getNodes()) { if (n instanceof SignExtendNode) {
                 * n.replaceFirstInput(n.inputs().first(), ph); } }
                 */

            } else {
                // if Tuple4
                // ----- (sizeOf(field1) + sizeOf(field2) + sizeOf(field3))
                // constant for (sizeOf(field1) + sizeOf(field2) + sizeOf(field3))
                Constant firstOffset;
                ConstantNode firstConstOffset;
                firstOffset = new RawConstant(fieldSizes.get(1) + fieldSizes.get(2) + fieldSizes.get(3));
                firstConstOffset = new ConstantNode(firstOffset, StampFactory.forKind(JavaKind.Byte));
                graph.addWithoutUnique(firstConstOffset);

                // ----- sizeOf(field0) + (sizeOf(field0) + sizeOf(field2) + sizeOf(field3))
                // constant for sizeOf(field0)
                Constant secondOffset;
                ConstantNode secondConstOffset;
                secondOffset = new RawConstant(fieldSizes.get(0));
                secondConstOffset = new ConstantNode(secondOffset, StampFactory.forKind(JavaKind.Byte));
                graph.addWithoutUnique(secondConstOffset);
                // constant for (sizeOf(field0) + sizeOf(field2) + sizeOf(field3))
                Constant secondOffsetMul;
                ConstantNode secondConstOffsetMul;
                secondOffsetMul = new RawConstant(fieldSizes.get(0) + fieldSizes.get(2) + fieldSizes.get(3));
                secondConstOffsetMul = new ConstantNode(secondOffsetMul, StampFactory.forKind(JavaKind.Byte));
                graph.addWithoutUnique(secondConstOffsetMul);

                // ----- sizeOf(fieldO) + sizeOf(field1) + (sizeOf(field3) + sizeOf(field0) +
                // sizeOf(field1))
                // create sizeOf(field0) + sizeOf(field1)
                Constant thirdOffset;
                ConstantNode thirdConstOffset;
                thirdOffset = new RawConstant(fieldSizes.get(0) + fieldSizes.get(1));
                thirdConstOffset = new ConstantNode(thirdOffset, StampFactory.forKind(JavaKind.Byte));
                graph.addWithoutUnique(thirdConstOffset);
                Constant thirdOffsetMul;
                // create sizeOf(field3) + sizeOf(field0) + sizeOf(field1)
                ConstantNode thirdConstOffsetMul;
                thirdOffsetMul = new RawConstant(fieldSizes.get(3) + fieldSizes.get(0) + fieldSizes.get(1));
                thirdConstOffsetMul = new ConstantNode(thirdOffsetMul, StampFactory.forKind(JavaKind.Byte));
                graph.addWithoutUnique(thirdConstOffsetMul);

                // ----- sizeOf(fieldO) + sizeOf(field1) + sizeOf(field2) + (sizeOf(fieldO) +
                // sizeOf(field1) + sizeOf(field2))
                // create sizeOf(fieldO) + sizeOf(field1) + sizeOf(field2)
                Constant fourthOffset;
                ConstantNode fourthConstOffset;
                fourthOffset = new RawConstant(fieldSizes.get(0) + fieldSizes.get(1) + fieldSizes.get(2));
                fourthConstOffset = new ConstantNode(fourthOffset, StampFactory.forKind(JavaKind.Byte));
                graph.addWithoutUnique(fourthConstOffset);
                // --------------------------------------

                // first offset: oclAddress + (sizeOf(field1) + sizeOf(field2) +
                // sizeOf(field3))*i
                MulNode multOffFirst = new MulNode(ph, firstConstOffset);
                graph.addWithoutUnique(multOffFirst);

                AddNode addOffFirst = new AddNode(multOffFirst, add);
                graph.addWithoutUnique(addOffFirst);

                readAddressNodes.get(0).replaceFirstInput(add, addOffFirst);
                // ----

                // second offset: oclAddress + (sizeOf(field0) + (sizeOf(field0) +
                // sizeOf(field2) + field3)*i)
                MulNode mulOffSec = new MulNode(ph, secondConstOffsetMul);
                graph.addWithoutUnique(mulOffSec);

                AddNode addExtraOffSecond = new AddNode(secondConstOffset, mulOffSec);
                graph.addWithoutUnique(addExtraOffSecond);

                AddNode addOffSec = new AddNode(addExtraOffSecond, add2);
                graph.addWithoutUnique(addOffSec);

                readAddressNodes.get(1).replaceFirstInput(add2, addOffSec);

                // third offset: oclAddress + (sizeOf(fieldO) + sizeOf(field1) + (sizeOf(field3)
                // + sizeOf(field0)
                // + sizeOf(field1)*i)
                MulNode mulOffThird = new MulNode(ph, thirdConstOffsetMul);
                graph.addWithoutUnique(mulOffThird);

                AddNode addExtraOffThird = new AddNode(thirdConstOffset, mulOffThird);
                graph.addWithoutUnique(addExtraOffThird);

                AddNode addOffThird = new AddNode(addExtraOffThird, add3);
                graph.addWithoutUnique(addOffThird);

                readAddressNodes.get(2).replaceFirstInput(add3, addOffThird);

                // fourth offset: oclAddress + (sizeOf(fieldO) + sizeOf(field1) + sizeOf(field2)
                // + (sizeOf(fieldO) + sizeOf(field1) + sizeOf(field2))*i)
                MulNode mulOffFourth = new MulNode(ph, fourthConstOffset);
                graph.addWithoutUnique(mulOffFourth);

                AddNode addExtraOffFourth = new AddNode(fourthConstOffset, mulOffFourth);
                graph.addWithoutUnique(addExtraOffFourth);

                AddNode addOffFourth = new AddNode(addExtraOffFourth, add4);
                graph.addWithoutUnique(addOffFourth);

                readAddressNodes.get(3).replaceFirstInput(add4, addOffFourth);

                /*
                 * for (Node n : graph.getNodes()) { if (n instanceof SignExtendNode) {
                 * n.replaceFirstInput(n.inputs().first(), ph); } }
                 */

            }
        }

        if (differentTypesRet) {
            // System.out.println("Return type fields are different!");
            boolean isTuple2 = false;
            boolean isTuple3 = false;
            boolean isTuple4 = false;

            if (fieldSizesRet.size() == 2) {
                isTuple2 = true;
            } else if (fieldSizesRet.size() == 3) {
                isTuple3 = true;
            } else if (fieldSizesRet.size() == 4) {
                // return;
                isTuple4 = true;
            } else {
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
            AddNode add = null;
            AddNode add2 = null;

            for (Node oclin : writeAddressNodes.get(0).inputs()) {
                if (oclin instanceof AddNode) {
                    add = (AddNode) oclin;
                }
            }

            for (Node oclin : writeAddressNodes.get(1).inputs()) {
                if (oclin instanceof AddNode) {
                    add2 = (AddNode) oclin;
                }
            }

            // if input is Tuple3 get input of 3rd readnode
            AddNode add3 = null;
            if (isTuple3 || isTuple4) {
                for (Node oclin : writeAddressNodes.get(2).inputs()) {
                    if (oclin instanceof AddNode) {
                        add3 = (AddNode) oclin;
                    }
                }
            }

            AddNode add4 = null;
            if (isTuple4) {
                for (Node oclin : writeAddressNodes.get(3).inputs()) {
                    if (oclin instanceof AddNode) {
                        add4 = (AddNode) oclin;
                    }
                }
            }

            ValuePhiNode ph = null;

            if (differentTypesInner) {

                // find outer loop phi node

                for (Node n : graph.getNodes()) {
                    if (n instanceof ValuePhiNode) {
                        ValuePhiNode phV = (ValuePhiNode) n;
                        for (Node in : phV.inputs()) {
                            if (in instanceof GlobalThreadIdNode) {
                                ph = phV;
                                break;
                            }
                        }
                        if (ph != null)
                            break;
                    }
                }

            } else {

                for (Node n : graph.getNodes()) {
                    if (n instanceof ValuePhiNode) {
                        ph = (ValuePhiNode) n;
                    }
                }
            }
            if (isTuple2) {
                Constant firstOffset;
                ConstantNode firstConstOffset;
                firstOffset = new RawConstant(fieldSizesRet.get(0));
                firstConstOffset = new ConstantNode(firstOffset, StampFactory.forKind(JavaKind.Byte));
                graph.addWithoutUnique(firstConstOffset);

                Constant secondOffset;
                ConstantNode secondConstOffset;
                secondOffset = new RawConstant(fieldSizesRet.get(1));
                secondConstOffset = new ConstantNode(secondOffset, StampFactory.forKind(JavaKind.Byte));
                graph.addWithoutUnique(secondConstOffset);

                // first offset: oclAddress + i*sizeOfSecondField
                MulNode multOffFirst = new MulNode(ph, secondConstOffset);
                graph.addWithoutUnique(multOffFirst);

                AddNode addOffFirst = new AddNode(multOffFirst, add);
                graph.addWithoutUnique(addOffFirst);

                writeAddressNodes.get(0).replaceFirstInput(add, addOffFirst);
                // ----

                // second offset: oclAddress + (sizeOfFirstField + i*sizeOfFirstField)
                MulNode mulOffSec = new MulNode(ph, firstConstOffset);
                graph.addWithoutUnique(mulOffSec);

                AddNode addExtraOffSecond = new AddNode(firstConstOffset, mulOffSec);
                graph.addWithoutUnique(addExtraOffSecond);

                AddNode addOffSec = new AddNode(addExtraOffSecond, add2);
                graph.addWithoutUnique(addOffSec);

                writeAddressNodes.get(1).replaceFirstInput(add2, addOffSec);
            } else if (isTuple3) {
                // if tuple3

                // ----- (sizeOf(field1) + sizeOf(field2))
                // constant for (sizeOf(field1) + sizeOf(field2))
                Constant firstOffset;
                ConstantNode firstConstOffset;
                firstOffset = new RawConstant(fieldSizesRet.get(1) + fieldSizesRet.get(2));
                firstConstOffset = new ConstantNode(firstOffset, StampFactory.forKind(JavaKind.Byte));
                graph.addWithoutUnique(firstConstOffset);

                // ----- sizeOf(field0) + (sizeOf(field0) + sizeOf(field2))
                // constant for sizeOf(field0)
                Constant secondOffset;
                ConstantNode secondConstOffset;
                secondOffset = new RawConstant(fieldSizesRet.get(0));
                secondConstOffset = new ConstantNode(secondOffset, StampFactory.forKind(JavaKind.Byte));
                graph.addWithoutUnique(secondConstOffset);
                // constant for (sizeOf(field0) + sizeOf(field2))
                Constant secondOffsetMul;
                ConstantNode secondConstOffsetMul;
                secondOffsetMul = new RawConstant(fieldSizesRet.get(0) + fieldSizesRet.get(2));
                secondConstOffsetMul = new ConstantNode(secondOffsetMul, StampFactory.forKind(JavaKind.Byte));
                graph.addWithoutUnique(secondConstOffsetMul);

                // ----- sizeOf(fieldO) + sizeOf(field1) + (sizeOf(field0) + sizeOf(field1))
                // create sizeOf(field0) + sizeOf(field1)
                Constant thirdOffset;
                ConstantNode thirdConstOffset;
                thirdOffset = new RawConstant(fieldSizesRet.get(0) + fieldSizesRet.get(1));
                thirdConstOffset = new ConstantNode(thirdOffset, StampFactory.forKind(JavaKind.Byte));
                graph.addWithoutUnique(thirdConstOffset);
                // --------------------------------------

                // first offset: oclAddress + (sizeOf(field1) + sizeOf(field2))*i
                MulNode multOffFirst = new MulNode(ph, firstConstOffset);
                graph.addWithoutUnique(multOffFirst);

                AddNode addOffFirst = new AddNode(multOffFirst, add);
                graph.addWithoutUnique(addOffFirst);

                writeAddressNodes.get(0).replaceFirstInput(add, addOffFirst);
                // ----

                // second offset: oclAddress + (sizeOf(field0) + (sizeOf(field0) +
                // sizeOf(field2))*i)
                MulNode mulOffSec = new MulNode(ph, secondConstOffsetMul);
                graph.addWithoutUnique(mulOffSec);

                AddNode addExtraOffSecond = new AddNode(secondConstOffset, mulOffSec);
                graph.addWithoutUnique(addExtraOffSecond);

                AddNode addOffSec = new AddNode(addExtraOffSecond, add2);
                graph.addWithoutUnique(addOffSec);

                writeAddressNodes.get(1).replaceFirstInput(add2, addOffSec);

                // third offset: oclAddress + (sizeOf(fieldO) + sizeOf(field1) + (sizeOf(field0)
                // + sizeOf(field1)*i)
                MulNode mulOffThird = new MulNode(ph, thirdConstOffset);
                graph.addWithoutUnique(mulOffThird);

                AddNode addExtraOffThird = new AddNode(thirdConstOffset, mulOffThird);
                graph.addWithoutUnique(addExtraOffThird);

                AddNode addOffThird = new AddNode(addExtraOffThird, add3);
                graph.addWithoutUnique(addOffThird);

                writeAddressNodes.get(2).replaceFirstInput(add3, addOffThird);

                // If input field types are different we have already fixed the write index
                // If not, we need to find the SignExtend nodes that belong to each write node
                // and set their values to i
                // This is necessary because in the TornadoTupleReplacement phase, if the
                // input/output is Tuple2 or Tuple3
                // the indexes of the load and store nodes are changed to (#fields)*i + (#field
                // + 1)
                // For instance, to access the second field of a Tuple3 the index of the load
                // node would be:
                // 3*i + 2
                // However, if the offsets of the types are different we handle field offset
                // differently
                // if (!differentTypes) {
                for (Node addIns : add.inputs()) {
                    if (addIns instanceof LeftShiftNode) {
                        for (Node shiftIns : addIns.inputs()) {
                            if (shiftIns instanceof SignExtendNode) {
                                shiftIns.replaceFirstInput(shiftIns.inputs().first(), ph);
                                break;
                            }
                        }
                        break;
                    }
                }

                for (Node addIns : add2.inputs()) {
                    if (addIns instanceof LeftShiftNode) {
                        for (Node shiftIns : addIns.inputs()) {
                            if (shiftIns instanceof SignExtendNode) {
                                shiftIns.replaceFirstInput(shiftIns.inputs().first(), ph);
                                break;
                            }
                        }
                        break;
                    }
                }

                if (add3 != null) {
                    for (Node addIns : add3.inputs()) {
                        if (addIns instanceof LeftShiftNode) {
                            for (Node shiftIns : addIns.inputs()) {
                                if (shiftIns instanceof SignExtendNode) {
                                    shiftIns.replaceFirstInput(shiftIns.inputs().first(), ph);
                                    break;
                                }
                            }
                            break;
                        }
                    }
                }

                // }

            } else {
                // is Tuple4
                // ----- (sizeOf(field1) + sizeOf(field2) + sizeOf(field3))
                // constant for (sizeOf(field1) + sizeOf(field2) + sizeOf(field3))
                // System.out.println("-- Return type diff: Tuple4!! ");
                Constant firstOffset;
                ConstantNode firstConstOffset;
                firstOffset = new RawConstant(fieldSizesRet.get(1) + fieldSizesRet.get(2) + fieldSizesRet.get(3));
                firstConstOffset = new ConstantNode(firstOffset, StampFactory.forKind(JavaKind.Byte));
                graph.addWithoutUnique(firstConstOffset);

                // ----- sizeOf(field0) + (sizeOf(field0) + sizeOf(field2) + sizeOf(field3))
                // constant for sizeOf(field0)
                Constant secondOffset;
                ConstantNode secondConstOffset;
                secondOffset = new RawConstant(fieldSizesRet.get(0));
                secondConstOffset = new ConstantNode(secondOffset, StampFactory.forKind(JavaKind.Byte));
                graph.addWithoutUnique(secondConstOffset);
                // constant for (sizeOf(field0) + sizeOf(field2) + sizeOf(field3))
                Constant secondOffsetMul;
                ConstantNode secondConstOffsetMul;
                secondOffsetMul = new RawConstant(fieldSizesRet.get(0) + fieldSizesRet.get(2) + fieldSizesRet.get(3));
                secondConstOffsetMul = new ConstantNode(secondOffsetMul, StampFactory.forKind(JavaKind.Byte));
                graph.addWithoutUnique(secondConstOffsetMul);

                // ----- sizeOf(fieldO) + sizeOf(field1) + (sizeOf(field3) + sizeOf(field0) +
                // sizeOf(field1))
                // create sizeOf(field0) + sizeOf(field1)
                Constant thirdOffset;
                ConstantNode thirdConstOffset;
                thirdOffset = new RawConstant(fieldSizesRet.get(0) + fieldSizesRet.get(1));
                thirdConstOffset = new ConstantNode(thirdOffset, StampFactory.forKind(JavaKind.Byte));
                graph.addWithoutUnique(thirdConstOffset);
                Constant thirdOffsetMul;
                // create sizeOf(field3) + sizeOf(field0) + sizeOf(field1)
                ConstantNode thirdConstOffsetMul;
                thirdOffsetMul = new RawConstant(fieldSizesRet.get(3) + fieldSizesRet.get(0) + fieldSizesRet.get(1));
                thirdConstOffsetMul = new ConstantNode(thirdOffsetMul, StampFactory.forKind(JavaKind.Byte));
                graph.addWithoutUnique(thirdConstOffsetMul);

                // ----- sizeOf(fieldO) + sizeOf(field1) + sizeOf(field2) + (sizeOf(fieldO) +
                // sizeOf(field1) + sizeOf(field2))
                // create sizeOf(fieldO) + sizeOf(field1) + sizeOf(field2)
                Constant fourthOffset;
                ConstantNode fourthConstOffset;
                fourthOffset = new RawConstant(fieldSizesRet.get(0) + fieldSizesRet.get(1) + fieldSizesRet.get(2));
                fourthConstOffset = new ConstantNode(fourthOffset, StampFactory.forKind(JavaKind.Byte));
                graph.addWithoutUnique(fourthConstOffset);
                // --------------------------------------

                // first offset: oclAddress + (sizeOf(field1) + sizeOf(field2) +
                // sizeOf(field3))*i
                MulNode multOffFirst = new MulNode(ph, firstConstOffset);
                graph.addWithoutUnique(multOffFirst);

                AddNode addOffFirst = new AddNode(multOffFirst, add);
                graph.addWithoutUnique(addOffFirst);

                writeAddressNodes.get(0).replaceFirstInput(add, addOffFirst);
                // ----

                // second offset: oclAddress + (sizeOf(field0) + (sizeOf(field0) +
                // sizeOf(field2) + field3)*i)
                MulNode mulOffSec = new MulNode(ph, secondConstOffsetMul);
                graph.addWithoutUnique(mulOffSec);

                AddNode addExtraOffSecond = new AddNode(secondConstOffset, mulOffSec);
                graph.addWithoutUnique(addExtraOffSecond);

                AddNode addOffSec = new AddNode(addExtraOffSecond, add2);
                graph.addWithoutUnique(addOffSec);

                writeAddressNodes.get(1).replaceFirstInput(add2, addOffSec);

                // third offset: oclAddress + (sizeOf(fieldO) + sizeOf(field1) + (sizeOf(field3)
                // + sizeOf(field0)
                // + sizeOf(field1)*i)
                MulNode mulOffThird = new MulNode(ph, thirdConstOffsetMul);
                graph.addWithoutUnique(mulOffThird);

                AddNode addExtraOffThird = new AddNode(thirdConstOffset, mulOffThird);
                graph.addWithoutUnique(addExtraOffThird);

                AddNode addOffThird = new AddNode(addExtraOffThird, add3);
                graph.addWithoutUnique(addOffThird);

                writeAddressNodes.get(2).replaceFirstInput(add3, addOffThird);

                // fourth offset: oclAddress + (sizeOf(fieldO) + sizeOf(field1) + sizeOf(field2)
                // + (sizeOf(fieldO) + sizeOf(field1) + sizeOf(field2))*i)
                MulNode mulOffFourth = new MulNode(ph, fourthConstOffset);
                graph.addWithoutUnique(mulOffFourth);

                AddNode addExtraOffFourth = new AddNode(fourthConstOffset, mulOffFourth);
                graph.addWithoutUnique(addExtraOffFourth);

                AddNode addOffFourth = new AddNode(addExtraOffFourth, add4);
                graph.addWithoutUnique(addOffFourth);

                writeAddressNodes.get(3).replaceFirstInput(add4, addOffFourth);

                // if (!differentTypes) {
                for (Node addIns : add.inputs()) {
                    if (addIns instanceof LeftShiftNode) {
                        for (Node shiftIns : addIns.inputs()) {
                            if (shiftIns instanceof SignExtendNode) {
                                shiftIns.replaceFirstInput(shiftIns.inputs().first(), ph);
                                break;
                            }
                        }
                        break;
                    }
                }

                for (Node addIns : add2.inputs()) {
                    if (addIns instanceof LeftShiftNode) {
                        for (Node shiftIns : addIns.inputs()) {
                            if (shiftIns instanceof SignExtendNode) {
                                shiftIns.replaceFirstInput(shiftIns.inputs().first(), ph);
                                break;
                            }
                        }
                        break;
                    }
                }

                if (add3 != null) {
                    for (Node addIns : add3.inputs()) {
                        if (addIns instanceof LeftShiftNode) {
                            for (Node shiftIns : addIns.inputs()) {
                                if (shiftIns instanceof SignExtendNode) {
                                    shiftIns.replaceFirstInput(shiftIns.inputs().first(), ph);
                                    break;
                                }
                            }
                            break;
                        }
                    }
                }

                if (add4 != null) {
                    for (Node addIns : add4.inputs()) {
                        if (addIns instanceof LeftShiftNode) {
                            for (Node shiftIns : addIns.inputs()) {
                                if (shiftIns instanceof SignExtendNode) {
                                    shiftIns.replaceFirstInput(shiftIns.inputs().first(), ph);
                                    break;
                                }
                            }
                            break;
                        }
                    }
                }

                // }

            }
        }

    }
}
