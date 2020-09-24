/*
 * Copyright (c) 2018, 2020, APT Group, Department of Computer Science,
 * The University of Manchester. All rights reserved.
 * Copyright (c) 2009, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Authors: James Clarkson
 *
 */
package uk.ac.manchester.tornado.drivers.opencl.graal.lir;

import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.RawConstant;
import org.graalvm.compiler.core.common.LIRKind;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.lir.ConstantValue;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LIRInstructionClass;
import org.graalvm.compiler.lir.Opcode;
import org.graalvm.compiler.lir.Variable;
import org.graalvm.compiler.lir.asm.CompilationResultBuilder;

import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Value;
import uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler;
import uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLBinaryIntrinsic;
import uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLTernaryIntrinsic;
import uk.ac.manchester.tornado.drivers.opencl.graal.compiler.OCLCompilationResultBuilder;
import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLUnary.MemoryAccess;
import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLUnary.OCLAddressCast;
import uk.ac.manchester.tornado.drivers.opencl.graal.meta.OCLMemorySpace;

import static uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssemblerConstants.*;

public class OCLLIRStmt {

    protected static abstract class AbstractInstruction extends LIRInstruction {

        protected AbstractInstruction(LIRInstructionClass<? extends AbstractInstruction> c) {
            super(c);
        }

        @Override
        public final void emitCode(CompilationResultBuilder crb) {
            emitCode((OCLCompilationResultBuilder) crb, (OCLAssembler) crb.asm);
        }

        public abstract void emitCode(OCLCompilationResultBuilder crb, OCLAssembler asm);

    }

    @Opcode("ASSIGN")
    public static class AssignStmt extends AbstractInstruction {

        public static final LIRInstructionClass<AssignStmt> TYPE = LIRInstructionClass.create(AssignStmt.class);

        @Def
        protected AllocatableValue lhs;
        @Use
        protected Value rhs;

        public AssignStmt(AllocatableValue lhs, Value rhs) {
            super(TYPE);
            this.lhs = lhs;
            this.rhs = rhs;
        }

        @Override
        public void emitCode(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();
            asm.emitValue(crb, lhs);
            asm.space();
            asm.assign();
            asm.space();
            if (rhs instanceof OCLLIROp) {
                ((OCLLIROp) rhs).emit(crb, asm);
            } else {
                asm.emitValue(crb, rhs);
            }
            asm.delimiter();
            asm.eol();
        }

        public AllocatableValue getResult() {
            return lhs;
        }

        public Value getExpr() {
            return rhs;
        }
    }

    @Opcode("MOVE")
    public static class MoveStmt extends AbstractInstruction {

        public static final LIRInstructionClass<MoveStmt> TYPE = LIRInstructionClass.create(MoveStmt.class);

        @Def
        protected AllocatableValue lhs;
        @Use
        protected Value rhs;

        public MoveStmt(AllocatableValue lhs, Value rhs) {
            super(TYPE);
            this.lhs = lhs;
            this.rhs = rhs;
        }

        @Override
        public void emitCode(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();
            asm.emitValue(crb, lhs);
            asm.space();
            asm.assign();
            asm.space();
            asm.emitValue(crb, rhs);
            asm.delimiter();
            asm.eol();
        }

        public AllocatableValue getResult() {
            return lhs;
        }

        public Value getExpr() {
            return rhs;
        }
    }

    @Opcode("LOAD")
    public static class LoadStmt extends AbstractInstruction {

        public static final LIRInstructionClass<LoadStmt> TYPE = LIRInstructionClass.create(LoadStmt.class);

        @Def
        protected AllocatableValue lhs;
        @Use
        protected OCLAddressCast cast;
        @Use
        protected MemoryAccess address;
        @Use
        protected Value index;

        public LoadStmt(AllocatableValue lhs, OCLAddressCast cast, MemoryAccess address) {
            super(TYPE);
            this.lhs = lhs;
            this.cast = cast;
            this.address = address;
        }

        public LoadStmt(AllocatableValue lhs, OCLAddressCast cast, MemoryAccess address, Value index) {
            super(TYPE);
            this.lhs = lhs;
            this.cast = cast;
            this.address = address;
            this.index = index;
        }

        public void emitIntegerBasedIndexCode(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.emitValue(crb, lhs);
            asm.space();
            asm.assign();
            asm.space();
            address.emit(crb, asm);
            asm.emit("[");
            asm.emitValue(crb, index);
            asm.emit("]");
            asm.delimiter();
            asm.eol();
        }

