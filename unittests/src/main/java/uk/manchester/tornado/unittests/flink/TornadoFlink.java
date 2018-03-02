/*
 * This file is part of Tornado: A heterogeneous programming framework: 
 * https://github.com/beehive-lab/tornado
 *
 * Copyright (c) 2013-2018, APT Group, School of Computer Science,
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
 * Authors: Juan Fumero
 *
 */
package uk.manchester.tornado.unittests.flink;

import static org.junit.Assert.assertArrayEquals;

import java.util.Arrays;

import org.junit.Test;

import uk.ac.manchester.tornado.runtime.api.TaskSchedule;
import uk.ac.manchester.tornado.unittests.common.TornadoTestBase;

public class TornadoFlink extends TornadoTestBase {

    public static final int N = 16;

    private interface Map {
        default int map() {
            return 0;
        }
    }

    private interface TornadoFlinkMap extends Map {
        public void tmap(int[] a, int[] b);
    }

    private static class TornadoFlinkMapFunction implements TornadoFlinkMap {
        @Override
        public void tmap(int[] a, int[] b) {
            for (int i = 0; i < a.length; i++) {
                b[i] = a[i] + 10;
            }
        }
    }

    @Test
    public static void main(String[] args) {

        int[] input = new int[N];
        int[] expected = new int[N];
        int[] output = new int[N];

        Arrays.fill(input, 10);
        Arrays.fill(expected, 20);

        TornadoFlinkMapFunction f = new TornadoFlinkMapFunction();

        TaskSchedule task = new TaskSchedule("s0").streamIn(input).task("t0", f::tmap, input, output).streamOut(output);

        task.execute();

        System.out.println("output: " + Arrays.toString(output));
        assertArrayEquals(expected, output);
    }

}
