package uk.ac.manchester.tornado.examples;

import uk.ac.manchester.tornado.api.TaskSchedule;

import java.util.Arrays;
import java.util.stream.IntStream;

public class TestFlink {

    public static final int BIG_SIZE = 1048576;

    public static int[] getDefaultDataBigArray() {
        int[] ar = new int[BIG_SIZE];
        IntStream.range(0, ar.length).sequential().forEach(idx -> ar[idx] = idx);
        return ar;
    }

    public static void main(String[] args) {

        int a[] = getDefaultDataBigArray();
        int b[] = new int[BIG_SIZE];
        Arrays.fill(b, 0);
        TestF tf = new TestF();
        TornadoMapFunctionBase tm = (TornadoMapFunctionBase) tf.retV();
        new TaskSchedule("s0").task("t0", tm::tmap, a, b).execute();
        // new TaskSchedule("s0").task("t0", (new
        // TestF.VectorScalarMultiplication())::compute, a, b).execute();
    }
}