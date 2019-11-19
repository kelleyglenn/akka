/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.delivery

import scala.concurrent.duration._
import scala.reflect.ClassTag

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.TimerScheduler
import akka.actor.typed.scaladsl.LoggerOps

// FIXME Scaladoc describes how it works, internally. Rewrite for end user and keep internals as impl notes.

/**
 * The producer will start the flow by sending a [[ProducerController.Start]] message to the `ProducerController` with
 * message adapter reference to convert [[ProducerController.RequestNext]] message.
 * The sends `RequestNext` to the producer, which is then allowed to send one message to the `ProducerController`.
 *
 * The producer and `ProducerController` are supposed to be local so that these messages are fast and not lost.
 *
 * The `ProducerController` sends the first message to the `ConsumerController` without waiting for
 * a `Request` from the `ConsumerController`. The main reason for this is that when used with
 * Cluster Sharding the first message will typically create the `ConsumerController`. It's
 * also a way to connect the ProducerController and ConsumerController in a dynamic way, for
 * example when the ProducerController is replaced.
 *
 * When the first message is received by the `ConsumerController` it sends back the initial `Request`.
 *
 * Apart from the first message the `ProducerController` will not send more messages than requested
 * by the `ConsumerController`.
 *
 * When there is demand from the consumer side the `ProducerController` sends `RequestNext` to the
 * actual producer, which is then allowed to send one more message.
 *
 * Each message is wrapped by the `ProducerController` in [[ConsumerController.SequencedMessage]] with
 * a monotonically increasing sequence number without gaps, starting at 1.
 *
 * The `Request` message also contains a `confirmedSeqNr` that is the acknowledgement
 * from the consumer that it has received and processed all messages up to that sequence number.
 *
 * The `ConsumerController` will send [[ProducerController.Internal.Resend]] if a lost message is detected
 * and then the `ProducerController` will resend all messages from that sequence number. The producer keeps
 * unconfirmed messages in a buffer to be able to resend them. The buffer size is limited
 * by the request window size.
 *
 * The resending is optional, and the `ConsumerController` can be started with `resendLost=false`
 * to ignore lost messages, and then the `ProducerController` will not buffer unconfirmed messages.
 * In that mode it provides only flow control but no reliable delivery.
 */
object ProducerController {

  sealed trait InternalCommand

  sealed trait Command[A] extends InternalCommand

  final case class Start[A](producer: ActorRef[RequestNext[A]]) extends Command[A]

  final case class RequestNext[A](
      producerId: String,
      currentSeqNr: Long,
      confirmedSeqNr: Long,
      sendNextTo: ActorRef[A],
      askNextTo: ActorRef[MessageWithConfirmation[A]])

  final case class RegisterConsumer[A](consumerController: ActorRef[ConsumerController.Command[A]]) extends Command[A]

  /**
   * For sending confirmation message back to the producer when the message has been fully delivered, processed,
   * and confirmed by the consumer. Typically used with `ask` from the producer.
   */
  final case class MessageWithConfirmation[A](message: A, replyTo: ActorRef[Long]) extends InternalCommand

  object Internal {
    final case class Request(confirmedSeqNr: Long, upToSeqNr: Long, supportResend: Boolean, viaTimeout: Boolean)
        extends InternalCommand {
      require(confirmedSeqNr < upToSeqNr)
    }
    final case class Resend(fromSeqNr: Long) extends InternalCommand
    final case class Ack(confirmedSeqNr: Long) extends InternalCommand
  }

  private case class Msg[A](msg: A) extends InternalCommand
  private case object ResendFirst extends InternalCommand

  private final case class State[A](
      requested: Boolean,
      currentSeqNr: Long,
      confirmedSeqNr: Long,
      requestedSeqNr: Long,
      pendingReplies: Map[Long, ActorRef[Long]],
      unconfirmed: Option[Vector[ConsumerController.SequencedMessage[A]]], // FIXME use OptionVal
      firstSeqNr: Long,
      send: ConsumerController.SequencedMessage[A] => Unit)

  def apply[A: ClassTag](producerId: String): Behavior[Command[A]] = {
    waitingForStart[A](None, None) { (producer, consumerController) =>
      val send: ConsumerController.SequencedMessage[A] => Unit = consumerController ! _
      becomeActive(producerId, producer, send)
    }.narrow
  }

  /**
   * For custom `send` function. For example used with Sharding where the message must be wrapped in
   * `ShardingEnvelope(SequencedMessage(msg))`.
   */
  def apply[A: ClassTag](
      producerId: String,
      send: ConsumerController.SequencedMessage[A] => Unit): Behavior[Command[A]] = {
    Behaviors.setup { context =>
      // ConsumerController not used here
      waitingForStart[A](None, consumerController = Some(context.system.deadLetters)) { (producer, _) =>
        becomeActive(producerId, producer, send)
      }.narrow
    }
  }

