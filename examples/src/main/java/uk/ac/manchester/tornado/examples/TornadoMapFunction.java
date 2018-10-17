package uk.ac.manchester.tornado.examples;

public interface TornadoMapFunction<T, O> extends MapFunction<T, O> {

    void tmap(double[] input1, double[] input2, double[] input3, double[] input4, double[] input5, double[] output);

    default O map(T value) {
        // returns 1. Since this is still called to collect the results, null caused an
        // exception
        return (O) new Integer(1);
    }

}