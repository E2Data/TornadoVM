/*
 * This file is part of Tornado: A heterogeneous programming framework:
 * https://github.com/beehive-lab/tornadovm
 *
 * Copyright (c) 2020, APT Group, Department of Computer Science,
 * School of Engineering, The University of Manchester. All rights reserved.
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
 */
package uk.ac.manchester.tornado.drivers.ptx.graal.lir;

import org.graalvm.compiler.core.common.LIRKind;
import org.graalvm.compiler.lir.Opcode;
import org.graalvm.compiler.lir.Variable;
import uk.ac.manchester.tornado.drivers.ptx.graal.asm.PTXAssembler;
import uk.ac.manchester.tornado.drivers.ptx.graal.compiler.PTXCompilationResultBuilder;

@Opcode("VSEL")
public class PTXVectorElementSelect extends PTXLIROp {

    private final Variable vector;
    private final int laneId;
    private final PTXVectorSplit vectorSplitData;

    public PTXVectorElementSelect(LIRKind lirKind, Variable vector, int laneId) {
        super(lirKind);
        this.vector = vector;
        this.laneId = laneId;
        this.vectorSplitData = new PTXVectorSplit(vector);
    }

    @Override
    public void emit(PTXCompilationResultBuilder crb, PTXAssembler asm, Variable dest) {
        asm.emitSymbol(vectorSplitData.getVectorElement(laneId));
    }

    @Override
    public String toString() {
        return String.format("vselect(%s, %d)", vector, laneId);
    }
}
