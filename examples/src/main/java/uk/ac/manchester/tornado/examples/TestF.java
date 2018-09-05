package uk.ac.manchester.tornado.examples;

import uk.ac.manchester.tornado.api.annotations.Parallel;

public class TestF {

    public static final class VectorScalarMultiplication extends TornadoMapFunctionBase<Integer, Integer> {
        @Override
        public void compute(int[] a, int[] b) {
            for (@Parallel int i = 0; i < a.length; i++) {
                b[i] = a[i] * 2;

            }
        }
    }

    MapFunction retV() {
        return new VectorScalarMultiplication();
    }

}
