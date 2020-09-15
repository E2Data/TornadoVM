package uk.ac.manchester.tornado.drivers.opencl.graal.nodes;

import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.NodeClass;
import org.graalvm.compiler.nodeinfo.NodeInfo;
import org.graalvm.compiler.nodes.FixedWithNextNode;
import org.graalvm.compiler.nodes.spi.LIRLowerable;
import org.graalvm.compiler.nodes.spi.NodeLIRBuilderTool;
import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLLIRStmt;

@NodeInfo
public class CopyArrayTupleField extends FixedWithNextNode implements LIRLowerable {

    public static final NodeClass<CopyArrayTupleField> TYPE = NodeClass.create(CopyArrayTupleField.class);

    public CopyArrayTupleField() {
        super(TYPE, StampFactory.forVoid());
    }

    private int loopLimit = 5;

    @Override
    public void generate(NodeLIRBuilderTool generator) {
        generator.getLIRGeneratorTool().append(new OCLLIRStmt.CopyArrayFieldExpr(loopLimit));
    }

}