        public void emitPointerBaseIndexCode(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.emitValue(crb, lhs);
            asm.space();
            asm.assign();
            asm.space();
            asm.emit("*(");
            cast.emit(crb, asm);
            asm.space();
            address.emit(crb, asm);
            asm.emit(")");
            asm.delimiter();
            asm.eol();
        }

        @Override
        public void emitCode(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();
            if (isLocalOrPrivateLoad()) {
                emitIntegerBasedIndexCode(crb, asm);
            } else {
                emitPointerBaseIndexCode(crb, asm);
            }
        }

        /**
         * This method is used to check if emiting a load to a local or private memory
         * space.
         *
         * @return boolean This returns if the memory base is private or local.
         */
        private boolean isLocalOrPrivateLoad() {
            return this.cast.getMemorySpace().getBase().memorySpace == OCLMemorySpace.LOCAL || this.cast.getMemorySpace().getBase().memorySpace == OCLMemorySpace.PRIVATE;
        }

        public AllocatableValue getResult() {
            return lhs;
        }

        public OCLAddressCast getCast() {
            return cast;
        }

        public MemoryAccess getAddress() {
            return address;
        }
    }

    @Opcode("VLOAD")
    public static class VectorLoadStmt extends AbstractInstruction {

        public static final LIRInstructionClass<VectorLoadStmt> TYPE = LIRInstructionClass.create(VectorLoadStmt.class);

        @Def
        protected AllocatableValue lhs;
        @Use
        protected OCLAddressCast cast;
        @Use
        protected MemoryAccess address;

        @Use
        protected Value index;

        protected OCLBinaryIntrinsic op;

        public VectorLoadStmt(AllocatableValue lhs, OCLBinaryIntrinsic op, Value index, OCLAddressCast cast, MemoryAccess address) {
            super(TYPE);
            this.lhs = lhs;
            this.cast = cast;
            this.address = address;
            this.op = op;
            this.index = index;
        }

        @Override
        public void emitCode(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();
            asm.emitValue(crb, lhs);
            asm.space();
            asm.assign();
            asm.space();
            asm.emit(op.toString());
            asm.emit("(");
            asm.emitValue(crb, index);
            asm.emit(", ");
            cast.emit(crb, asm);
            asm.space();
            address.emit(crb, asm);
            asm.emit(")");
            asm.delimiter();
            asm.eol();
        }

        public Value getResult() {
            return lhs;
        }

        public OCLAddressCast getCast() {
            return cast;
        }

        public MemoryAccess getAddress() {
            return address;
        }

        public OCLBinaryIntrinsic getOp() {
            return op;
        }
    }

    @Opcode("STORE")
    public static class StoreStmt extends AbstractInstruction {

        public static final LIRInstructionClass<StoreStmt> TYPE = LIRInstructionClass.create(StoreStmt.class);

        @Use
        protected Value rhs;
        @Use
        protected OCLAddressCast cast;
        @Use
        protected MemoryAccess address;
        @Use
        protected Value index;

        public StoreStmt(OCLAddressCast cast, MemoryAccess address, Value rhs) {
            super(TYPE);
            this.rhs = rhs;
            this.cast = cast;
            this.address = address;
        }

        public StoreStmt(OCLAddressCast cast, MemoryAccess address, Value rhs, Value index) {
            super(TYPE);
            this.rhs = rhs;
            this.cast = cast;
            this.address = address;
            this.index = index;
        }

        /**
         * It emits code in the form:
         * 
         * <code>
         *     ul_12[index] = value;
         * </code>
         * 
         * @param crb
         *            OpenCL Compilation Result Builder
         * 
         * @param asm
         *            OpenCL Assembler
         */
        public void emitLocalAndPrivateStore(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            address.emit(crb, asm);
            asm.emit("[");
            asm.emitValue(crb, index);
            asm.emit("]");
            asm.space();
            asm.assign();
            asm.space();
            asm.emitValue(crb, rhs);
            asm.delimiter();
            asm.eol();
        }

