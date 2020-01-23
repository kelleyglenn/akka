/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.delivery

import scala.concurrent.duration._

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.internal.delivery.ProducerController.MessageWithConfirmation
import org.scalatest.wordspec.AnyWordSpecLike

class ProducerControllerSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with LogCapturing {
  import TestConsumer.sequencedMessage

  private var idCount = 0
  private def nextId(): Int = {
    idCount += 1
    idCount
  }

  private def producerId: String = s"p-$idCount"

  "ProducerController" must {

    "resend lost initial SequencedMessage" in {
      nextId()
      val consumerControllerProbe = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()

      val producerController =
        spawn(ProducerController[TestConsumer.Job](producerId, None), s"producerController-${idCount}")
      val producerProbe = createTestProbe[ProducerController.RequestNext[TestConsumer.Job]]()
      producerController ! ProducerController.Start(producerProbe.ref)

      producerController ! ProducerController.RegisterConsumer(consumerControllerProbe.ref)

      val sendTo = producerProbe.receiveMessage().sendNextTo
      sendTo ! TestConsumer.Job("msg-1")

      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 1, producerController))

      // the ConsumerController will send initial `Request` back, but if that is lost or if the first
      // `SequencedMessage` is lost the ProducerController will resend the SequencedMessage
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 1, producerController))

      val internalProducerController = producerController.unsafeUpcast[ProducerController.InternalCommand]
      internalProducerController ! ProducerController.Internal.Request(1L, 10L, true, false)
      consumerControllerProbe.expectNoMessage(1100.millis)

      testKit.stop(producerController)
    }

    "resend lost SequencedMessage when receiving Resend" in {
      nextId()
      val consumerControllerProbe = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()

      val producerController =
        spawn(ProducerController[TestConsumer.Job](producerId, None), s"producerController-${idCount}")
          .unsafeUpcast[ProducerController.InternalCommand]
      val producerProbe = createTestProbe[ProducerController.RequestNext[TestConsumer.Job]]()
      producerController ! ProducerController.Start(producerProbe.ref)

      producerController ! ProducerController.RegisterConsumer(consumerControllerProbe.ref)

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-1")
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 1, producerController))

      producerController ! ProducerController.Internal.Request(1L, 10L, true, false)

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-2")
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 2, producerController))

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-3")
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 3, producerController))
      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-4")
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 4, producerController))

      // let's say 3 is lost, when 4 is received the ConsumerController detects the gap and sends Resend(3)
      producerController ! ProducerController.Internal.Resend(3)

      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 3, producerController))
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 4, producerController))

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-5")
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 5, producerController))

      testKit.stop(producerController)
    }

    "resend last lost SequencedMessage when receiving Request" in {
      nextId()
      val consumerControllerProbe = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()

      val producerController =
        spawn(ProducerController[TestConsumer.Job](producerId, None), s"producerController-${idCount}")
          .unsafeUpcast[ProducerController.InternalCommand]
      val producerProbe = createTestProbe[ProducerController.RequestNext[TestConsumer.Job]]()
      producerController ! ProducerController.Start(producerProbe.ref)

      producerController ! ProducerController.RegisterConsumer(consumerControllerProbe.ref)

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-1")
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 1, producerController))

      producerController ! ProducerController.Internal.Request(1L, 10L, true, false)

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-2")
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 2, producerController))

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-3")
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 3, producerController))
      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-4")
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 4, producerController))

      // let's say 3 and 4 are lost, and no more messages are sent from producer
      // ConsumerController will resend Request periodically
      producerController ! ProducerController.Internal.Request(2L, 10L, true, true)

      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 3, producerController))
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 4, producerController))

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-5")
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 5, producerController))

      testKit.stop(producerController)
    }

    "support registration of new ConsumerController" in {
      nextId()

      val producerController =
        spawn(ProducerController[TestConsumer.Job](producerId, None), s"producerController-${idCount}")
          .unsafeUpcast[ProducerController.InternalCommand]
      val producerProbe = createTestProbe[ProducerController.RequestNext[TestConsumer.Job]]()
      producerController ! ProducerController.Start(producerProbe.ref)

      val consumerControllerProbe1 = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()
      producerController ! ProducerController.RegisterConsumer(consumerControllerProbe1.ref)

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-1")
      consumerControllerProbe1.expectMessage(sequencedMessage(producerId, 1, producerController))

      producerController ! ProducerController.Internal.Request(1L, 10L, true, false)

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-2")
      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-3")

      val consumerControllerProbe2 = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()
      producerController ! ProducerController.RegisterConsumer(consumerControllerProbe2.ref)

      consumerControllerProbe2.expectMessage(
        sequencedMessage(producerId, 2, producerController).copy(first = true)(producerController))
      consumerControllerProbe2.expectNoMessage(100.millis)
      // if no Request confirming the first (seqNr=2) it will resend it
      consumerControllerProbe2.expectMessage(
        sequencedMessage(producerId, 2, producerController).copy(first = true)(producerController))

      producerController ! ProducerController.Internal.Request(2L, 10L, true, false)
      // then the other unconfirmed should be resent
      consumerControllerProbe2.expectMessage(sequencedMessage(producerId, 3, producerController))

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-4")
      consumerControllerProbe2.expectMessage(sequencedMessage(producerId, 4, producerController))

      testKit.stop(producerController)
    }

    "reply to MessageWithConfirmation" in {
      nextId()
      val consumerControllerProbe = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()

      val producerController =
        spawn(ProducerController[TestConsumer.Job](producerId, None), s"producerController-${idCount}")
          .unsafeUpcast[ProducerController.InternalCommand]
      val producerProbe = createTestProbe[ProducerController.RequestNext[TestConsumer.Job]]()
      producerController ! ProducerController.Start(producerProbe.ref)

      producerController ! ProducerController.RegisterConsumer(consumerControllerProbe.ref)

      val replyTo = createTestProbe[Long]()

      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-1"), replyTo.ref)
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 1, producerController, ack = true))
      producerController ! ProducerController.Internal.Request(1L, 10L, true, false)
      replyTo.expectMessage(1L)

      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-2"), replyTo.ref)
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 2, producerController, ack = true))
      producerController ! ProducerController.Internal.Ack(2L)
      replyTo.expectMessage(2L)

      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-3"), replyTo.ref)
      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-4"), replyTo.ref)
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 3, producerController, ack = true))
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 4, producerController, ack = true))
      // Ack(3 lost, but Ack(4) triggers reply for 3 and 4
      producerController ! ProducerController.Internal.Ack(4L)
      replyTo.expectMessage(3L)
      replyTo.expectMessage(4L)

      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-5"), replyTo.ref)
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 5, producerController, ack = true))
      // Ack(5) lost, but eventually a Request will trigger the reply
      producerController ! ProducerController.Internal.Request(5L, 15L, true, false)
      replyTo.expectMessage(5L)

      testKit.stop(producerController)
    }

    "allow restart of producer" in {
      nextId()
      val consumerControllerProbe = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()

      val producerController =
        spawn(ProducerController[TestConsumer.Job](producerId, None), s"producerController-${idCount}")
          .unsafeUpcast[ProducerController.InternalCommand]
      val producerProbe1 = createTestProbe[ProducerController.RequestNext[TestConsumer.Job]]()
      producerController ! ProducerController.Start(producerProbe1.ref)

      producerController ! ProducerController.RegisterConsumer(consumerControllerProbe.ref)

      producerProbe1.receiveMessage().sendNextTo ! TestConsumer.Job("msg-1")
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 1, producerController))
      producerController ! ProducerController.Internal.Request(1L, 10L, true, false)

      producerProbe1.receiveMessage().sendNextTo ! TestConsumer.Job("msg-2")
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 2, producerController))

      producerProbe1.receiveMessage().currentSeqNr should ===(3)

      // restart producer, new Start
      val producerProbe2 = createTestProbe[ProducerController.RequestNext[TestConsumer.Job]]()
      producerController ! ProducerController.Start(producerProbe2.ref)

      producerProbe2.receiveMessage().sendNextTo ! TestConsumer.Job("msg-3")
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 3, producerController))

      producerProbe2.receiveMessage().sendNextTo ! TestConsumer.Job("msg-4")
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 4, producerController))

      testKit.stop(producerController)
    }

  }

  "ProducerController without resends" must {
    "not resend last lost SequencedMessage when receiving Request" in {
      nextId()
      val consumerControllerProbe = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()

      val producerController =
        spawn(ProducerController[TestConsumer.Job](producerId, None), s"producerController-${idCount}")
          .unsafeUpcast[ProducerController.InternalCommand]
      val producerProbe = createTestProbe[ProducerController.RequestNext[TestConsumer.Job]]()
      producerController ! ProducerController.Start(producerProbe.ref)

      producerController ! ProducerController.RegisterConsumer(consumerControllerProbe.ref)

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-1")
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 1, producerController))

      producerController ! ProducerController.Internal.Request(1L, 10L, supportResend = false, false)

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-2")
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 2, producerController))

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-3")
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 3, producerController))
      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-4")
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 4, producerController))

      // let's say 3 and 4 are lost, and no more messages are sent from producer
      // ConsumerController will resend Request periodically
      producerController ! ProducerController.Internal.Request(2L, 10L, supportResend = false, true)

      // but 3 and 4 are not resent because supportResend = false
      consumerControllerProbe.expectNoMessage()

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-5")
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 5, producerController))

      testKit.stop(producerController)
    }

    "reply to MessageWithConfirmation for lost messages" in {
      nextId()
      val consumerControllerProbe = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()

      val producerController =
        spawn(ProducerController[TestConsumer.Job](producerId, None), s"producerController-${idCount}")
          .unsafeUpcast[ProducerController.InternalCommand]
      val producerProbe = createTestProbe[ProducerController.RequestNext[TestConsumer.Job]]()
      producerController ! ProducerController.Start(producerProbe.ref)

      producerController ! ProducerController.RegisterConsumer(consumerControllerProbe.ref)

      val replyTo = createTestProbe[Long]()

      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-1"), replyTo.ref)
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 1, producerController, ack = true))
      producerController ! ProducerController.Internal.Request(1L, 10L, supportResend = false, false)
      replyTo.expectMessage(1L)

      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-2"), replyTo.ref)
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 2, producerController, ack = true))
      producerController ! ProducerController.Internal.Ack(2L)
      replyTo.expectMessage(2L)

      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-3"), replyTo.ref)
      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-4"), replyTo.ref)
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 3, producerController, ack = true))
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 4, producerController, ack = true))
      // Ack(3 lost, but Ack(4) triggers reply for 3 and 4
      producerController ! ProducerController.Internal.Ack(4L)
      replyTo.expectMessage(3L)
      replyTo.expectMessage(4L)

      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-5"), replyTo.ref)
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 5, producerController, ack = true))
      // Ack(5) lost, but eventually a Request will trigger the reply
      producerController ! ProducerController.Internal.Request(5L, 15L, supportResend = false, false)
      replyTo.expectMessage(5L)

      testKit.stop(producerController)
    }
  }

}
