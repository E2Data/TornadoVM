package uk.ac.manchester.tornado.api.flink;

/**
 * Class containing data information passed from Flink.
 */
public class FlinkData {

    // TODO: Replace individual arrays with queue
    private byte[] firstByteDataSet;
    private byte[] secondByteDataSet;
    private byte[] thirdByteDataSet;
    private byte[] fourthByteDataSet;
    private byte[] byteResults;
    private int numberOfResBytes;
    private int streamOutPos;
    private boolean reduction;

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

    public FlinkData(byte[] firstByteDataSet, byte[] secondByteDataSet, byte[] thirdByteDataSet, byte[] fourthByteDataSet) {
        this.firstByteDataSet = firstByteDataSet;
        this.secondByteDataSet = secondByteDataSet;
        this.thirdByteDataSet = thirdByteDataSet;
        this.fourthByteDataSet = fourthByteDataSet;
        reduction = true;
    }

    public byte[] getFirstByteDataSet() {
        return firstByteDataSet;
    }

    public byte[] getSecondByteDataSet() {
        return secondByteDataSet;
    }

    public byte[] getThirdByteDataSet() {
        return thirdByteDataSet;
    }

    public byte[] getFourthByteDataSet() {
        return fourthByteDataSet;
    }

    public byte[] getByteResults() {
        return byteResults;
    }

    public int getStreamOutPos() {
        return streamOutPos;
    }

    public boolean isReduction() {
        return this.reduction;
    }

}