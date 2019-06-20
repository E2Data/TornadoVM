package uk.ac.manchester.tornado.examples;

import uk.ac.manchester.tornado.api.annotations.Parallel;

public class MapSkeleton {

    public static void map(double[] in, double[] out) {

        for (@Parallel int i = 0; i < in.length; i++) {

        }
    }

    public static void map(int[] in, int[] out) {

        for (@Parallel int i = 0; i < in.length; i++) {

        }

    }

    public static void map(float[] in, float[] out) {

        for (@Parallel int i = 0; i < in.length; i++) {

        }
    }

    public static void map(long[] in, long[] out) {

        for (@Parallel int i = 0; i < in.length; i++) {

        }

    }
}
