package uk.ac.manchester.tornado.examples;

import uk.ac.manchester.tornado.api.annotations.Reduce;

public abstract class TornadoReduceFunctionBase<T1> implements TornadoReduceFunction<T1> {

    public abstract void compute(double[] a, @Reduce double[] b);

    @Override
    public void treduce(double[] a, @Reduce double[] b) {
        compute(a, b);
    }

}
