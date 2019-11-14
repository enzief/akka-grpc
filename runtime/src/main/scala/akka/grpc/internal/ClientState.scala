/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.grpc.internal

import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicReference

import akka.Done
import akka.annotation.InternalApi
import akka.grpc.GrpcClientSettings
import akka.stream.{ ActorMaterializer, Materializer }
import akka.pattern.Patterns
import io.grpc.ManagedChannel

import scala.annotation.tailrec
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.concurrent.duration._
import scala.util.{ Failure, Success }
import scala.compat.java8.FutureConverters._

/**
 * INTERNAL API
 * Used from generated code so can't be private.
 *
 * Client utilities taking care of Channel reconnection and Channel lifecycle in general.
 */
@InternalApi
final class ClientState(settings: GrpcClientSettings, channelFactory: GrpcClientSettings => Future[InternalChannel])(
    implicit mat: Materializer,
    ex: ExecutionContext) {
  def this(settings: GrpcClientSettings)(implicit mat: Materializer, ex: ExecutionContext) =
    this(settings, s => NettyClientUtils.createChannel(s))

  private val internalChannelRef = new AtomicReference[Option[Future[InternalChannel]]](Some(create()))
  internalChannelRef.get().foreach(c => recreateOnFailure(c.flatMap(_.done)))

  // usually None, it'll have a value when the underlying InternalChannel is closing or closed.
  private val closing = new AtomicReference[Option[Future[Done]]](None)
  private val closeDemand: Promise[Done] = Promise[Done]()

  mat match {
    case m: ActorMaterializer =>
      m.system.whenTerminated.foreach(_ => close())(ex)
    case _ =>
  }

  // used from generated client
  def withChannel[A](f: Future[ManagedChannel] => A): A =
    f {
      internalChannelRef.get().getOrElse(Future.failed(new ClientClosedException)).map(_.managedChannel)
    }

  def closedCS(): CompletionStage[Done] = closed().toJava
  def closeCS(): CompletionStage[Done] = close().toJava

  def closed(): Future[Done] =
    // while there's no request to close this RestartingClient, it will continue to restart.
    // Once there's demand, the `closeDemand` future will redeem flatMapping with the `closing`
    // future which is a reference to promise of the internalChannel close status.
    closeDemand.future.flatMap { _ =>
      // `closeDemand` guards the read access to `closing`
      closing.get().get
    }

  @tailrec
  def close(): Future[Done] = {
    val maybeChannel = internalChannelRef.get()
    maybeChannel match {
      case Some(channel) =>
        // invoke `close` on the channel and capture the `channel.done` returned
        val done = channel.flatMap(ChannelUtils.close(_))
        // set the `closing` to the current `channel.done`
        closing.compareAndSet(None, Some(done))
        // notify there's been close demand (see `def closed()` above)
        closeDemand.trySuccess(Done)

        if (internalChannelRef.compareAndSet(maybeChannel, None)) {
          done
        } else {
          // when internalChannelRef was not maybeChannel
          if (internalChannelRef.get != null) {
            // client has had a ClientConnectionException and been re-created, need to shutdown the new one
            close()
          } else {
            // or a competing thread already set `internalChannelRef` to None and CAS failed.
            done
          }
        }
      case _ =>
        // set the `closing` to immediate success
        val done = Future.successful(Done)
        closing.compareAndSet(None, Some(done))
        // notify there's been close demand (see `def closed()` above)
        closeDemand.trySuccess(Done)
        done
    }
  }

  private def create(): Future[InternalChannel] =
    Patterns.retry(
      () => channelFactory(settings),
      // TODO get from settings
      1000,
      400.millis,
      // TODO remove cast once we update Akka
      mat.asInstanceOf[ActorMaterializer].system.scheduler,
      mat.asInstanceOf[ActorMaterializer].system.dispatcher)

  private def recreateOnFailure(done: Future[Done]): Unit =
    done.onComplete {
      case Failure(_: ClientConnectionException) =>
        // TODO Would be better to retry with backoff
        // TODO Would be good to log
        recreate()
      case Failure(_) =>
        // TODO This makes the client unusable. Perhaps we should retry with backoff here too?
        // TODO Would be good to log
        close()
      case _ =>
      // completed successfully, nothing else to do (except perhaps log?)
    }

  private def recreate(): Unit = {
    val old = internalChannelRef.get()
    if (old.isDefined) {
      val newInternalChannel = create()
      recreateOnFailure(newInternalChannel.flatMap(_.done))
      // Only one client is alive at a time. However a close() could have happened between the get() and this set
      if (!internalChannelRef.compareAndSet(old, Some(newInternalChannel))) {
        // close the newly created client we've been shutdown
        newInternalChannel.map(ChannelUtils.close(_))
      }
    }
  }
}

/**
 * INTERNAL API
 * Used from generated code so can't be private.
 *
 * Thrown if a withChannel call is called after closing the internal channel
 */
@InternalApi
final class ClientClosedException() extends RuntimeException("withChannel called after close()")
