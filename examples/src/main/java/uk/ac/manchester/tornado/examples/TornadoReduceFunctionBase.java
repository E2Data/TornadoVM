package uk.ac.manchester.tornado.examples;

public abstract class TornadoReduceFunctionBase<T1> implements TornadoReduceFunction<T1> {

    public abstract void compute(double[] a, double[] b, double[] c);

    @Override
    public void treduce(double[] a, double[] b, double[] c) {
        compute(a, b, c);
    }

}
