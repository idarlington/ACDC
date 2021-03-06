import akka.actor.{ Actor, ActorLogging, ActorRef, Props }

import scala.util.{ Failure, Success }
import scala.concurrent.ExecutionContext.Implicits.global

object ServiceActor {
  final case class GetVehicles()
  final case class GetVehicle(id: String)
  final case class VehiclesPerTile()

  def props: Props = Props[ServiceActor]()
}

class ServiceActor extends Actor with ActorLogging {

  import ServiceActor._

  def receive: Receive = {
    case GetVehicles => {
      log.info("got request")
      val sender_ : ActorRef = sender()
      Database.vehiclesList() onComplete {
        case Success(result) => { sender_ ! TiledVehicles(result) }
        case Failure(t)      => {}
      }
    }
    case GetVehicle(id) => {
      val sender_ : ActorRef = sender()
      Database.getVehicleById(id) onComplete {
        case Success(result) => {
          result match {
            case Some(location) => { sender_ ! Option(Location(location.latitude, location.longitude)) }
            case None           => { sender_ ! None }
          }
        }
        case Failure(t) => { sender_ ! None }
      }
    }
    case VehiclesPerTile => {
      val vehiclesPerTile = Database.vehiclesPerTile()
      sender() ! VehiclesCountPerTile(vehiclesPerTile)
    }
  }
}
