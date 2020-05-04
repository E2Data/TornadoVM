/*
 * This file is part of Tornado: A heterogeneous programming framework: 
 * https://github.com/beehive-lab/tornadovm
 *
 * Copyright (c) 2013-2020, APT Group, Department of Computer Science,
 * The University of Manchester. All rights reserved.
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

import static uk.ac.manchester.tornado.api.exceptions.TornadoInternalError.unimplemented;
import static uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLBinaryIntrinsic.CROSS;
import static uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLBinaryIntrinsic.DOT;
import static uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLBinaryIntrinsic.FLOAT_MAX;
import static uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLBinaryIntrinsic.FLOAT_MIN;
import static uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLBinaryIntrinsic.FLOAT_POW;
import static uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLBinaryIntrinsic.INT_MAX;
import static uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLBinaryIntrinsic.INT_MIN;
import static uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLTernaryIntrinsic.CLAMP;
import static uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLUnaryIntrinsic.ABS;
import static uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLUnaryIntrinsic.COS;
import static uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLUnaryIntrinsic.EXP;
import static uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLUnaryIntrinsic.FLOAT_ABS;
import static uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLUnaryIntrinsic.FLOAT_FLOOR;
import static uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLUnaryIntrinsic.FLOAT_TRUNC;
import static uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLUnaryIntrinsic.LOG;
import static uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLUnaryIntrinsic.POPCOUNT;
import static uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLUnaryIntrinsic.SIN;
import static uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLUnaryIntrinsic.SQRT;
import static uk.ac.manchester.tornado.runtime.graal.compiler.TornadoCodeGenerator.trace;

import org.graalvm.compiler.core.common.LIRKind;
import org.graalvm.compiler.lir.Variable;

import jdk.vm.ci.meta.Value;

public class OCLBuiltinTool {

    public Value genFloatACos(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatACosh(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatACospi(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatASin(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatASinh(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatASinpi(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatATan(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatATanh(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatATanpi(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatCbrt(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatCeil(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatCos(Value input) {
        trace("genCos: cos(%s)", input);
        return new OCLUnary.Intrinsic(COS, LIRKind.value(input.getPlatformKind()), input);
    }

    public Value genFloatCosh(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatCospi(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatErfc(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatErf(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatExp(Value input) {
        trace("genExp: exp(%s)", input);
        return new OCLUnary.Intrinsic(EXP, LIRKind.value(input.getPlatformKind()), input);
    }

    public Value genFloatExp2(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatExp10(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatExpm1(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatFloor(Value input) {
        trace("genFloatFloor: floor(%s)", input);
        return new OCLUnary.Intrinsic(FLOAT_FLOOR, LIRKind.value(input.getPlatformKind()), input);
    }

    public Value genFloatILogb(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatLGamma(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatLog(Value input) {
        trace("genLog: log(%s)", input);
        return new OCLUnary.Intrinsic(LOG, LIRKind.value(input.getPlatformKind()), input);
    }

    public Value genFloatLog2(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatLog10(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatLog1p(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatLogb(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatNan(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatRint(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatRound(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatRSqrt(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatSin(Value input) {
        trace("genSin: sin(%s)", input);
        return new OCLUnary.Intrinsic(SIN, LIRKind.value(input.getPlatformKind()), input);
    }

    public Value genFloatSinh(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatSinpi(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatTan(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatTanh(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatTanpi(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatTGamma(Value input) {
        unimplemented();
        return null;
    }

    public Value genFloatTrunc(Value input) {
        trace("genFloatTrunc: trunc(%s)", input);
        return new OCLUnary.Intrinsic(FLOAT_TRUNC, LIRKind.value(input.getPlatformKind()), input);
    }

    public Value genFloatATan2(Value x, Value y) {
        unimplemented();
        return null;
    }

    public Value genFloatATan2pi(Value x, Value y) {
        unimplemented();
        return null;
    }

    public Value genFloatCopySign(Value x, Value y) {
        unimplemented();
        return null;
    }

    public Value genFloatDim(Value x, Value y) {
        unimplemented();
        return null;
    }

    public Value genFloatFma(Value x, Value y) {
        unimplemented();
        return null;
    }

    public Value genFloatMax(Value x, Value y) {
        trace("genFloatMax: max(%s,%s)", x, y);
        return new OCLBinary.Intrinsic(FLOAT_MAX, LIRKind.combine(x, y), x, y);
    }

    public Value genFloatMin(Value x, Value y) {
        trace("genFloatMin: min(%s,%s)", x, y);
        return new OCLBinary.Intrinsic(FLOAT_MIN, LIRKind.combine(x, y), x, y);
    }

    public Value genFloatMod(Value x, Value y) {
        unimplemented();
        return null;
    }

    public Value genFloatFract(Value x, Value y) {
        unimplemented();
        return null;
    }

    public Value genFloatFrexp(Value x, Value y) {
        unimplemented();
        return null;
    }

    public Value genFloatHypot(Value x, Value y) {
        unimplemented();
        return null;
    }

    public Value genFloatLdexp(Value x, Value y) {
        unimplemented();
        return null;
    }

    public Value genFloatMad(Value x, Value y) {
        unimplemented();
        return null;
    }

    public Value genFloatMaxmag(Value x, Value y) {
        unimplemented();
        return null;
    }

    public Value genFloatMinmag(Value x, Value y) {
        unimplemented();
        return null;
    }

    public Value genFloatModf(Value x, Value y) {
        unimplemented();
        return null;
    }

    public Value genFloatNextAfter(Value x, Value y) {
        unimplemented();
        return null;
    }

    public Value genFloatPow(Value x, Value y) {
        trace("genFloatPow: pow(%s,%s)", x, y);
        return new OCLBinary.Intrinsic(FLOAT_POW, LIRKind.combine(x, y), x, y);
    }

    public Value genFloatPown(Value x, Value y) {
        unimplemented();
        return null;
    }

    public Value genFloatPowr(Value x, Value y) {
        unimplemented();
        return null;
    }

    public Value genFloatRemainder(Value x, Value y) {
        unimplemented();
        return null;
    }

    public Value genFloatRootn(Value x, Value y) {
        unimplemented();
        return null;
    }

    public Value genFloatSincos(Value x, Value y) {
        unimplemented();
        return null;
    }

    public Variable genBitCount(Value input) {
        unimplemented();
        return null;
    }

    public Variable genBitScanForward(Value input) {
        unimplemented();
        return null;
    }

    public Variable genBitScanReverse(Value input) {
        unimplemented();
        return null;
    }

    public Value genIntAbs(Value input) {
        trace("genIntAbs: abs(%s)", input);
        return new OCLUnary.Intrinsic(ABS, LIRKind.value(input.getPlatformKind()), input);
    }

    public Value genFloatSqrt(Value input) {
        trace("genAbs: sqrt(%s)", input);
        return new OCLUnary.Intrinsic(SQRT, LIRKind.value(input.getPlatformKind()), input);
    }

    public Value genIntMax(Value x, Value y) {
        trace("genMax: max(%s,%s)", x, y);
        return new OCLBinary.Intrinsic(INT_MAX, LIRKind.combine(x, y), x, y);
    }

    public Value genIntMin(Value x, Value y) {
        trace("genMin: min(%s,%s)", x, y);
        return new OCLBinary.Intrinsic(INT_MIN, LIRKind.combine(x, y), x, y);
    }

    public Value genIntClz(Value value) {
        unimplemented();
        return null;
    }

    public Value genIntPopcount(Value value) {
        trace("genBitCount: bitcount(%s)", value);
        return new OCLUnary.Intrinsic(POPCOUNT, LIRKind.value(value.getPlatformKind()), value);
    }

    public Value genIntClamp(Value x, Value y, Value z) {
        trace("genIntClamp: clamp(%s, %s, %s)", x, y, z);
        return new OCLTernary.Intrinsic(CLAMP, LIRKind.combine(x, y, z), x, y, z);
    }

    public Value genIntMad24(Value x, Value y, Value z) {
        unimplemented();
        return null;
    }

    public Value genIntMadHi(Value x, Value y, Value z) {
        unimplemented();
        return null;
    }

    public Value genIntMadSat(Value x, Value y, Value z) {
        unimplemented();
        return null;
    }

    public Value genFloatAbs(Value input) {
        trace("genFloatAbs: abs(%s)", input);
        return new OCLUnary.Intrinsic(FLOAT_ABS, LIRKind.value(input.getPlatformKind()), input);
    }

    public Value genFloatFMA(Value x, Value y, Value z) {
        unimplemented();
        return null;
    }

    public Value genFloatMAD(Value x, Value y, Value z) {
        unimplemented();
        return null;
    }

    public Value genFloatRemquo(Value x, Value y, Value z) {
        unimplemented();
        return null;
    }

    public Value genGeometricDot(Value x, Value y) {
        trace("genDot: dot(%s,%s)", x, y);
        return new OCLBinary.Intrinsic(DOT, LIRKind.combine(x, y), x, y);
    }

    public Value genGeometricCross(Value x, Value y) {
        trace("genCross: cross(%s,%s)", x, y);
        return new OCLBinary.Intrinsic(CROSS, LIRKind.combine(x, y), x, y);
    }

}
