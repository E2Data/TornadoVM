package uk.ac.manchester.tornado.api.flink;

/**
 * Class containing data information passed from Flink.
 */
public class FlinkData {

    private byte[] firstByteDataSet;
    private byte[] secondByteDataSet;
    private byte[] byteResults;
    private int numberOfResBytes;
    private int streamOutPos;

    public FlinkData(byte[] firstByteDataSet, byte[] secondByteDataSet, int streamOutPos, int numberOfResBytes) {
        this.firstByteDataSet = firstByteDataSet;
        this.secondByteDataSet = secondByteDataSet;
        this.byteResults = new byte[numberOfResBytes];
        this.streamOutPos = streamOutPos;
    }

    public FlinkData(byte[] firstByteDataSet, int streamOutPos, int numberOfResBytes) {
        this.firstByteDataSet = firstByteDataSet;
        this.byteResults = new byte[numberOfResBytes];
        this.streamOutPos = streamOutPos;
    }

    public boolean hasSecondDataSet() {
        return (this.secondByteDataSet != null);
    }

    public byte[] getFirstByteDataSet() {
        return firstByteDataSet;
    }

    public byte[] getSecondByteDataSet() {
        return secondByteDataSet;
    }

    public byte[] getByteResults() {
        return byteResults;
    }

    public void setByteResults(byte[] byteResults) {
        this.byteResults = byteResults;
    }
}
