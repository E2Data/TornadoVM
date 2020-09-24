package uk.ac.manchester.tornado.drivers.opencl.graal.nodes;

import jdk.vm.ci.meta.*;
import org.graalvm.compiler.core.common.LIRKind;
import org.graalvm.compiler.core.common.type.ObjectStamp;
import org.graalvm.compiler.core.common.type.Stamp;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.core.common.util.UnsignedLong;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.graph.NodeClass;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.Variable;
import org.graalvm.compiler.lir.gen.ArithmeticLIRGeneratorTool;
import org.graalvm.compiler.lir.gen.LIRGeneratorTool;
import org.graalvm.compiler.nodeinfo.NodeInfo;
import org.graalvm.compiler.nodes.*;
import org.graalvm.compiler.nodes.memory.FloatingReadNode;
import org.graalvm.compiler.nodes.memory.address.AddressNode;
import org.graalvm.compiler.nodes.spi.LIRLowerable;
import org.graalvm.compiler.nodes.spi.NodeLIRBuilderTool;
import uk.ac.manchester.tornado.drivers.opencl.graal.OCLArchitecture;
import uk.ac.manchester.tornado.drivers.opencl.graal.OCLStampFactory;
import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLAddressNode;
import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLKind;
import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLLIRStmt;
import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLUnary;
import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLUnary.MemoryAccess;

import static uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLKind.ILLEGAL;

@NodeInfo
public class CopyArrayTupleField extends FixedWithNextNode implements LIRLowerable {

    public static final NodeClass<CopyArrayTupleField> TYPE = NodeClass.create(CopyArrayTupleField.class);

    private int tupleSize;
    private int tupleSizeRet;
    private int arrayElementSize;
    private int arrayLength;
    private int tupleArrayFieldNo;
    private String type;
    private Stamp readStamp;

    @Input
    private PhiNode outerLoopIndex;
    @Input
    private OCLAddressNode readAddress;
    @Input
    private OCLAddressNode writeAddress;

    public CopyArrayTupleField(int tupleSize, int tupleSizeRet, int arrayElementSize, int arrayLength, PhiNode outerLoopIndex, OCLAddressNode readAddress, OCLAddressNode writeAddress, String type,
            Stamp readStamp, int tupleArrayFieldNo) {
        super(TYPE, StampFactory.forVoid());
        this.readAddress = readAddress;
        this.writeAddress = writeAddress;
        this.tupleSize = tupleSize;
        this.tupleSizeRet = tupleSizeRet;
        this.arrayElementSize = arrayElementSize;
        this.arrayLength = arrayLength;
        this.outerLoopIndex = outerLoopIndex;
        this.type = type;
        this.readStamp = readStamp;
        this.tupleArrayFieldNo = tupleArrayFieldNo;
    }

    public CopyArrayTupleField() {
        super(TYPE, StampFactory.forVoid());
    }

    protected LIRKind getPhiKind(PhiNode phi, LIRGeneratorTool gen) {
        Stamp stamp = phi.stamp(NodeView.DEFAULT);
        if (stamp.isEmpty()) {
            for (ValueNode n : phi.values()) {
                if (stamp.isEmpty()) {
                    stamp = n.stamp(NodeView.DEFAULT);
                } else {
                    stamp = stamp.meet(n.stamp(NodeView.DEFAULT));
                }
            }
            phi.setStamp(stamp);
        } else if (stamp instanceof ObjectStamp) {
            ObjectStamp oStamp = (ObjectStamp) stamp;
            OCLKind oclKind = OCLKind.fromResolvedJavaType(oStamp.javaType(gen.getMetaAccess()));
            if (oclKind != ILLEGAL && oclKind.isVector()) {
                stamp = OCLStampFactory.getStampFor(oclKind);
                phi.setStamp(stamp);
            }
        }
        return gen.getLIRKind(stamp);
    }

    public Value operandForPhi(NodeLIRBuilderTool gen, ValuePhiNode phi) {
        Value result = gen.operand(phi);
        if (result == null) {
            Variable newOperand = gen.getLIRGeneratorTool().newVariable(getPhiKind(phi, gen.getLIRGeneratorTool()));
            gen.setResult(phi, newOperand);
            return newOperand;
        } else {
            return result;
        }
    }

