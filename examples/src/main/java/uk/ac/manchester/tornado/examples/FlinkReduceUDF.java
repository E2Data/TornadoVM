package uk.ac.manchester.tornado.examples;

public class FlinkReduceUDF {

    public static final class FlinkReduce implements ReduceFunction<Double> {
        public Double reduce(Double d1, Double d2) {
            return d1 + d2;
        }
    }

}
