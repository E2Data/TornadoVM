/*
 * This file is part of Tornado: A heterogeneous programming framework: 
 * https://github.com/beehive-lab/tornadovm
 *
 * Copyright (c) 2013-2020, APT Group, Department of Computer Science,
 * The University of Manchester. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * GNU Classpath is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2, or (at your option)
 * any later version.
 * 
 * GNU Classpath is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with GNU Classpath; see the file COPYING.  If not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 *
 * Linking this library statically or dynamically with other modules is
 * making a combined work based on this library.  Thus, the terms and
 * conditions of the GNU General Public License cover the whole
 * combination.
 * 
 * As a special exception, the copyright holders of this library give you
 * permission to link this library with independent modules to produce an
 * executable, regardless of the license terms of these independent
 * modules, and to copy and distribute the resulting executable under
 * terms of your choice, provided that you also meet, for each linked
 * independent module, the terms and conditions of the license of that
 * module.  An independent module is a module which is not derived from
 * or based on this library.  If you modify this library, you may extend
 * this exception to your version of the library, but you are not
 * obligated to do so.  If you do not wish to do so, delete this
 * exception statement from your version.
 *
 */
package uk.ac.manchester.tornado.api.collections.types;

import static java.lang.Float.floatToRawIntBits;
import static java.lang.Math.abs;
import static java.lang.Math.ulp;
import static uk.ac.manchester.tornado.api.collections.math.TornadoMath.findULPDistance;

public class FloatOps {

    public static final float EPSILON = 1e-7f;
    public static final String fmt = "%.3f";
    public static final String fmt2 = "{%.3f,%.3f}";
    public static final String fmt3 = "{%.3f,%.3f,%.3f}";
    public static final String fmt3e = "{%.4e,%.4e,%.4e}";
    public static final String fmt4 = "{%.3f,%.3f,%.3f,%.3f}";
    public static final String fmt4m = "%.3f,%.3f,%.3f,%.3f";
    public static final String fmt4em = "%.3e,%.3e,%.3e,%.3e";
    public static final String fmt6 = "{%.3f,%.3f,%.3f,%.3f,%.3f,%.3f}";
    public static final String fmt8 = "{%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f}";
    public static final String fmt6e = "{%e,%e,%e,%e,%e,%e}";

    public static final boolean compareBits(float a, float b) {
        long ai = floatToRawIntBits(a);
        long bi = floatToRawIntBits(b);

        long diff = ai ^ bi;
        return (diff == 0);
    }

    public static final boolean compareULP(float value, float expected, float ulps) {
        final float tol = ulps * ulp(expected);
        if (value == expected) {
            return true;
        }

        return abs(value - expected) < tol;
    }

    public static final float findMaxULP(Float2 value, Float2 expected) {
        return findULPDistance(value.storage, expected.storage);
    }

    public static final float findMaxULP(Float3 value, Float3 expected) {
        return findULPDistance(value.storage, expected.storage);
    }

    public static final float findMaxULP(Float4 value, Float4 expected) {
        return findULPDistance(value.storage, expected.storage);
    }

    public static final float findMaxULP(Float6 value, Float6 expected) {
        return findULPDistance(value.storage, expected.storage);
    }

    public static final float findMaxULP(Float8 value, Float8 expected) {
        return findULPDistance(value.storage, expected.storage);
    }

    public static final float findMaxULP(float value, float expected) {
        final float ULP = ulp(expected);

        if (value == expected) {
            return 0f;
        }

        final float absValue = abs(value - expected);
        return absValue / ULP;
    }

    public static final boolean compare(float a, float b) {
        return (abs(a - b) <= EPSILON);
    }

    public static final boolean compare(float a, float b, float tol) {
        return (abs(a - b) <= tol);
    }

    public static final float sq(float value) {
        return value * value;
    }

    public static final void atomicAdd(float[] array, int index, float value) {
        array[index] += value;
    }
}
