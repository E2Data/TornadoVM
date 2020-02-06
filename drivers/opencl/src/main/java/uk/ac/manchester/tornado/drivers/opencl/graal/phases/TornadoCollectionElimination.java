package uk.ac.manchester.tornado.drivers.opencl.graal.phases;

import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.RawConstant;
import org.graalvm.compiler.core.common.type.Stamp;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.AbstractBeginNode;
import org.graalvm.compiler.nodes.BeginNode;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.IfNode;
import org.graalvm.compiler.nodes.InvokeNode;
import org.graalvm.compiler.nodes.LoopBeginNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.ValuePhiNode;
import org.graalvm.compiler.nodes.calc.AddNode;
import org.graalvm.compiler.nodes.calc.IntegerLessThanNode;
import org.graalvm.compiler.nodes.java.LoadFieldNode;
import org.graalvm.compiler.phases.BasePhase;
import uk.ac.manchester.tornado.drivers.opencl.graal.nodes.GlobalThreadIdNode;
import uk.ac.manchester.tornado.runtime.graal.phases.TornadoHighTierContext;

import java.util.ArrayList;

public class TornadoCollectionElimination extends BasePhase<TornadoHighTierContext> {

    public static String broadcastedCollection = "a -";
    public static int sizeOfCollection = 2;
    public static boolean broadcastedDataset = true;

    @Override
    protected void run(StructuredGraph graph, TornadoHighTierContext context) {
        if (broadcastedDataset) {
            ConstantNode forIteratorInput = null;

            ArrayList<Node> collectionNodesToDelete = new ArrayList<>();
            for (Node n : graph.getNodes()) {
                if (n instanceof GlobalThreadIdNode) {
                    forIteratorInput = (ConstantNode) n.inputs().first();
                }
                if (n instanceof LoadFieldNode && ((LoadFieldNode) n).stamp().toString().contains(broadcastedCollection)) {
                    collectionNodesToDelete.add(n);
                } else if (n instanceof InvokeNode) {
                    collectionNodesToDelete.add(n);
                }
            }

            for (Node n : collectionNodesToDelete) {
                if (n instanceof LoadFieldNode) {
                    Node pred = n.predecessor();
                    Node rep = n.successors().first().successors().first();
                    pred.replaceFirstSuccessor(n, rep);
                } else if (n instanceof InvokeNode) {
                    if (n.predecessor() instanceof LoopBeginNode) {
                        // create the nodes for the iteration

                        LoopBeginNode b = (LoopBeginNode) n.predecessor();

                        Constant loopLimit = new RawConstant(sizeOfCollection);
                        ConstantNode loopLimitNode = new ConstantNode(loopLimit, StampFactory.positiveInt());
                        graph.addWithoutUnique(loopLimitNode);

                        Constant loopIncr = new RawConstant(1);
                        ConstantNode loopIncrNode = new ConstantNode(loopIncr, StampFactory.positiveInt());
                        graph.addWithoutUnique(loopIncrNode);

                        AddNode add = new AddNode(loopIncrNode, loopLimitNode);
                        graph.addWithoutUnique(add);

                        Stamp st = forIteratorInput.stamp().join(add.stamp());
                        ValueNode[] valarray = new ValueNode[] { forIteratorInput, add };

                        ValuePhiNode ph = new ValuePhiNode(st, b, valarray);
                        graph.addWithoutUnique(ph);

                        add.replaceFirstInput(loopLimitNode, ph);

                        IntegerLessThanNode less = new IntegerLessThanNode(loopLimitNode, ph);
                        graph.addWithoutUnique(less);

                        Node suc = n.successors().first();

                        b.replaceFirstSuccessor(n, suc);

                        if (b.successors().first() instanceof IfNode) {
                            IfNode ifN = (IfNode) b.successors().first();
                            AbstractBeginNode trueSuc = ifN.trueSuccessor();
                            AbstractBeginNode falseSuc = ifN.falseSuccessor();
                            ifN.setTrueSuccessor(falseSuc);
                            ifN.setFalseSuccessor(trueSuc);
                            Node ifcurrIn = ifN.inputs().first();
                            ifN.replaceFirstInput(ifcurrIn, less);
                            ifcurrIn.safeDelete();
                        }

                    } else if (n.predecessor() instanceof BeginNode) {
                        BeginNode pred = (BeginNode) n.predecessor();
                        Node suc = n.successors().first();
                        pred.replaceFirstSuccessor(n, suc);
                    }
                }
            }

            for (Node n : collectionNodesToDelete) {
                for (Node in : n.inputs()) {
                    in.safeDelete();
                }
                n.safeDelete();
            }
        }
    }
}
