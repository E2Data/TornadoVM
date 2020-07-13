package uk.ac.manchester.tornado.api.flink;

import java.util.ArrayList;

/**
 * Class containing data information passed from Flink.
 */
public class FlinkData {

    // TODO: Replace individual arrays with queue
    private ArrayList<Object> data = new ArrayList<>();
    private boolean precompiled;
    private boolean plainRed;

    public FlinkData(byte[]... inputBytes) {
        for (byte[] inputData : inputBytes) {
            this.data.add(inputData);
        }
    }

    public FlinkData(boolean precompiled, byte[]... inputBytes) {
        for (byte[] inputData : inputBytes) {
            this.data.add(inputData);
        }

        this.precompiled = precompiled;
    }

    public FlinkData(Object userFunc, boolean plainRed, byte[]... inputBytes) {
        this.data.add(userFunc);
        for (byte[] inputData : inputBytes) {
            this.data.add(inputData);
        }

        this.plainRed = plainRed;
    }

    public ArrayList<Object> getByteDataSets() {
        return this.data;
    }

    public boolean isPlainReduction() {
        return this.plainRed;
    }

    public boolean isPrecompiled() {
        return this.precompiled;
    }
}
