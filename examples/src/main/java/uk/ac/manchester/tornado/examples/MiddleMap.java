package uk.ac.manchester.tornado.examples;

public abstract class MiddleMap<T, O> {

    // name composes of mymap + returntype + arg type

    public abstract int mymapintint(int i);

    public abstract int mymapintdouble(double i);

    public abstract int mymapintfloat(float i);

    public abstract int mymapintlong(long i);

    public abstract double mymapdoubledouble(double i);

    public abstract double mymapdoubleint(int i);

    public abstract double mymapdoublefloat(float i);

    public abstract double mymapdoublelong(long i);

    public abstract float mymapfloatfloat(float i);

    public abstract float mymapfloatint(int i);

    public abstract float mymapfloatdouble(double i);

    public abstract float mymapfloatlong(long i);

    public abstract long mymaplonglong(long i);

    public abstract long mymaplongint(int i);

    public abstract long mymaplongdouble(double i);

    public abstract long mymaplongfloat(float i);

    public abstract O mymapobjectobject(T o);

}