    @Override
    public void generate(NodeLIRBuilderTool generator) {
        if (tupleArrayFieldNo == 0) {
            LIRGeneratorTool tool = generator.getLIRGeneratorTool();
            // i_10
            Variable var1 = tool.newVariable(tool.getLIRKind(StampFactory.intValue()));
            // i_11
            Variable var2 = tool.newVariable(tool.getLIRKind(StampFactory.intValue()));
            // i_12
            Variable var3 = tool.newVariable(tool.getLIRKind(StampFactory.intValue()));
            // i_13
            Variable var4 = tool.newVariable(tool.getLIRKind(StampFactory.intValue()));
            // ul_14
            Variable var5 = tool.newVariable(tool.getLIRKind(StampFactory.forKind(JavaKind.fromJavaClass(UnsignedLong.class))));
            // array element variable
            Variable var6 = tool.newVariable(tool.getLIRKind(readStamp));
            // i_16
            Variable var7 = tool.newVariable(tool.getLIRKind(StampFactory.intValue()));
            // i_17
            Variable var8 = tool.newVariable(tool.getLIRKind(StampFactory.intValue()));
            // i_18
            Variable var9 = tool.newVariable(tool.getLIRKind(StampFactory.intValue()));
            // i_19
            Variable var10 = tool.newVariable(tool.getLIRKind(StampFactory.forKind(JavaKind.fromJavaClass(UnsignedLong.class)))); // tool.newVariable(tool.getLIRKind(StampFactory.intValue()));
            // ul_20
            Constant increm = new RawConstant(1);
            Value var11 = tool.emitConstant(tool.getLIRKind(StampFactory.intValue()), increm);

            // index
            Variable index = tool.newVariable(tool.getLIRKind(StampFactory.intValue()));
            // arrayLength
            Constant length = new RawConstant(arrayLength);
            Value arLength = tool.emitConstant(tool.getLIRKind(StampFactory.intValue()), length);
            // arrayElementSize
            Constant size = new RawConstant(arrayElementSize);
            Value elSize = tool.emitConstant(tool.getLIRKind(StampFactory.intValue()), size);
            // tupleSize
            Constant tupleSizeConst = new RawConstant(tupleSize);
            Value tupSize = tool.emitConstant(tool.getLIRKind(StampFactory.intValue()), tupleSizeConst);
            // tupleSizeRet
            Constant tupleSizeRetConst = new RawConstant(tupleSizeRet);
            Value tupSizeRet = tool.emitConstant(tool.getLIRKind(StampFactory.intValue()), tupleSizeRetConst);
            // zero
            Constant zeroConst = new RawConstant(0);
            Value zero = tool.emitConstant(tool.getLIRKind(StampFactory.intValue()), zeroConst);
            // header (24L)
            Constant headerConst = new RawConstant(24);
            Value header = tool.emitConstant(tool.getLIRKind(StampFactory.forKind(JavaKind.Long)), headerConst);

            // outerLoopIndex
            Variable loopVar = tool.newVariable(tool.getLIRKind(StampFactory.intValue()));
            if (outerLoopIndex.singleBackValueOrThis() == outerLoopIndex && loopVar instanceof Variable) {
                generator.setResult(outerLoopIndex, loopVar);
            } else {
                final AllocatableValue result = (AllocatableValue) operandForPhi(generator, (ValuePhiNode) outerLoopIndex);
                generator.getLIRGeneratorTool().append(new OCLLIRStmt.AssignStmt(loopVar, result));
            }

            MemoryAccess memRead = (MemoryAccess) generator.operand(readAddress);
            MemoryAccess memWrite = (MemoryAccess) generator.operand(writeAddress);

            LIRKind lirKind = tool.getLIRKind(readStamp);
            OCLKind oclKind = (OCLKind) lirKind.getPlatformKind();

            OCLArchitecture.OCLMemoryBase readBase = memRead.getBase();
            OCLArchitecture.OCLMemoryBase writeBase = memWrite.getBase();

            OCLUnary.OCLAddressCast readCast = new OCLUnary.OCLAddressCast(readBase, LIRKind.value(oclKind));
            OCLUnary.OCLAddressCast writeCast = new OCLUnary.OCLAddressCast(writeBase, LIRKind.value(oclKind));

            tool.append(new OCLLIRStmt.CopyArrayField1TupleExpr(tupSize, tupSizeRet, elSize, arLength, loopVar, index, var1, var2, var3, var4, var5, var6, var7, var8, var9, var10, var11, memRead,
                    memWrite, zero, header, type, readCast, writeCast));
        }
    }

}
