package uk.ac.manchester.tornado.unittests.flink;

import org.junit.Test;
import uk.ac.manchester.tornado.api.TaskSchedule;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.annotations.Reduce;
import uk.ac.manchester.tornado.unittests.common.TornadoTestBase;

import java.util.Random;
import java.util.stream.IntStream;

import static org.junit.Assert.assertEquals;

public class InstanceReduction extends TornadoTestBase {
    public static final int N = 32;

    public static class ReduceTest {

        public void reduce(float[] input, @Reduce float[] result) {
            result[0] = 0.0f;
            for (@Parallel int i = 0; i < input.length; i++) {
                result[0] += input[i];
            }
        }

    }

    @Test
    public void testReductionIntanceClass() {

        float[] input = new float[N];
        float[] result = new float[1];
        float[] expected = new float[1];

        Random rand = new Random();
        IntStream.range(0, 32).parallel().forEach(i -> {
            input[i] = rand.nextFloat();
        });

        for (int i = 0; i < input.length; i++) {
            expected[0] += input[i];
        }

        ReduceTest rd = new ReduceTest();

        TaskSchedule task = new TaskSchedule("s0").streamIn(input).task("t0", rd::reduce, input, result).streamOut(result);

        task.execute();

        System.out.println("output: " + result[0]);
        assertEquals(expected[0], result[0], 0.01f);
    }

}