  private def waitingForStart[A: ClassTag](
      producer: Option[ActorRef[RequestNext[A]]],
      consumerController: Option[ActorRef[ConsumerController.Command[A]]])(
      thenBecomeActive: (ActorRef[RequestNext[A]], ActorRef[ConsumerController.Command[A]]) => Behavior[InternalCommand])
      : Behavior[InternalCommand] = {
    Behaviors.receiveMessagePartial[InternalCommand] {
      case RegisterConsumer(c: ActorRef[ConsumerController.Command[A]] @unchecked) =>
        producer match {
          case Some(p) => thenBecomeActive(p, c)
          case None    => waitingForStart(producer, Some(c))(thenBecomeActive)
        }
      case start: Start[A] @unchecked =>
        consumerController match {
          case Some(c) => thenBecomeActive(start.producer, c)
          case None    => waitingForStart(Some(start.producer), consumerController)(thenBecomeActive)
        }
    }
  }

  private def becomeActive[A: ClassTag](
      producerId: String,
      producer: ActorRef[RequestNext[A]],
      send: ConsumerController.SequencedMessage[A] => Unit): Behavior[InternalCommand] = {

    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        val msgAdapter: ActorRef[A] = ctx.messageAdapter(msg => Msg(msg))
        producer ! RequestNext(producerId, 1L, 0L, msgAdapter, ctx.self)
        new ProducerController[A](ctx, producerId, producer, msgAdapter, timers).active(
          State(
            requested = true,
            currentSeqNr = 1L,
            confirmedSeqNr = 0L,
            requestedSeqNr = 1L,
            pendingReplies = Map.empty,
            unconfirmed = Some(Vector.empty),
            firstSeqNr = 1L,
            send))
      }
    }
  }

}

