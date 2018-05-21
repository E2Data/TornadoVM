package uk.ac.manchester.tornado.examples;

public interface TornadoMapFunction<T, O> extends MapFunction<T, O> {
    void tmap(int[] input, int[] output);

    default O map(T value) {
        // returns 1. Since this is still called to collect the results, null caused an
        // exception
        return (O) new Integer(1);
    }
}