        /**
         * It emits code in the form:
         *
         * <code>
         *     *((__global <type> *) ul_13) = <value>
         * </code>
         *
         * @param crb
         *            OpenCL Compilation Result Builder
         *
         * @param asm
         *            OpenCL Assembler
         */
        public void emitGlobalStore(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.emit("*(");
            cast.emit(crb, asm);
            asm.space();
            address.emit(crb, asm);
            asm.emit(")");
            asm.space();
            asm.assign();
            asm.space();
            asm.emitValue(crb, rhs);
            asm.delimiter();
            asm.eol();
        }

        @Override
        public void emitCode(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();
            if (isLocalOrPrivateStore()) {
                emitLocalAndPrivateStore(crb, asm);
            } else {
                emitGlobalStore(crb, asm);
            }
        }

        /**
         * This method is used to check if emitting a store to a local or private memory
         * space.
         * 
         * @return It returns true if the memory base is private or local.
         */
        private boolean isLocalOrPrivateStore() {
            return this.cast.getMemorySpace().getBase().memorySpace == OCLMemorySpace.LOCAL || this.cast.getMemorySpace().getBase().memorySpace == OCLMemorySpace.PRIVATE;
        }

        public Value getRhs() {
            return rhs;
        }

        public OCLAddressCast getCast() {
            return cast;
        }

        public MemoryAccess getAddress() {
            return address;
        }
    }

    @Opcode("ATOMIC_ADD_STORE")
    public static class StoreAtomicAddStmt extends AbstractInstruction {

        public static final LIRInstructionClass<StoreStmt> TYPE = LIRInstructionClass.create(StoreStmt.class);

        public static final boolean GENERATE_ATOMIC = true;

        @Use
        protected Value rhs;
        @Use
        protected OCLAddressCast cast;
        @Use
        protected Value left;
        @Use
        protected MemoryAccess address;

        public StoreAtomicAddStmt(OCLAddressCast cast, MemoryAccess address, Value rhs) {
            super(TYPE);
            this.rhs = rhs;
            this.cast = cast;
            this.address = address;
        }

        public StoreAtomicAddStmt(Value left, Value rhs) {
            super(TYPE);
            this.rhs = rhs;
            this.left = left;
        }

        private void emitAtomicAddStore(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();
            asm.emit("atomic_add( & (");
            asm.emit("*(");
            cast.emit(crb, asm);
            asm.space();
            address.emit(crb, asm);
            asm.emit(")), ");
            asm.space();
            asm.emitValue(crb, rhs);
            asm.emit(")");
            asm.delimiter();
            asm.eol();
        }

        private void emitStore(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();
            asm.emit("*(");
            cast.emit(crb, asm);
            asm.space();
            address.emit(crb, asm);
            asm.emit(")");
            asm.space();
            asm.assign();
            asm.space();
            asm.emitValue(crb, rhs);
            asm.delimiter();
            asm.eol();
        }

        private void emitScalarStore(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();
            asm.emitValue(crb, left);
            asm.space();
            asm.assign();
            asm.space();
            asm.emitValue(crb, rhs);
            asm.delimiter();
            asm.eol();
        }

        @Override
        public void emitCode(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();
            if (left == null) {
                if (GENERATE_ATOMIC) {
                    emitAtomicAddStore(crb, asm);
                } else {
                    emitStore(crb, asm);
                }
            }
        }

        public Value getRhs() {
            return rhs;
        }

        public Value getLeft() {
            return left;
        }

        public OCLAddressCast getCast() {
            return cast;
        }

        public MemoryAccess getAddress() {
            return address;
        }
    }

    @Opcode("ATOMIC_ADD_FLOAT_STORE")
    public static class StoreAtomicAddFloatStmt extends AbstractInstruction {

        public static final LIRInstructionClass<StoreStmt> TYPE = LIRInstructionClass.create(StoreStmt.class);

        public static final boolean GENERATE_ATOMIC = true;

        @Use
        protected Value rhs;
        @Use
        protected OCLAddressCast cast;
        @Use
        protected Value left;
        @Use
        protected MemoryAccess address;

        public StoreAtomicAddFloatStmt(OCLAddressCast cast, MemoryAccess address, Value rhs) {
            super(TYPE);
            this.rhs = rhs;
            this.cast = cast;
            this.address = address;
        }

        public StoreAtomicAddFloatStmt(Value left, Value rhs) {
            super(TYPE);
            this.rhs = rhs;
            this.left = left;
        }

