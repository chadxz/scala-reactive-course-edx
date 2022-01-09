/** Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
  */
package actorbintree

import akka.actor._
import scala.collection.immutable.Queue

object BinaryTreeSet {

  trait Operation {
    def requester: ActorRef
    def id: Int
    def elem: Int
  }

  trait OperationReply {
    def id: Int
  }

  /** Request with identifier `id` to insert an element `elem` into the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Insert(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to check whether an element `elem` is present
    * in the tree. The actor at reference `requester` should be notified when
    * this operation is completed.
    */
  case class Contains(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to remove the element `elem` from the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Remove(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request to perform garbage collection */
  case object GC

  /** Holds the answer to the Contains request with identifier `id`. `result` is
    * true if and only if the element is present in the tree.
    */
  case class ContainsResult(id: Int, result: Boolean) extends OperationReply

  /** Message to signal successful completion of an insert or remove operation.
    */
  case class OperationFinished(id: Int) extends OperationReply

}

class BinaryTreeSet extends Actor {
  import BinaryTreeSet._
  import BinaryTreeNode._

  def createRoot: ActorRef =
    context.actorOf(BinaryTreeNode.props(0))

  var root: ActorRef = createRoot

  // optional (used to stash incoming operations during garbage collection)
  var pendingQueue: Queue[Operation] = Queue.empty[Operation]

  // optional
  def receive: Receive = normal

  // optional
  /** Accepts `Operation` and `GC` messages. */
  val normal: Receive = {
    case o: Operation => root ! o
    case GC =>
      val newRoot = createRoot
      root ! CopyTo(newRoot)
      context.become(garbageCollecting(newRoot))
  }

  // optional
  /** Handles messages while garbage collection is performed. `newRoot` is the
    * root of the new binary tree where we want to copy all non-removed elements
    * into.
    */
  def garbageCollecting(newRoot: ActorRef): Receive = {
    case o: Operation => pendingQueue = pendingQueue.enqueue(o)
    case CopyFinished =>
      val oldRoot = root
      root = newRoot
      context.stop(oldRoot)

      while (pendingQueue.nonEmpty) {
        val (o, rest) = pendingQueue.dequeue
        root ! o
        pendingQueue = rest
      }

      context.become(normal)
  }
}

object BinaryTreeNode {
  trait Position

  case object Left extends Position
  case object Right extends Position

  case class CopyTo(treeNode: ActorRef)

  /** Acknowledges that a copy has been completed. This message should be sent
    * from a node to its parent, when this node and all its children nodes have
    * finished being copied.
    */
  case object CopyFinished

  def props(elem: Int, initiallyRemoved: Boolean = false): Props =
    Props(classOf[BinaryTreeNode], elem, initiallyRemoved)
}

class BinaryTreeNode(val elem: Int, initiallyRemoved: Boolean = false)
    extends Actor {
  import BinaryTreeNode._
  import BinaryTreeSet._

  var subtrees: Map[Position, ActorRef] = Map[Position, ActorRef]()
  var removed: Boolean = initiallyRemoved

  // optional
  def receive: Receive = normal

  // optional
  /** Handles `Operation` messages and `CopyTo` requests. */
  val normal: Receive = {
    case Insert(requester, id, receivedElem) =>
      if (receivedElem != elem) {
        val Side = if (receivedElem < elem) Left else Right
        subtrees.get(Side) match {
          case Some(actor) =>
            actor ! Insert(requester, id, receivedElem)
          case None =>
            subtrees = subtrees + (Side -> context.actorOf(
              BinaryTreeNode.props(receivedElem)
            ))
            requester ! OperationFinished(id)
        }
      } else {
        removed = false
        requester ! OperationFinished(id)
      }
    case Contains(requester, id, receivedElem) =>
      if (receivedElem != elem) {
        val Side = if (receivedElem < elem) Left else Right
        subtrees.get(Side) match {
          case Some(actor) => actor ! Contains(requester, id, receivedElem)
          case None        => requester ! ContainsResult(id, result = false)
        }
      } else requester ! ContainsResult(id, result = !removed)
    case Remove(requester, id, receivedElem) =>
      if (receivedElem != elem) {
        val Side = if (receivedElem < elem) Left else Right
        subtrees.get(Side) match {
          case Some(actor) => actor ! Remove(requester, id, receivedElem)
          case None        => requester ! OperationFinished(id)
        }
      } else {
        removed = true
        requester ! OperationFinished(id)
      }
    case CopyTo(treeNode) => {
      if (!removed) {
        treeNode ! Insert(self, 999, elem)
      }

      for { actor <- subtrees.values } actor ! CopyTo(treeNode)
      context.become(copying(subtrees.values.toSet, insertConfirmed = false))
    }
  }

  // optional
  /** `expected` is the set of ActorRefs whose replies we are waiting for,
    * `insertConfirmed` tracks whether the copy of this node to the new tree has
    * been confirmed.
    */
  def copying(expected: Set[ActorRef], insertConfirmed: Boolean): Receive = {
    case OperationFinished(_) =>
      context.become(copying(expected, insertConfirmed = true))
    case CopyFinished => ???
  }

}
