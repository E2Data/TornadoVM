package uk.ac.manchester.tornado.examples;

import uk.ac.manchester.tornado.api.TaskSchedule;
import uk.ac.manchester.tornado.api.TornadoDriver;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.enums.TornadoDeviceType;
import uk.ac.manchester.tornado.api.runtime.TornadoRuntime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.IntStream;

public class TestFlink {

    // public static final double[] centroids_id = new double[] { 1, 2, 3 };
    // public static final double[] centroids_x = new double[] { -31.85f, 35.16f,
    // 2.3f };
    // public static final double[] centroids_y = new double[] { -44.77f, 17.46f,
    // 3.1f };

    // public static final double[] points_x = new double[] { -14.22f, 62.78f,
    // 76.18f, 35.04f, -9.53f, -34.35f, 1.2f, 1.3, -8.52, 6.5 };
    // public static final double[] points_y = new double[] { -48.01f, 57.10f,
    // 56.18f, -42.99f, 50.29f, -46.26f, 3.4f, 4.3, 48.21, 7.2 };

    public static final double[] p_x = new double[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 };
    // public static final double[] p_y = new double[] { 1, 2, 4, 4, 5, 6, 7, 8, 9,
    // 10 };

    /*
     * public static double[] c_1(double[] point_x, double[] point_y, double[]
     * accres) { double p1,p2,sumx,sumy; if (point_x.length == 1) { p1 = point_x[0];
     * p2 = point_y[0]; sumx = p1; sumy = p2; } else { p1 = point_x[0] + point_x[1];
     * p2 = point_y[0] + point_y[1]; sumx = p1; sumy = p2; } for (@Parallel int i =
     * 2; i < point_x.length; i++) { p1 = sumx + point_x[i]; p2 = sumy + point_y[i];
     * sumx = p1; sumy = p2; }
     * 
     * accres[0] = sumx; accres[1] = sumy; accres[2] = point_x.length; return
     * accres; }
     */

    public static void main(String[] args) {
        double[] test = new double[4];
        TestF tf = new TestF();
        TornadoReduceFunctionBase accum = (TornadoReduceFunctionBase) tf.retAccum();
        new TaskSchedule("s1").task("t1", accum::treduce, p_x, test).streamOut(test).execute();
        System.out.println(Arrays.toString(test));
        // System.out.println("test = " + test[0]);

    }

}