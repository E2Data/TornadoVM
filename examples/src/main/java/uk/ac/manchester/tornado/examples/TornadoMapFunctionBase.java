package uk.ac.manchester.tornado.examples;

public abstract class TornadoMapFunctionBase<T, O> implements TornadoMapFunction<T, O> {

    public abstract void compute(int[] a, int[] b);

    @Override
    public void tmap(int[] a, int[] b) {
        compute(a, b);
    }
}
