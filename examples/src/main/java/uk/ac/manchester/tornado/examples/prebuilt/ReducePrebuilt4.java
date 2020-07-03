/*
 * Copyright (c) 2013-2020, APT Group, Department of Computer Science,
 * The University of Manchester.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package uk.ac.manchester.tornado.examples.prebuilt;

import java.util.Arrays;
import java.util.Random;
import java.util.stream.IntStream;

import uk.ac.manchester.tornado.api.TaskSchedule;
import uk.ac.manchester.tornado.api.common.Access;
import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.runtime.TornadoRuntime;

public class ReducePrebuilt4 {

    private static final int SIZE = 8192;

    public static void run() {
        int[] i1 = new int[SIZE];
        double[] i2A = new double[SIZE];
        double[] i2B = new double[SIZE];
        long[] i3 = new long[SIZE];

        int size = 8192 / 256;

        int[] o1 = new int[size + 1];
        double[] o2A = new double[size + 1];
        double[] o2B = new double[size + 1];
        long[] o3 = new long[size + 1];

        Random r = new Random();
        IntStream.range(0, SIZE).parallel().forEach(i -> {
            i1[i] = r.nextInt();
            i2A[i] = r.nextDouble();
            i2B[i] = r.nextDouble();
            i3[i] = 1;
        });

        TornadoDevice defaultDevice = TornadoRuntime.getTornadoRuntime().getDefaultDevice();

        new TaskSchedule("s0") //
                .prebuiltTask("t0", //
                        "reduce", //
                        "./pre-compiled/prebuilt-reduceFlink.cl", //
                        new Object[] { i1, i2A, i2B, i3, o1, o2A, o2B, o3 }, //
                        new Access[] { Access.READ, Access.READ, Access.READ, Access.READ, Access.WRITE, Access.WRITE, Access.WRITE, Access.WRITE }, //
                        defaultDevice, //
                        new int[] { SIZE }) //
                .streamOut(o1, o2A, o2B, o3) //
                .execute();

        // Final reduction
        for (int i = 1; i < o1.length; i++) {
            o2A[0] += o2A[i];
            o2B[0] += o2B[i];
            o3[0] += o3[i];
        }

        System.out.println(Arrays.toString(o2A));
        System.out.println(Arrays.toString(o2B));
        System.out.println(Arrays.toString(o3));
    }

    public static void main(String[] args) {
        run();
    }

}