private class ProducerController[A: ClassTag](
    ctx: ActorContext[ProducerController.InternalCommand],
    producerId: String,
    producer: ActorRef[ProducerController.RequestNext[A]],
    msgAdapter: ActorRef[A],
    timers: TimerScheduler[ProducerController.InternalCommand]) {
  import ProducerController._
  import ProducerController.Internal._
  import ConsumerController.SequencedMessage

  private def active(s: State[A]): Behavior[InternalCommand] = {

    def onMsg(m: A, newPendingReplies: Map[Long, ActorRef[Long]], ack: Boolean): Behavior[InternalCommand] = {
      if (s.requested && s.currentSeqNr <= s.requestedSeqNr) {
        // FIXME adjust all logging, most should probably be debug
        ctx.log.info("sent [{}]", s.currentSeqNr)
        val seqMsg = SequencedMessage(producerId, s.currentSeqNr, m, s.currentSeqNr == s.firstSeqNr, ack)(ctx.self)
        val newUnconfirmed = s.unconfirmed match {
          case Some(u) => Some(u :+ seqMsg)
          case None    => None // no resending, no need to keep unconfirmed
        }
        if (s.currentSeqNr == s.firstSeqNr)
          timers.startTimerWithFixedDelay(ResendFirst, ResendFirst, 1.second)

        s.send(seqMsg)
        val newRequested =
          if (s.currentSeqNr == s.requestedSeqNr)
            false
          else {
            producer ! RequestNext(producerId, s.currentSeqNr, s.confirmedSeqNr, msgAdapter, ctx.self)
            true
          }
        active(
          s.copy(
            requested = newRequested,
            currentSeqNr = s.currentSeqNr + 1,
            pendingReplies = newPendingReplies,
            unconfirmed = newUnconfirmed))
      } else {
        throw new IllegalStateException(
          s"Unexpected Msg when no demand, requested ${s.requested}, " +
          s"requestedSeqNr ${s.requestedSeqNr}, currentSeqNr ${s.currentSeqNr}")
      }
    }

    def onAck(newConfirmedSeqNr: Long): State[A] = {
      // FIXME use more efficient Map for pendingReplies, sorted, maybe just `Vector[(Long, ActorRef)]`
      val newPendingReplies =
        if (s.pendingReplies.isEmpty)
          s.pendingReplies
        else {
          val replies = s.pendingReplies.keys.filter(_ <= newConfirmedSeqNr).toVector.sorted
          if (replies.nonEmpty)
            ctx.log.info("Confirmation replies from [{}] to [{}]", replies.min, replies.max)
          replies.foreach { seqNr =>
            s.pendingReplies(seqNr) ! seqNr
          }
          s.pendingReplies -- replies
        }

      val newUnconfirmed =
        s.unconfirmed match {
          case Some(u) => Some(u.dropWhile(_.seqNr <= newConfirmedSeqNr))
          case None    => None
        }

      if (newConfirmedSeqNr == s.firstSeqNr)
        timers.cancel(ResendFirst)

      s.copy(
        confirmedSeqNr = math.max(s.confirmedSeqNr, newConfirmedSeqNr),
        pendingReplies = newPendingReplies,
        unconfirmed = newUnconfirmed)
    }

    def resendUnconfirmed(newUnconfirmed: Vector[SequencedMessage[A]]): Unit = {
      if (newUnconfirmed.nonEmpty)
        ctx.log.info("resending [{} - {}]", newUnconfirmed.head.seqNr, newUnconfirmed.last.seqNr)
      newUnconfirmed.foreach(s.send)
    }

    Behaviors.receiveMessage {
      case MessageWithConfirmation(m: A, replyTo) =>
        onMsg(m, s.pendingReplies.updated(s.currentSeqNr, replyTo), ack = true)

      case Msg(m: A) =>
        onMsg(m, s.pendingReplies, ack = false)

      case Request(newConfirmedSeqNr, newRequestedSeqNr, supportResend, viaTimeout) =>
        ctx.log.infoN(
          "Request, confirmed [{}], requested [{}], current [{}]",
          newConfirmedSeqNr,
          newRequestedSeqNr,
          s.currentSeqNr)

        val stateAfterAck = onAck(newConfirmedSeqNr)

        val newUnconfirmed =
          if (stateAfterAck.unconfirmed.nonEmpty == supportResend)
            stateAfterAck.unconfirmed
          else if (supportResend)
            Some(Vector.empty)
          else
            None

        if ((viaTimeout || newConfirmedSeqNr == s.firstSeqNr) && newUnconfirmed.nonEmpty) {
          // the last message was lost and no more message was sent that would trigger Resend
          newUnconfirmed.foreach(resendUnconfirmed)
        }

        if (newRequestedSeqNr > s.requestedSeqNr) {
          if (!s.requested && (newRequestedSeqNr - s.currentSeqNr) > 0)
            producer ! RequestNext(producerId, s.currentSeqNr, newConfirmedSeqNr, msgAdapter, ctx.self)
          active(stateAfterAck.copy(requested = true, requestedSeqNr = newRequestedSeqNr, unconfirmed = newUnconfirmed))
        } else {
          active(stateAfterAck.copy(unconfirmed = newUnconfirmed))
        }

      case Ack(newConfirmedSeqNr) =>
        ctx.log.infoN("Ack, confirmed [{}], current [{}]", newConfirmedSeqNr, s.currentSeqNr)
        val stateAfterAck = onAck(newConfirmedSeqNr)
        if (newConfirmedSeqNr == s.firstSeqNr && stateAfterAck.unconfirmed.nonEmpty) {
          stateAfterAck.unconfirmed.foreach(resendUnconfirmed)
        }
        active(stateAfterAck)

      case Resend(fromSeqNr) =>
        s.unconfirmed match {
          case Some(u) =>
            val newUnconfirmed = u.dropWhile(_.seqNr < fromSeqNr)
            resendUnconfirmed(newUnconfirmed)
            active(s.copy(unconfirmed = Some(newUnconfirmed)))
          case None =>
            throw new IllegalStateException("Resend not supported, run the ConsumerController with resendLost = true")
        }

      case ResendFirst =>
        s.unconfirmed match {
          case Some(u) if u.nonEmpty && u.head.seqNr == s.firstSeqNr =>
            ctx.log.info("resending first, [{}]", s.firstSeqNr)
            s.send(u.head.copy(first = true)(ctx.self))
          case _ =>
            if (s.currentSeqNr > s.firstSeqNr)
              timers.cancel(ResendFirst)
        }
        Behaviors.same

      case RegisterConsumer(consumerController: ActorRef[ConsumerController.Command[A]] @unchecked) =>
        val newFirstSeqNr =
          if (s.unconfirmed.isEmpty || s.unconfirmed.get.isEmpty) s.currentSeqNr
          else s.unconfirmed.map(_.head.seqNr).getOrElse(s.currentSeqNr)
        ctx.log.info(
          "Register new ConsumerController [{}], starting with seqNr [{}].",
          consumerController,
          newFirstSeqNr)
        if (s.unconfirmed.nonEmpty) {
          timers.startTimerWithFixedDelay(ResendFirst, ResendFirst, 1.second)
          ctx.self ! ResendFirst
        }
        // update the send function
        val newSend = consumerController ! _
        active(s.copy(firstSeqNr = newFirstSeqNr, send = newSend))
    }
  }
}

// FIXME it must be possible to restart the producer, sending new Start

// FIXME there should also be a durable version of this (using EventSouredBehavior) that stores the
// unconfirmed messages before sending and stores ack event when confirmed.
