package com.nlpagentsystem

import akka.actor.{ Actor, ActorLogging, ActorRef, PoisonPill, Props }
import com.nlpagentsystem.DebateSupervisorActor.{ NewArgument => SupervisorNewArgument, OutOfArguments => SupervisorOutOfArguments }
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.model.Filters.equal

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration.Duration

final case class Argument(componentName: String, description: String, score: Double)

object DebaterActor {
  def props(
    name: String,
    collection: MongoCollection[Review],
    productId: String,
    skip: Int,
    limit: Int
  ): Props =
    Props(new DebaterActor(name, collection, productId, skip, limit))

  final case class OutOfArguments(from: String)
  final case class NewArgument(from: String, argument: Argument)
  final case class StartDebate(otherDebater: ActorRef)

}

class DebaterActor(
  name: String,
  collection: MongoCollection[Review],
  productId: String,
  skip: Int,
  limit: Int
)
    extends Actor with ActorLogging {

  import DebaterActor._
  import context._

  var arguments: ListBuffer[Argument] = ListBuffer.empty

  override def preStart() {
    val future = collection.find(equal("product_id", productId)).skip(skip).limit(limit).toFuture()
    val fetchedReviews = Await.result(future, Duration(5, "sec"))
    arguments = fetchedReviews.flatMap(
      review => review.features
        .filter(feature => feature.polarity_score.abs >= 0.15)
        .map(feature => Argument(feature.name, feature.description, feature.polarity_score))
    ).to[ListBuffer]
  }

  def exhausted: Receive = {
    case OutOfArguments(from) =>
      self ! PoisonPill
    case default => log.debug(default.toString)
  }
  override def receive: Receive = {
    case StartDebate(otherDebater) =>
      if (arguments.isEmpty) {
        become(exhausted)
        parent ! SupervisorOutOfArguments(name)
        otherDebater ! OutOfArguments(name)
      } else {
        val newArgument = arguments.head
        arguments = arguments.tail
        otherDebater ! NewArgument(name, newArgument)
        parent ! SupervisorNewArgument(name, newArgument)
      }
    case NewArgument(from, argument) =>
      if (arguments.isEmpty) {
        become(exhausted)
        parent ! SupervisorOutOfArguments(name)
        sender() ! OutOfArguments(name)
      } else {
        val similarArgument = arguments.find(a => a.componentName == argument.componentName).orNull
        val newArgument = if (similarArgument != null) {
          arguments.-=(similarArgument)
          similarArgument
        } else arguments.remove(0)
        sender() ! NewArgument(name, newArgument)
        parent ! SupervisorNewArgument(name, newArgument)
      }
    case OutOfArguments(from) =>
      arguments.foreach(
        arg => {
          sender() ! NewArgument(name, arg)
          context.parent ! SupervisorNewArgument(name, arg)
        }
      )
      parent ! SupervisorOutOfArguments(name)
      sender() ! OutOfArguments(name)
      self ! PoisonPill
  }
}