        private void emitAtomicAddStore(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();
            asm.emit("atomicAdd_Tornado_Floats( &("); // Calling to the
                                                      // intrinsic for Floats
            asm.emit("*(");
            cast.emit(crb, asm);
            asm.space();
            address.emit(crb, asm);
            asm.emit(")), ");
            asm.space();
            asm.emitValue(crb, rhs);
            asm.emit(")");
            asm.delimiter();
            asm.eol();

        }

        private void emitStore(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();
            asm.emit("*(");
            cast.emit(crb, asm);
            asm.space();
            address.emit(crb, asm);
            asm.emit(")");
            asm.space();
            asm.assign();
            asm.space();
            asm.emitValue(crb, rhs);
            asm.delimiter();
            asm.eol();
        }

        private void emitScalarStore(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();
            asm.emitValue(crb, left);
            asm.space();
            asm.assign();
            asm.space();
            asm.emitValue(crb, rhs);
            asm.delimiter();
            asm.eol();
        }

        @Override
        public void emitCode(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();
            if (left == null) {
                if (GENERATE_ATOMIC) {
                    emitAtomicAddStore(crb, asm);
                } else {
                    emitStore(crb, asm);
                }
            }
        }

        public Value getRhs() {
            return rhs;
        }

        public Value getLeft() {
            return left;
        }

        public OCLAddressCast getCast() {
            return cast;
        }

        public MemoryAccess getAddress() {
            return address;
        }
    }

    @Opcode("ATOMIC_SUB_STORE")
    public static class StoreAtomicSubStmt extends AbstractInstruction {

        public static final LIRInstructionClass<StoreStmt> TYPE = LIRInstructionClass.create(StoreStmt.class);

        public static final boolean GENERATE_ATOMIC = true;

        @Use
        protected Value rhs;
        @Use
        protected OCLAddressCast cast;
        @Use
        protected Value left;
        @Use
        protected MemoryAccess address;

        public StoreAtomicSubStmt(OCLAddressCast cast, MemoryAccess address, Value rhs) {
            super(TYPE);
            this.rhs = rhs;
            this.cast = cast;
            this.address = address;
        }

        public StoreAtomicSubStmt(Value left, Value rhs) {
            super(TYPE);
            this.rhs = rhs;
            this.left = left;
        }

        private void emitAtomicSubStore(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.emit("atomic_add( & (");
            asm.emit("*(");
            cast.emit(crb, asm);
            asm.space();
            address.emit(crb, asm);
            asm.emit(")), ");
            asm.space();
            asm.emitValue(crb, rhs);
            asm.emit(")");
            asm.delimiter();
            asm.eol();
        }

        private void emitStore(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();
            asm.emit("*(");
            cast.emit(crb, asm);
            asm.space();
            address.emit(crb, asm);
            asm.emit(")");
            asm.space();
            asm.assign();
            asm.space();
            asm.emitValue(crb, rhs);
            asm.delimiter();
            asm.eol();
        }

        private void emitScalarStore(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();
            asm.emitValue(crb, left);
            asm.space();
            asm.assign();
            asm.space();
            asm.emitValue(crb, rhs);
            asm.delimiter();
            asm.eol();
        }

        @Override
        public void emitCode(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();
            if (left == null) {
                if (GENERATE_ATOMIC) {
                    emitAtomicSubStore(crb, asm);
                } else {
                    emitStore(crb, asm);
                }
            }
        }

        public Value getRhs() {
            return rhs;
        }

        public Value getLeft() {
            return left;
        }

        public OCLAddressCast getCast() {
            return cast;
        }

        public MemoryAccess getAddress() {
            return address;
        }
    }

    @Opcode("ATOMIC_MUL_STORE")
    public static class StoreAtomicMulStmt extends AbstractInstruction {

        public static final LIRInstructionClass<StoreStmt> TYPE = LIRInstructionClass.create(StoreStmt.class);

        public static final boolean GENERATE_ATOMIC = true;

        @Use
        protected Value rhs;
        @Use
        protected OCLAddressCast cast;
        @Use
        protected Value left;
        @Use
        protected MemoryAccess address;

        public StoreAtomicMulStmt(OCLAddressCast cast, MemoryAccess address, Value rhs) {
            super(TYPE);
            this.rhs = rhs;
            this.cast = cast;
            this.address = address;
        }

        public StoreAtomicMulStmt(Value left, Value rhs) {
            super(TYPE);
            this.rhs = rhs;
            this.left = left;
        }

