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
import org.graalvm.compiler.nodes.memory.FloatingReadNode;
import org.graalvm.compiler.phases.Phase;

import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLAddressNode;

import java.util.ArrayList;

public class TornadoTupleOffset extends Phase {

    public static boolean differentTypes = false;

    @Override
    protected void run(StructuredGraph graph) {
        if (differentTypes) {
            int sizeOfF0 = 4;
            int sizeOfF1 = 8;

            ArrayList<OCLAddressNode> readAddressNodes = new ArrayList<>();
            for (Node n : graph.getNodes()) {
                if (n instanceof FloatingReadNode && !((FloatingReadNode) n).stamp().toString().contains("Lorg/apache/flink/")) {
                    readAddressNodes.add((OCLAddressNode) n.inputs().first());
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

            ValuePhiNode ph = null;

            for (Node n : graph.getNodes()) {
                if (n instanceof ValuePhiNode) {
                    ph = (ValuePhiNode) n;
                }
            }
            Constant firstOffset;
            ConstantNode firstConstOffset;
            firstOffset = new RawConstant(sizeOfF0);
            firstConstOffset = new ConstantNode(firstOffset, StampFactory.forKind(JavaKind.Byte));
            graph.addOrUnique(firstConstOffset);

            Constant secondOffset;
            ConstantNode secondConstOffset;
            secondOffset = new RawConstant(sizeOfF1);
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

            // ----

        }
    }
}
