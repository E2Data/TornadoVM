package uk.ac.manchester.tornado.examples;

import uk.ac.manchester.tornado.api.TaskSchedule;
import uk.ac.manchester.tornado.api.annotations.Parallel;

public class TestSketcher {

    public class Test {

        public void test(double[] in, double[] out) {
            for (@Parallel int i = 0; i < in.length; i++) {
                out[i] = in[i];
            }
        }

        public void test(int[] in, int[] out) {
            for (@Parallel int i = 0; i < in.length; i++) {
                out[i] = in[i];
            }
        }
    }

    public void callTaskSchedules(double[] in) {
        double[] out = new double[in.length];
        Test t = new Test();
        new TaskSchedule("s0").task("t0", t::test, in, out).streamOut(out).execute();
    }

    public void callTaskSchedule(int[] in2) {
        int[] out2 = new int[in2.length];
        Test t = new Test();
        new TaskSchedule("s0").task("t0", t::test, in2, out2).streamOut(out2).execute();
    }

    public static void main(String[] args) {

        double[] in = new double[] { 1.0, 2.0, 3.0, 5.6, 7.7 };
        double[] out = new double[in.length];
        int[] in2 = new int[] { 1, 2, 3, 5, 7 };
        int[] out2 = new int[in2.length];
        // TestSketcher ts = new TestSketcher();
        // ts.callTaskSchedules(in);
        // ts.callTaskSchedule(in2);
        MiddleMap mdm = new MapASMSkeleton();
        TornadoMap msk = new TornadoMap(mdm);

        new TaskSchedule("s0").task("t0", msk::map, in2, out2).streamOut(out2).execute();

        // mdm = new MapASMSkeleton();
        // msk = new TornadoMap(mdm);

        new TaskSchedule("s0").task("t0", msk::map, in, out).streamOut(out).execute();

        // double[] out = new double[in.length];
        //
        // Test t = new Test();
        //
        // new TaskSchedule("s0").task("t0", t::test, in, out).streamOut(out).execute();
        //
        // int[] in2 = new int[] { 1, 2, 3, 5, 7 };
        // int[] out2 = new int[in2.length];
        //
        // new TaskSchedule("s0").task("t0", t::test, in2,
        // out2).streamOut(out2).execute();

    }

}