        private void emitAtomicMulStore(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.emit("atomicMul_Tornado_Int( &(");
            asm.emit("*(");
            cast.emit(crb, asm);
            asm.space();
            address.emit(crb, asm);
            asm.emit(")), ");
            asm.space();
            asm.emitValue(crb, rhs);
            asm.emit(")");
            asm.delimiter();
            asm.eol();
        }

        private void emitStore(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();
            asm.emit("*(");
            cast.emit(crb, asm);
            asm.space();
            address.emit(crb, asm);
            asm.emit(")");
            asm.space();
            asm.assign();
            asm.space();
            asm.emitValue(crb, rhs);
            asm.delimiter();
            asm.eol();
        }

        private void emitScalarStore(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();
            asm.emitValue(crb, left);
            asm.space();
            asm.assign();
            asm.space();
            asm.emitValue(crb, rhs);
            asm.delimiter();
            asm.eol();
        }

        @Override
        public void emitCode(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();
            if (left == null) {
                if (GENERATE_ATOMIC) {
                    emitAtomicMulStore(crb, asm);
                } else {
                    emitStore(crb, asm);
                }
            }
        }

        public Value getRhs() {
            return rhs;
        }

        public Value getLeft() {
            return left;
        }

        public OCLAddressCast getCast() {
            return cast;
        }

        public MemoryAccess getAddress() {
            return address;
        }
    }

    @Opcode("VSTORE")
    public static class VectorStoreStmt extends AbstractInstruction {

        public static final LIRInstructionClass<VectorStoreStmt> TYPE = LIRInstructionClass.create(VectorStoreStmt.class);

        @Use
        protected Value rhs;
        @Use
        protected OCLAddressCast cast;
        @Use
        protected MemoryAccess address;
        @Use
        protected Value index;

        protected OCLTernaryIntrinsic op;

        public VectorStoreStmt(OCLTernaryIntrinsic op, Value index, OCLAddressCast cast, MemoryAccess address, Value rhs) {
            super(TYPE);
            this.rhs = rhs;
            this.cast = cast;
            this.address = address;
            this.op = op;
            this.index = index;
        }

        @Override
        public void emitCode(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();
            asm.emit(op.toString());
            asm.emit("(");
            asm.emitValue(crb, rhs);
            asm.emit(", ");
            asm.emitValue(crb, index);
            asm.emit(", ");
            cast.emit(crb, asm);
            asm.space();
            address.emit(crb, asm);
            asm.emit(")");
            asm.delimiter();
            asm.eol();
        }

        public Value getRhs() {
            return rhs;
        }

        public OCLAddressCast getCast() {
            return cast;
        }

        public MemoryAccess getAddress() {
            return address;
        }

        public Value getIndex() {
            return index;
        }

        public OCLTernaryIntrinsic getOp() {
            return op;
        }
    }

    @Opcode("EXPR")
    public static class ExprStmt extends AbstractInstruction {

        public static final LIRInstructionClass<ExprStmt> TYPE = LIRInstructionClass.create(ExprStmt.class);

        @Use
        protected Value expr;

        public ExprStmt(OCLLIROp expr) {
            super(TYPE);
            this.expr = expr;
        }

        @Override
        public void emitCode(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();
            if (expr instanceof OCLLIROp) {
                ((OCLLIROp) expr).emit(crb, asm);
            } else {
                asm.emitValue(crb, expr);
            }
            asm.delimiter();
            asm.eol();
        }

        public Value getExpr() {
            return expr;
        }
    }

    @Opcode("Pragma")
    public static class PragmaExpr extends AbstractInstruction {

        public static final LIRInstructionClass<PragmaExpr> TYPE = LIRInstructionClass.create(PragmaExpr.class);

        @Use
        protected Value prg;

        public PragmaExpr(OCLLIROp prg) {
            super(TYPE);
            this.prg = prg;
        }

        @Override
        public void emitCode(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            if (prg instanceof OCLLIROp) {
                ((OCLLIROp) prg).emit(crb, asm);
            } else {
                asm.emitValue(crb, prg);
            }

        }
    }

    public static class CopyArrayField1TupleExpr extends AbstractInstruction {

        public static final LIRInstructionClass<CopyArrayField1TupleExpr> TYPE = LIRInstructionClass.create(CopyArrayField1TupleExpr.class);

        @Use
        Variable index;

        @Use
        Value arrayLength;

        @Use
        Value arrayElementSize;

        @Use
        Value tupleSize;

