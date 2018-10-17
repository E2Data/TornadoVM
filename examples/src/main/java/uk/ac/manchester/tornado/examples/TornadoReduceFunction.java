package uk.ac.manchester.tornado.examples;

import uk.ac.manchester.tornado.api.annotations.Reduce;

public interface TornadoReduceFunction<T1> extends ReduceFunction<T1> {

    void treduce(double[] input1, @Reduce double[] output1);

    default T1 reduce(T1 value1, T1 value2) {
        // returns 1. Since this is still called to collect the results, null caused an
        // exception
        return (T1) new Integer(1);
    }

}
