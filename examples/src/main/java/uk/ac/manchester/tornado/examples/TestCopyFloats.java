package uk.ac.manchester.tornado.examples;

import uk.ac.manchester.tornado.api.TaskSchedule;
import uk.ac.manchester.tornado.api.annotations.Parallel;

import java.util.Arrays;

public class TestCopyFloats {

    public static class SimpleCopy {

        public void copyFloat(float[] in, float[] out) {
            for (@Parallel int i = 0; i < in.length; i++) {
                out[i] = in[i];
            }
        }

    }

    public static void main(String[] args) {

        SimpleCopy cp = new SimpleCopy();

        float[] in = new float[32];
        float[] out = new float[32];

        Arrays.fill(in, 7L);

        TaskSchedule ts = new TaskSchedule("s0").task("t0", cp::copyFloat, in, out).streamOut(out);
        ts.execute();

        System.out.println(Arrays.toString(out));

    }

}
