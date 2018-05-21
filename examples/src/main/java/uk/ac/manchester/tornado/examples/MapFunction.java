package uk.ac.manchester.tornado.examples;

import java.io.Serializable;

public interface MapFunction<T, O> extends Function, Serializable {
    O map(T value) throws Exception;
}