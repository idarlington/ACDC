import java.util.Properties

import akka.actor.{ Actor, ActorLogging }
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.pattern.pipe
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import org.apache.kafka.clients.producer.{ KafkaProducer, ProducerConfig, ProducerRecord }
import AppConfiguration._
import spray.json._

import scala.concurrent.Future
import scala.util.{ Failure, Success }

class StreamActor extends Actor with ActorLogging with JsonFormatSupport {

  import context.dispatcher

  final implicit val materializer: ActorMaterializer = ActorMaterializer(
    ActorMaterializerSettings(context.system)
  )

  val tileSystem = new TileSystem()
  val http       = Http(context.system)
  val props      = new Properties()

  val URL       = collectorURL
  val KafkaHost = s"$kafkaBroker:$kafkaPort"

  props.put("bootstrap.servers", KafkaHost)
  props.put("client.id", "Producer")
  props.put(
    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
    "org.apache.kafka.common.serialization.StringSerializer"
  )
  props.put(
    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
    "org.apache.kafka.common.serialization.StringSerializer"
  )

  val producer = new KafkaProducer[String, String](props)

  def receive = {
    case HttpResponse(StatusCodes.OK, headers, entity, _) ⇒ {
      Unmarshal(entity).to[VehicleList] onComplete {
        case Success(vehicleList) =>
          vehicleList.items.foreach { vehicle =>
            val id                   = vehicle.id
            val heading              = vehicle.heading
            val latitude             = vehicle.latitude
            val longitude            = vehicle.longitude
            val run_id               = vehicle.run_id
            val route_id             = vehicle.route_id
            val seconds_since_report = vehicle.seconds_since_report
            val predictable          = vehicle.predictable
            val tile                 = tileSystem.latLongToTileXY(latitude, longitude, levelOfDetail)

            val tiledVehicle_ =
              TiledVehicle(id, heading, latitude, longitude, run_id, route_id, seconds_since_report, predictable, tile)
            log.info(tiledVehicle_.toString)
            Future { Database.saveOrUpdate(tiledVehicle_) }
            Future {
              producer.send(new ProducerRecord[String, String]("vehicles", id, tiledVehicle_.toJson.toString()))
            }
            log.info("Saved to DB")
          }
        case Failure(exception) => { log.error(exception, "Failed") }
      }

    }
    case resp @ HttpResponse(code, _, _, _) ⇒
      log.info("Request failed, response code: ", code)
      resp.discardEntityBytes()
    case _ ⇒
      log.info("Calling API")
      http
        .singleRequest(HttpRequest(uri = URL))
        .pipeTo(self)
      log.info("Received request")
  }
}