        @Use
        Value tupleSizeRet;

        @Use
        Variable outerLoopIndex;

        @Use
        Variable var1;

        @Use
        Variable var2;

        @Use
        Variable var3;

        @Use
        Variable var4;

        @Use
        Variable var5;

        @Use
        Variable var6;

        @Use
        Variable var7;

        @Use
        Variable var8;

        @Use
        Variable var9;

        @Use
        Variable var10;

        @Use
        Value var11;

        @Use
        MemoryAccess readAddress;

        @Use
        MemoryAccess writeAddress;

        @Use
        Value zero;

        @Use
        Value header;

        @Use
        OCLAddressCast readCast;
        @Use
        OCLAddressCast writeCast;

        String type;

        public CopyArrayField1TupleExpr(Value tupleSize, Value tupleSizeRet, Value arrayElementSize, Value arrayLength, Variable outerLoopIndex, Variable index, Variable var1, Variable var2,
                Variable var3, Variable var4, Variable var5, Variable var6, Variable var7, Variable var8, Variable var9, Variable var10, Value var11, MemoryAccess readAddress,
                MemoryAccess writeAddress, Value zero, Value header, String type, OCLAddressCast readCast, OCLAddressCast writeCast) {
            super(TYPE);
            this.tupleSize = tupleSize;
            this.tupleSizeRet = tupleSizeRet;
            this.arrayElementSize = arrayElementSize;
            this.arrayLength = arrayLength;
            this.outerLoopIndex = outerLoopIndex;
            this.index = index;
            this.var1 = var1;
            this.var2 = var2;
            this.var3 = var3;
            this.var4 = var4;
            this.var5 = var5;
            this.var6 = var6;
            this.var7 = var7;
            this.var8 = var8;
            this.var9 = var9;
            this.var10 = var10;
            this.var11 = var11;
            this.readAddress = readAddress;
            this.writeAddress = writeAddress;
            this.zero = zero;
            this.header = header;
            this.type = type;
            this.readCast = readCast;
            this.writeCast = writeCast;
        }

