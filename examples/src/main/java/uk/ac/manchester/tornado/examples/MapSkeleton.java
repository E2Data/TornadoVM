package uk.ac.manchester.tornado.examples;

import uk.ac.manchester.tornado.api.annotations.Parallel;

public class MapSkeleton {

    public static void map(int[] in, int[] out) {

        for (@Parallel int i = 0; i < in.length; i++) {

        }
    }
}
