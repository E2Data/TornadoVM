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

import java.util.Random;
import java.util.stream.IntStream;

public class ReducePrebuilt {

    private static final int SIZE = 8192;

    // Original task
    private static void reductionAddDoubles(double[] input, @Reduce double[] result) {
        result[0] = 0.0f;
        for (@Parallel int i = 0; i < input.length; i++) {
            result[0] += input[i];
        }
    }

    public static void run() {
        double[] input = new double[SIZE];
        int size = 8192 / 256;
        double[] output = new double[size + 1];
        double[] result = new double[1];

        Random r = new Random();
        IntStream.range(0, SIZE).parallel().forEach(i -> {
            input[i] = r.nextDouble();
        });

        TornadoDevice defaultDevice = TornadoRuntime.getTornadoRuntime().getDefaultDevice();
        // @formatter:off
        new TaskSchedule("s0")
                .prebuiltTask("t0",
                        "reductionAddDoubles",
                        "./pre-compiled//prebuilt-reduce.cl",
                        new Object[] { input, output },
                        new Access[] { Access.READ, Access.WRITE },
                        defaultDevice,
                        new int[] { SIZE })  
                .streamOut(output)
                .execute();
        
        // Final reduction
        for (int i = 1; i < output.length; i++) {
            output[0] += output[i];
        }
        
        // Check
        //@formatter:off
        new TaskSchedule("s1")
                .task("t1", ReducePrebuilt::reductionAddDoubles, input, result)
                .streamOut(result)
                .execute();
        //@formatter:on

        if (Math.abs(output[0] - result[0]) > 0.01) {
            System.out.println("[ERROR] Result not correct");
            System.out.println(output[0] + " vs " + result[0]);
        } else {
            System.out.println("Result is correct");
        }
    }

    public static void main(String[] args) {
        run();
    }

}
