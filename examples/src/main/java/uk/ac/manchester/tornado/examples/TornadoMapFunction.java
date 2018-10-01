package uk.ac.manchester.tornado.examples;

public interface TornadoMapFunction<T, O> extends MapFunction<T, O> {

    void tmap(double[] input, double[] output);

    void tmap(double[] a, double[] b, double[] c, double[] d, double[] e, double[] f);

    default O map(T value) {
        // returns 1. Since this is still called to collect the results, null caused an
        // exception
        return (O) new Double(1);
    }

}