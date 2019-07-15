package uk.ac.manchester.tornado.examples;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.annotations.Reduce;

public class ReduceSkeleton {

    public static void reduce(double[] input, @Reduce double[] result) {
        result[0] = 0.0f;
        for (@Parallel int i = 0; i < input.length; i++) {

        }
    }

}