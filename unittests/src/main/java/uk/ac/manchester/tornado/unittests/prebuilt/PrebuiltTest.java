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

package uk.ac.manchester.tornado.unittests.prebuilt;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Test;

import uk.ac.manchester.tornado.api.TaskSchedule;
import uk.ac.manchester.tornado.api.common.Access;
import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.runtime.TornadoRuntime;
import uk.ac.manchester.tornado.unittests.common.TornadoTestBase;

public class PrebuiltTest extends TornadoTestBase {

    @Test
    public void testPrebuild01() {

        final int numElements = 8;
        int[] a = new int[numElements];
        int[] b = new int[numElements];
        int[] c = new int[numElements];

        String tornadoSDK = System.getenv("TORNADO_SDK");

        Arrays.fill(a, 1);
        Arrays.fill(b, 2);

        TornadoDevice defaultDevice = TornadoRuntime.getTornadoRuntime().getDriver(0).getDevice(0);
        String filePath = tornadoSDK + "/examples/generated/";
        filePath += defaultDevice.getDeviceName().contains("cuda") ? "add.ptx" : "add.cl";

        // @formatter:off
        new TaskSchedule("s0")
            .prebuiltTask("t0", 
                        "add", 
                        filePath,
                        new Object[] { a, b, c },
                        new Access[] { Access.READ, Access.READ, Access.WRITE }, 
                        defaultDevice,
                        new int[] { numElements })
            .streamOut(c)
            .execute();
        // @formatter:on

        for (int i = 0; i < c.length; i++) {
            assertEquals(a[i] + b[i], c[i], 0.001);
        }
    }

    @Test
    public void testPrebuild02() {

        final int numElements = 8;
        int[] a = new int[numElements];
        int[] b = new int[numElements];
        int[] c = new int[numElements];

        String tornadoSDK = System.getenv("TORNADO_SDK");

        Arrays.fill(a, 1);
        Arrays.fill(b, 2);

        TornadoDevice defaultDevice = TornadoRuntime.getTornadoRuntime().getDriver(0).getDevice(0);
        String filePath = tornadoSDK + "/examples/generated/";
        filePath += defaultDevice.getDeviceName().contains("cuda") ? "add.ptx" : "add2.cl";

        // @formatter:off
        new TaskSchedule("s0")
                .prebuiltTask("t0",
                        "add",
                        filePath,
                        new Object[] { a, b, c },
                        new Access[] { Access.READ, Access.READ, Access.WRITE },
                        defaultDevice,
                        new int[] { numElements })
                .streamOut(c)
                .execute();
        // @formatter:on

        for (int i = 0; i < c.length; i++) {
            assertEquals(a[i] + b[i], c[i], 0.001);
        }
    }

    @Test
    public void testPrebuiltReduce() {
        double[] input = new double[512];
        int size = input.length / 256;
        double[] output = new double[size];
        Arrays.fill(input, 2.0);
        TornadoDevice defaultDevice = TornadoRuntime.getTornadoRuntime().getDriver(0).getDevice(0);
        String tornadoSDK = System.getenv("TORNADO_SDK");
        String filePath = tornadoSDK + "/examples/generated/";
        filePath += "simple-double-reduce.cl";

        // @formatter:off
        TaskSchedule ts = new TaskSchedule("reduce-min-doubles")
                .prebuiltTask("t0",
                        "reduceDouble",
                        filePath,
                        new Object[] { input, output },
                        new Access[] { Access.READ, Access.WRITE },
                        defaultDevice,
                        new int[] { input.length } )
                .streamOut(output);
        // @formatter:on
        ts.execute();

        System.out.println(Arrays.toString(output));

    }

}
