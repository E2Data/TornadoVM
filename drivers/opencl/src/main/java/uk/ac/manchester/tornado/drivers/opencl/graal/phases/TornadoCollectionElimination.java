package uk.ac.manchester.tornado.drivers.opencl.graal.phases;

import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.RawConstant;
import org.graalvm.compiler.core.common.type.Stamp;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.AbstractBeginNode;
import org.graalvm.compiler.nodes.BeginNode;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.FixedGuardNode;
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

    public static String broadcastedCollection;
    public static int sizeOfCollection = 2;
    public static boolean broadcastedDataset;

    @Override
    protected void run(StructuredGraph graph, TornadoHighTierContext context) {
        if (broadcastedDataset) {

            boolean forEach = false;

            for (Node n : graph.getNodes()) {
                if (n instanceof InvokeNode) {
                    InvokeNode in = (InvokeNode) n;
                    if (in.toString().contains("hasNext")) {
                        forEach = true;
                    }
                }
            }

            if (forEach) {

                System.out.println("[ERROR] We currently do not support for each iterators. Please replace with with a regular for loop.");
                return;
                // ConstantNode forIteratorInput = null;
                //
                // ArrayList<Node> collectionNodesToDelete = new ArrayList<>();
                // for (Node n : graph.getNodes()) {
                // if (n instanceof GlobalThreadIdNode) {
                // forIteratorInput = (ConstantNode) n.inputs().first();
                // }
                // if (n instanceof LoadFieldNode &&
                // n.toString().contains(broadcastedCollection)) {
                // collectionNodesToDelete.add(n);
                // } else if (n instanceof InvokeNode) {
                // collectionNodesToDelete.add(n);
                // }
                // }
                //
                // ValuePhiNode ph;
                //
                // for (Node n : collectionNodesToDelete) {
                // if (n instanceof LoadFieldNode) {
                // System.out.println("--- " + n + " --- 1");
                // Node pred = n.predecessor(); // fixedguard
                // // System.out.println("=== pred: " + pred);
                // Node suc = n.successors().first().successors().first();
                // pred.replaceFirstSuccessor(pred.successors().first(), suc);
                //
                // // System.out.println("== new suc: " + suc);
                //
                // //
                // n.successors().first().successors().first().replaceAtPredecessor(n.predecessor());
                // // // end
                // // System.out
                // // .println("== Before replacement: The predecessor of " +
                // // n.successors().first().successors().first() + " is: " +
                // // n.successors().first().successors().first().predecessor());
                // // suc.replaceAtPredecessor(n.predecessor());
                // // System.out.println("== Replaced the predecessor of " + suc + " to " +
                // // suc.predecessor());
                //
                // // n.predecessor().replaceFirstSuccessor(n,
                // // n.successors().first().successors().first());
                //
                // //
                // n.successors().first().replaceFirstSuccessor(n.successors().first().successors().first(),
                // // null);
                // } else if (n instanceof InvokeNode) {
                // if (n.predecessor() instanceof LoopBeginNode) {
                // // create the nodes for the iteration
                // System.out.println("--- " + n + " --- 2a");
                // LoopBeginNode b = (LoopBeginNode) n.predecessor();
                //
                // Constant loopLimit = new RawConstant(sizeOfCollection);
                // ConstantNode loopLimitNode = new ConstantNode(loopLimit,
                // StampFactory.positiveInt());
                // graph.addWithoutUnique(loopLimitNode);
                //
                // Constant loopIncr = new RawConstant(1);
                // ConstantNode loopIncrNode = new ConstantNode(loopIncr,
                // StampFactory.positiveInt());
                // graph.addWithoutUnique(loopIncrNode);
                //
                // AddNode add = new AddNode(loopIncrNode, loopLimitNode);
                // graph.addWithoutUnique(add);
                //
                // Stamp st = forIteratorInput.stamp().join(add.stamp());
                // ValueNode[] valarray = new ValueNode[] { forIteratorInput, add };
                //
                // ph = new ValuePhiNode(st, b, valarray);
                // graph.addWithoutUnique(ph);
                //
                // add.replaceFirstInput(loopLimitNode, ph);
                //
                // IntegerLessThanNode less = new IntegerLessThanNode(loopLimitNode, ph);
                // graph.addWithoutUnique(less);
                //
                // Node suc = n.successors().first();
                //
                // b.replaceFirstSuccessor(n, suc);
                //
                // // n.replaceFirstSuccessor(n.successors().first(), null);
                // // suc.replaceAtPredecessor(b);
                //
                // if (b.successors().first() instanceof IfNode) {
                // IfNode ifN = (IfNode) b.successors().first();
                // AbstractBeginNode trueSuc = ifN.trueSuccessor();
                // AbstractBeginNode falseSuc = ifN.falseSuccessor();
                // ifN.setTrueSuccessor(falseSuc);
                // ifN.setFalseSuccessor(trueSuc);
                // Node ifcurrIn = ifN.inputs().first();
                // ifN.replaceFirstInput(ifcurrIn, less);
                // ifcurrIn.safeDelete();
                // // if (ifN.trueSuccessor() instanceof BeginNode) {
                // // BeginNode bn = (BeginNode) ifN.trueSuccessor();
                // // if (bn.successors().first() instanceof InvokeNode) {
                // // System.out.println("xxxx - Replace successors for begin");
                // // bn.replaceFirstSuccessor(bn.successors().first(),
                // // bn.successors().first().successors().first());
                // // }
                // // }
                // }
                //
                // } else if (n.predecessor() instanceof BeginNode) {
                // System.out.println("--- " + n + " --- 2b");
                // BeginNode pred = (BeginNode) n.predecessor();
                // Node suc = n.successors().first();
                // System.out.println("Before: node's " + suc + " pred : " + suc.predecessor());
                // // suc.replaceAtPredecessor(pred);
                // pred.replaceFirstSuccessor(n, suc);
                // // pred.replaceFirstSuccessor(n, suc);
                // // n.successors().first().replaceAtPredecessor(pred);
                // // n.replaceFirstSuccessor(n.successors().first(), null);
                // // pred.replaceFirstSuccessor(n, suc);
                //
                // // suc.replaceAtPredecessor(pred);
                // // suc.replaceAtPredecessor(n);
                // // pred.replaceAtPredecessor(suc);
                // // pred.replaceAtPredecessor(n);
                // // ===> n.replaceAtPredecessor(suc);
                // // n.replaceAtUsagesAndDelete(suc);
                // // n.replaceAtPredecessor(pred);
                // // notToDelete = n;
                // System.out.println("After: node's " + suc + " pred : " + suc.predecessor());
                // }
                // }
                // }
                //
                // for (Node n : collectionNodesToDelete) {
                // // if (n != notToDelete) {
                // System.out.println("Collection: delete " + n);
                // for (Node in : n.inputs()) {
                // // if (in instanceof )
                // System.out.println("Collection: delete input " + in + " of node " + n);
                // in.safeDelete();
                // }
                // // n.clearInputs();
                // n.safeDelete();
                // // } else {
                // // System.out.println("Hey! Don't delete node " + n);
                // // }
                // }

            } else {

                for (Node n : graph.getNodes()) {
                    if (n instanceof LoadFieldNode && n.toString().contains("centroid")) {

                        ArrayList<Node> nodesToBeRemoved = new ArrayList<>();
                        nodesToBeRemoved.add(n);

                        Node pred = n.predecessor();
                        Node currSuc = n.successors().first();

                        Node newSuc = currSuc;

                        while (newSuc instanceof InvokeNode || newSuc instanceof FixedGuardNode) {
                            nodesToBeRemoved.add(newSuc);
                            newSuc = newSuc.successors().first();
                        }

                        for (Node nodes : graph.getNodes()) {
                            if (nodes instanceof IntegerLessThanNode) {
                                for (Node in : nodes.inputs()) {
                                    if (in instanceof InvokeNode) {
                                        Constant loopLimitConst = new RawConstant(sizeOfCollection);
                                        ConstantNode loopLimit = new ConstantNode(loopLimitConst, StampFactory.positiveInt());
                                        graph.addWithoutUnique(loopLimit);
                                        nodes.replaceFirstInput(in, loopLimit);
                                    }
                                }
                            }
                        }

                        Node newSucPrev = newSuc.predecessor();

                        // break link
                        newSucPrev.replaceFirstSuccessor(newSuc, null);

                        n.replaceAtPredecessor(newSuc);

                        pred.replaceFirstSuccessor(n, newSuc);

                        for (int i = 0; i < nodesToBeRemoved.size(); i++) {
                            Node del = nodesToBeRemoved.get(i);
                            if (del instanceof LoadFieldNode) {
                                del.safeDelete();
                            } else {
                                for (Node in : del.inputs()) {
                                    in.safeDelete();
                                }
                                del.safeDelete();
                            }
                        }

                    }
                }

            }

        }
    }
}
