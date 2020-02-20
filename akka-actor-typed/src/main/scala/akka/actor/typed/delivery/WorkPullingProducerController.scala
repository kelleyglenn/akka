/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.delivery

import java.util.Optional

import scala.reflect.ClassTag

import scala.compat.java8.OptionConverters._
import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.delivery.internal.WorkPullingProducerControllerImpl
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.config.Config

/**
 * Work pulling is a pattern where several worker actors pull tasks in their own pace from
 * a shared work manager instead of that the manager pushes work to the workers blindly
 * without knowing their individual capacity and current availability.
 *
 * The `WorkPullingProducerController` can be used together with [[ConsumerController]] to
 * implement the work pulling pattern.
 *
 * One important property is that the order of the messages should not matter, because each
 * message is routed randomly to one of the workers with demand. In other words, two subsequent
 * messages may be routed to two different workers and processed independent of each other.
 *
 * A worker actor (consumer) and its `ConsumerController` is dynamically registered to the
 * `WorkPullingProducerController` via a [[ServiceKey]]. It will register itself to the
 * * [[akka.actor.typed.receptionist.Receptionist]], and the `WorkPullingProducerController`
 * subscribes to the same key to find active workers. In this way workers can be dynamically
 * added or removed from any node in the cluster.
 *
 * The work manager (producer) actor will start the flow by sending a [[WorkPullingProducerController.Start]]
 * message to the `WorkPullingProducerController`. The `ActorRef` in the `Start` message is
 * typically constructed as a message adapter to map the [[WorkPullingProducerController.RequestNext]]
 * to the protocol of the producer actor.
 *
 * The `WorkPullingProducerController` sends `RequestNext` to the producer, which is then allowed
 * to send one message to the `WorkPullingProducerController` via the `sendNextTo` in the `RequestNext`.
 * Thereafter the producer will receive a new `RequestNext` when it's allowed to send one more message.
 * It will send a new `RequestNext` when there are demand from any worker.
 * It's possible that all workers with demand are deregistered after the `RequestNext` is sent and before
 * the actual messages is sent to the `WorkPullingProducerController`. In that case the message is
 * buffered and will be delivered when a new worker is registered or when there is new demand.
 *
 * The producer and `WorkPullingProducerController` actors are supposed to be local so that these messages are
 * fast and not lost.
 *
 * Many unconfirmed messages can be in flight between the `WorkPullingProducerController` and each
 * `ConsumerController`. The flow control is driven by the consumer side, which means that the
 * `WorkPullingProducerController` will not send faster than the demand requested by the workers.
 *
 * Lost messages are detected, resent and deduplicated if needed. This is also driven by the consumer side,
 * which means that the `WorkPullingProducerController` will not push resends unless requested by the
 * `ConsumerController`.
 *
 * If a worker crashes or is stopped gracefully the unconfirmed messages for that worker will be
 * routed to other workers by the `WorkPullingProducerController`. This may result in that some messages
 * may be processed more than once, by different workers.
 *
 * Until sent messages have been confirmed the `WorkPullingProducerController` keeps them in memory to be able to
 * resend them. If the JVM of the `WorkPullingProducerController` crashes those unconfirmed messages are lost.
 * To make sure the messages can be delivered also in that scenario the `WorkPullingProducerController` can be
 * used with a [[DurableProducerQueue]]. Then the unconfirmed messages are stored in a durable way so
 * that they can be redelivered when the producer is started again. An implementation of the
 * `DurableProducerQueue` is provided in `EventSourcedProducerQueue` in `akka-persistence-typed`.
 *
 * Instead of using `tell` with the `sendNextTo` in the `RequestNext` the producer can use `context.ask`
 * with the `askNextTo` in the `RequestNext`. The difference is that a reply is sent back when the
 * message has been handled. If a `DurableProducerQueue` is used then the reply is sent when the message
 * has been stored successfully, but it might not have been processed by the consumer yet. Otherwise the
 * reply is sent after the consumer has processed and confirmed the message.
 *
 * It's also possible to use the `WorkPullingProducerController` and `ConsumerController` without resending
 * lost messages, but the flow control is still used. This can be more efficient since messages
 * don't have to be kept in memory in the `ProducerController` until they have been
 * confirmed, but the drawback is that lost messages will not be delivered. See configuration
 * `only-flow-control` of the `ConsumerController`.
 */
object WorkPullingProducerController {

  import WorkPullingProducerControllerImpl.UnsealedInternalCommand

  sealed trait Command[A] extends UnsealedInternalCommand

