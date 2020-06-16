package uk.ac.manchester.tornado.runtime;

import uk.ac.manchester.tornado.api.flink.FlinkCompilerInfo;

/**
 * Intermediate class which stores compiler information that is passed from
 * Flink. This information is accessed by the compiler phases
 * TornadoTupleOffset, TornadoTupleReplacement and TornadoCollectionElimination.
 */
public class FlinkCompilerInfoIntermediate {

    private static FlinkCompilerInfo fcomp;

    public FlinkCompilerInfoIntermediate(FlinkCompilerInfo fcompInfo) {
        fcomp = fcompInfo;
    }

    public static FlinkCompilerInfo getFlinkCompilerInfo() {
        return fcomp;
    }
}
