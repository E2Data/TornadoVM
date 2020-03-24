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

import uk.ac.manchester.tornado.api.TaskSchedule;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.annotations.Reduce;
import uk.ac.manchester.tornado.api.common.Access;
import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.runtime.TornadoRuntime;

import java.util.Arrays;
import java.util.Random;
import java.util.stream.IntStream;

public class ReducePrebuilt3 {

    private static final int SIZE = 8192;

    // Original task
    private static void reductionAddDoubles(double[] input, @Reduce double[] result1, @Reduce double[] result2, @Reduce double[] result3) {
        result1[0] = 0.0f;
        result2[0] = 0.0f;
        result3[0] = 0.0f;
        for (@Parallel int i = 0; i < input.length; i++) {
            result1[0] += input[i];
            result2[0] += input[i];
            result3[0] += input[i];
        }
    }

    public static void run() {
        long[] input = new long[SIZE];
        int size = 8192 / 256;
        long[] output1 = new long[size + 1];
        long[] output2 = new long[size + 1];
        long[] output3 = new long[size + 1];
        double[] result = new double[1];

        Random r = new Random();
        IntStream.range(0, SIZE).parallel().forEach(i -> {
            input[i] = r.nextLong();
        });

        TornadoDevice defaultDevice = TornadoRuntime.getTornadoRuntime().getDefaultDevice();
        // @formatter:off
        new TaskSchedule("s0")
                .prebuiltTask("t0",
                        "reductionAddDoubles",
                        "./pre-compiled/prebuilt-reduce3.cl",
                        new Object[] { input, output1, output2 , output3 },
                        new Access[] { Access.READ, Access.WRITE, Access.WRITE , Access.WRITE },
                        defaultDevice,
                        new int[] { SIZE })
                .streamOut(output1, output2, output3)
                .execute();
        // @formatter:on

        // Final reduction
        for (int i = 1; i < output1.length; i++) {
            output1[0] += output1[i];
            output2[0] += output2[i];
            output3[0] += output3[i];
        }

        System.out.println(Arrays.toString(output1));
        System.out.println(Arrays.toString(output2));
        System.out.println(Arrays.toString(output3));

    }

    public static void main(String[] args) {
        run();
    }

}