  /**
   * Initial message from the producer actor. The `producer` is typically constructed
   * as a message adapter to map the [[RequestNext]] to the protocol of the producer actor.
   *
   * If the producer is restarted it should send a new `Start` message to the
   * `WorkPullingProducerController`.
   */
  final case class Start[A](producer: ActorRef[RequestNext[A]]) extends Command[A]

  /**
   * The `WorkPullingProducerController` sends `RequestNext` to the producer when it is allowed to send one
   * message via the `sendNextTo` or `askNextTo`. Note that only one message is allowed, and then
   * it must wait for next `RequestNext` before sending one more message.
   */
  final case class RequestNext[A](sendNextTo: ActorRef[A], askNextTo: ActorRef[MessageWithConfirmation[A]])

  /**
   * For sending confirmation message back to the producer when the message has been fully delivered, processed,
   * and confirmed by the consumer. Typically used with `context.ask` from the producer.
   */
  final case class MessageWithConfirmation[A](message: A, replyTo: ActorRef[Done]) extends UnsealedInternalCommand

  /**
   * Retrieve information about registered workers.
   */
  final case class GetWorkerStats[A](replyTo: ActorRef[WorkerStats]) extends Command[A]

  final case class WorkerStats(numberOfWorkers: Int)

  object Settings {

    /**
     * Scala API: Factory method from config `akka.reliable-delivery.work-pulling.producer-controller`
     * of the `ActorSystem`.
     */
    def apply(system: ActorSystem[_]): Settings =
      apply(system.settings.config.getConfig("akka.reliable-delivery.work-pulling.producer-controller"))

    /**
     * Scala API: Factory method from Config corresponding to
     * `akka.reliable-delivery.work-pulling.producer-controller`.
     */
    def apply(config: Config): Settings = {
      new Settings(bufferSize = config.getInt("buffer-size"), ProducerController.Settings(config))
    }

    /**
     * Java API: Factory method from config `akka.reliable-delivery.work-pulling.producer-controller`
     * of the `ActorSystem`.
     */
    def create(system: ActorSystem[_]): Settings =
      apply(system)

    /**
     * java API: Factory method from Config corresponding to
     * `akka.reliable-delivery.work-pulling.producer-controller`.
     */
    def create(config: Config): Settings =
      apply(config)
  }

  final class Settings private (val bufferSize: Int, val producerControllerSettings: ProducerController.Settings) {

    def withBufferSize(newBufferSize: Int): Settings =
      copy(bufferSize = newBufferSize)

    def withProducerControllerSettings(newProducerControllerSettings: ProducerController.Settings): Settings =
      copy(producerControllerSettings = newProducerControllerSettings)

    /**
     * Private copy method for internal use only.
     */
    private def copy(
        bufferSize: Int = bufferSize,
        producerControllerSettings: ProducerController.Settings = producerControllerSettings) =
      new Settings(bufferSize, producerControllerSettings)

    override def toString: String =
      s"Settings($bufferSize,$producerControllerSettings)"
  }

  def apply[A: ClassTag](
      producerId: String,
      workerServiceKey: ServiceKey[ConsumerController.Command[A]],
      durableQueueBehavior: Option[Behavior[DurableProducerQueue.Command[A]]]): Behavior[Command[A]] = {
    Behaviors.setup { context =>
      WorkPullingProducerControllerImpl(producerId, workerServiceKey, durableQueueBehavior, Settings(context.system))
    }
  }

  def apply[A: ClassTag](
      producerId: String,
      workerServiceKey: ServiceKey[ConsumerController.Command[A]],
      durableQueueBehavior: Option[Behavior[DurableProducerQueue.Command[A]]],
      settings: Settings): Behavior[Command[A]] = {
    WorkPullingProducerControllerImpl(producerId, workerServiceKey, durableQueueBehavior, settings)
  }

  /**
   * Java API
   */
  def create[A](
      messageClass: Class[A],
      producerId: String,
      workerServiceKey: ServiceKey[ConsumerController.Command[A]],
      durableQueueBehavior: Optional[Behavior[DurableProducerQueue.Command[A]]]): Behavior[Command[A]] = {
    apply(producerId, workerServiceKey, durableQueueBehavior.asScala)(ClassTag(messageClass))
  }

  /**
   * Java API
   */
  def apply[A: ClassTag](
      messageClass: Class[A],
      producerId: String,
      workerServiceKey: ServiceKey[ConsumerController.Command[A]],
      durableQueueBehavior: Optional[Behavior[DurableProducerQueue.Command[A]]],
      settings: Settings): Behavior[Command[A]] = {
    apply(producerId, workerServiceKey, durableQueueBehavior.asScala, settings)(ClassTag(messageClass))
  }
}
