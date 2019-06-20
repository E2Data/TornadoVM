package uk.ac.manchester.tornado.examples;

public class FlinkMapUDF {

    public static final class Flink implements MapFunction<Integer, Integer> {

        @Override
        public Integer map(Integer value) {
            int x = value - 2 + 9;
            return value * x;
        }

    }

}
