package uk.ac.manchester.tornado.drivers.opencl.graal.phases;

import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.RawConstant;

import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValuePhiNode;
import org.graalvm.compiler.nodes.calc.AddNode;
import org.graalvm.compiler.nodes.calc.LeftShiftNode;
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

    private boolean broadcastedDataset;

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

    @Override
    protected void run(StructuredGraph graph) {

        FlinkCompilerInfo fcomp = FlinkCompilerInfoIntermediate.getFlinkCompilerInfo();
        if (fcomp != null) {
            flinkSetCompInfo(fcomp);
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
