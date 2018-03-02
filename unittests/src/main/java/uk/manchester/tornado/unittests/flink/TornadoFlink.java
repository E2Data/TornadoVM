package uk.manchester.tornado.unittests.flink;

import static org.junit.Assert.assertArrayEquals;

import java.util.Arrays;

import org.junit.Test;

import tornado.runtime.api.TaskSchedule;
import tornado.unittests.common.TornadoTestBase;

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