        @Override
        public void emitCode(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            // declare new variables
            // -- index & intermediate variables for offset calculation
            asm.indent();
            asm.emitSymbol("int");
            asm.emitSymbol(" ");
            asm.emitValue(crb, index);
            asm.emitSymbol(", ");
            asm.emitValue(crb, var1);
            asm.emitSymbol(", ");
            asm.emitValue(crb, var2);
            asm.emitSymbol(", ");
            asm.emitValue(crb, var3);
            asm.emitSymbol(", ");
            asm.emitValue(crb, var7);
            asm.emitSymbol(", ");
            asm.emitValue(crb, var8);
            asm.emitSymbol(", ");
            asm.emitValue(crb, var9);
            asm.emitSymbol(";");
            asm.emitSymbol(EOL);
            asm.indent();
            // -- variable that will store array element
            asm.emitSymbol(type);
            asm.emitSymbol(" ");
            asm.emitValue(crb, var6);
            asm.emitSymbol(";");
            asm.emitSymbol(EOL);
            asm.indent();
            // -- variables for read/write address
            asm.emitSymbol("ulong");
            asm.emitSymbol(" ");
            asm.emitValue(crb, var5);
            asm.emitSymbol(", ");
            asm.emitValue(crb, var10);
            asm.emitSymbol(";");
            asm.emitSymbol(EOL);
            asm.indent();
            asm.emitValue(crb, index);
            asm.emitSymbol(" = ");
            asm.emitValue(crb, zero);
            asm.emitSymbol(";");
            asm.emitSymbol(EOL);
            asm.indent();
            asm.emitSymbol(FOR_LOOP);
            asm.emitSymbol(OPEN_PARENTHESIS);
            asm.emitSymbol(STMT_DELIMITER);
            asm.emitValue(crb, index);
            asm.emitSymbol(" < ");
            asm.emitValue(crb, arrayLength);
            asm.emitSymbol(STMT_DELIMITER);
            asm.emitSymbol(CLOSE_PARENTHESIS);
            asm.emitSymbol(TAB);
            asm.emitSymbol(CURLY_BRACKET_OPEN);
            asm.emitSymbol(EOL);
            asm.indent();
            // i_10 = sizeOfArrayElement * index
            asm.emitSymbol(TAB);
            asm.emitValue(crb, var1);
            asm.emitSymbol(" = ");
            asm.emitValue(crb, arrayElementSize);
            asm.emitSymbol(" * ");
            asm.emitValue(crb, index);
            asm.emitSymbol(";");
            asm.emitSymbol(EOL);
            asm.indent();
            // i_11 = sizeOfTuple(f0 + f1) * l_4
            asm.emitSymbol(TAB);
            asm.emitValue(crb, var2);
            asm.emitSymbol(" = ");
            asm.emitValue(crb, tupleSize);
            asm.emitSymbol(" * ");
            asm.emitValue(crb, outerLoopIndex);
            asm.emitSymbol(";");
            asm.emitSymbol(EOL);
            asm.indent();
            // i_12 = i_10 + i_11
            asm.emitSymbol(TAB);
            asm.emitValue(crb, var3);
            asm.emitSymbol(" = ");
            asm.emitValue(crb, var1);
            asm.emitSymbol(" + ");
            asm.emitValue(crb, var2);
            asm.emitSymbol(";");
            asm.emitSymbol(EOL);
            asm.indent();
            // ul_14 = (ul_0 + 24) + i_13;
            asm.emitSymbol(TAB);
            asm.emitValue(crb, var5);
            asm.emitSymbol(" = ");
            readAddress.emit(crb, asm);
            asm.emitSymbol(" + ");
            asm.emitValue(crb, var3);
            asm.emitSymbol(";");
            asm.emitSymbol(EOL);
            asm.indent();
            // arrayElementVar = *((__global type *) ul_14);
            asm.emitSymbol(TAB);
            asm.emitValue(crb, var6);
            asm.emitSymbol(" = ");
            asm.emit("*(");
            readCast.emit(crb, asm);
            asm.space();
            asm.emitValue(crb, var5);
            asm.emit(")");
            asm.emitSymbol(";");
            asm.space();
            asm.emitSymbol(EOL);
            asm.indent();
            // i_16 = sizeOfArrayElement * index;
            asm.emitSymbol(TAB);
            asm.emitValue(crb, var7);
            asm.emitSymbol(" = ");
            asm.emitValue(crb, arrayElementSize);
            asm.emitSymbol(" * ");
            asm.emitValue(crb, index);
            asm.emitSymbol(";");
            asm.emitSymbol(EOL);
            asm.indent();
            // i_17 = sizeOfTuple(f0 + f1) * l_4;
            asm.emitSymbol(TAB);
            asm.emitValue(crb, var8);
            asm.emitSymbol(" = ");
            asm.emitValue(crb, tupleSizeRet);
            asm.emitSymbol(" * ");
            asm.emitValue(crb, outerLoopIndex);
            asm.emitSymbol(";");
            asm.emitSymbol(EOL);
            asm.indent();
            // i_18 = i_16 + i_17;
            asm.emitSymbol(TAB);
            asm.emitValue(crb, var9);
            asm.emitSymbol(" = ");
            asm.emitValue(crb, var7);
            asm.emitSymbol(" + ");
            asm.emitValue(crb, var8);
            asm.emitSymbol(";");
            asm.emitSymbol(EOL);
            asm.indent();
            // ul_20 = (ul_1 + 24) + i_19;
            asm.emitSymbol(TAB);
            asm.emitValue(crb, var10);
            asm.emitSymbol(" = ");
            writeAddress.emit(crb, asm);
            asm.emitSymbol(" + ");
            asm.emitValue(crb, var9);
            asm.emitSymbol(";");
            asm.emitSymbol(EOL);
            asm.indent();
            // *((__global type *) ul_20) = arrayElementVar;
            asm.emitSymbol(TAB);
            asm.emit("*(");
            readCast.emit(crb, asm);
            asm.space();
            asm.emitValue(crb, var10);
            asm.emit(")");
            asm.space();
            asm.assign();
            asm.emitValue(crb, var6);
            asm.emitSymbol(";");
            asm.emitSymbol(EOL);
            asm.indent();
            // index++;
            asm.emitSymbol(TAB);
            asm.emitValue(crb, index);
            asm.emitSymbol(" = ");
            asm.emitValue(crb, index);
            asm.emitSymbol(" + ");
            asm.emitValue(crb, var11);
            asm.emitSymbol(";");
            asm.emitSymbol(EOL);
            asm.indent();
            asm.emitSymbol(CURLY_BRACKET_CLOSE);
            asm.emitSymbol(EOL);
        }
    }
}
