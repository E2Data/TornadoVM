package uk.ac.manchester.tornado.drivers.opencl.graal.phases;

import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.RawConstant;

import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValuePhiNode;
import org.graalvm.compiler.nodes.calc.AddNode;
import org.graalvm.compiler.nodes.calc.MulNode;
import org.graalvm.compiler.nodes.calc.SignExtendNode;
import org.graalvm.compiler.nodes.memory.FloatingReadNode;
import org.graalvm.compiler.phases.Phase;

import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLAddressNode;

import java.util.ArrayList;
import java.util.HashMap;

public class TornadoTupleOffset extends Phase {

    public static boolean differentTypes = false;
    public static ArrayList<Integer> fieldSizes = new ArrayList<>();
    public static ArrayList<String> fieldTypes = new ArrayList<>();

    @Override
    protected void run(StructuredGraph graph) {
        if (differentTypes) {

            boolean isTuple2 = false;
            boolean isTuple3 = false;

            if (fieldSizes.size() == 2) {
                isTuple2 = true;
            } else if (fieldSizes.size() == 3) {
                isTuple3 = true;
            } else {
                System.out.println("[TornadoTupleOffset phase WARNING]: We currently only support up to Tuple3.");
                return;
            }

            // ArrayList<OCLAddressNode> readAddressNodes = new ArrayList<>();
            HashMap<Integer, OCLAddressNode> readAddressNodes = new HashMap();
            int j = 0;
            for (Node n : graph.getNodes()) {
                if (n instanceof FloatingReadNode && !((FloatingReadNode) n).stamp().toString().contains("Lorg/apache/flink/")) {
                    FloatingReadNode f = (FloatingReadNode) n;
                    String readFieldType = f.getLocationIdentity().toString();
                    for (int i = 0; i < fieldTypes.size(); i++) {
                        if (readFieldType.contains(fieldTypes.get(i))) {
                            readAddressNodes.put(i, (OCLAddressNode) n.inputs().first());
                            fieldTypes.set(i, "used");
                            break;
                        }
                    }
                }
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
            if (isTuple3) {
                for (Node oclin : readAddressNodes.get(2).inputs()) {
                    if (oclin instanceof AddNode) {
                        add3 = (AddNode) oclin;
                    }
                }
            }

            ValuePhiNode ph = null;

            for (Node n : graph.getNodes()) {
                if (n instanceof ValuePhiNode) {
                    ph = (ValuePhiNode) n;
                }
            }
            if (isTuple2) {
                Constant firstOffset;
                ConstantNode firstConstOffset;
                firstOffset = new RawConstant(fieldSizes.get(0));
                firstConstOffset = new ConstantNode(firstOffset, StampFactory.forKind(JavaKind.Byte));
                graph.addOrUnique(firstConstOffset);

                Constant secondOffset;
                ConstantNode secondConstOffset;
                secondOffset = new RawConstant(fieldSizes.get(1));
                secondConstOffset = new ConstantNode(secondOffset, StampFactory.forKind(JavaKind.Byte));
                graph.addOrUnique(secondConstOffset);

                // first offset: oclAddress + i*sizeOfSecondField
                MulNode multOffFirst = new MulNode(ph, secondConstOffset);
                graph.addOrUnique(multOffFirst);

                AddNode addOffFirst = new AddNode(multOffFirst, add);
                graph.addOrUnique(addOffFirst);

                readAddressNodes.get(0).replaceFirstInput(add, addOffFirst);
                // ----

                // second offset: oclAddress + (sizeOfFirstField + i*sizeOfFirstField)
                MulNode mulOffSec = new MulNode(ph, firstConstOffset);
                graph.addOrUnique(mulOffSec);

                AddNode addExtraOffSecond = new AddNode(firstConstOffset, mulOffSec);
                graph.addOrUnique(addExtraOffSecond);

                AddNode addOffSec = new AddNode(addExtraOffSecond, add2);
                graph.addOrUnique(addOffSec);

                readAddressNodes.get(1).replaceFirstInput(add2, addOffSec);
            } else {
                // if tuple3

                // ----- (sizeOf(field1) + sizeOf(field2))
                // constant for (sizeOf(field1) + sizeOf(field2))
                Constant firstOffset;
                ConstantNode firstConstOffset;
                firstOffset = new RawConstant(fieldSizes.get(1) + fieldSizes.get(2));
                firstConstOffset = new ConstantNode(firstOffset, StampFactory.forKind(JavaKind.Byte));
                graph.addOrUnique(firstConstOffset);

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
                graph.addOrUnique(multOffFirst);

                AddNode addOffFirst = new AddNode(multOffFirst, add);
                graph.addOrUnique(addOffFirst);

                readAddressNodes.get(0).replaceFirstInput(add, addOffFirst);
                // ----

                // second offset: oclAddress + (sizeOf(field0) + (sizeOf(field0) +
                // sizeOf(field2))*i)
                MulNode mulOffSec = new MulNode(ph, secondConstOffsetMul);
                graph.addOrUnique(mulOffSec);

                AddNode addExtraOffSecond = new AddNode(secondConstOffset, mulOffSec);
                graph.addOrUnique(addExtraOffSecond);

                AddNode addOffSec = new AddNode(addExtraOffSecond, add2);
                graph.addOrUnique(addOffSec);

                readAddressNodes.get(1).replaceFirstInput(add2, addOffSec);

                // third offset: oclAddress + (sizeOf(fieldO) + sizeOf(field1) + (sizeOf(field0)
                // + sizeOf(field1)*i)
                MulNode mulOffThird = new MulNode(ph, thirdConstOffset);
                graph.addOrUnique(mulOffThird);

                AddNode addExtraOffThird = new AddNode(thirdConstOffset, mulOffThird);
                graph.addOrUnique(addExtraOffThird);

                AddNode addOffThird = new AddNode(addExtraOffThird, add3);
                graph.addOrUnique(addOffThird);

                readAddressNodes.get(2).replaceFirstInput(add3, addOffThird);

                for (Node n : graph.getNodes()) {
                    if (n instanceof SignExtendNode) {
                        n.replaceFirstInput(n.inputs().first(), ph);
                    }
                }

            }

            // ----

        }
    }
}
