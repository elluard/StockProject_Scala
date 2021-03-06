package main.collectors.HttpOnly

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorAttributes
import main.common.HttpCommon.{fromHttpResponseToReponseBody, fromRequestTickerToHttpRequest, fromTickerRowToRequestTicker, priceTupleFlow, requestSuccessFilter}
import main.database.PriceHistory
import spray.json.DefaultJsonProtocol

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.{Failure, Success}

class ConnectionLevelClient extends DefaultJsonProtocol {
  import main.common._

  implicit val system : ActorSystem = ActorSystem("HistoryCollector_ConnectionLevel")

  def getHistory = {
    PriceHistory
      .selectTicker // DB로부터 데이터 읽어들임
      .flatMapConcat( fromTickerRowToRequestTicker ) //데이터 가공
      .map ( fromRequestTickerToHttpRequest )
      .throttle(4, 1 minute)  //여기서 1분에 4건 다음 step 으로 전진하게끔 한다.
      .via(Http().connectionTo("api.polygon.io").https())  //여기서 HTTP Request 전송, HttpReseponse 형태로 바뀐다.
      .filter( requestSuccessFilter )
      .flatMapConcat( fromHttpResponseToReponseBody )
      .via( priceTupleFlow )
      .withAttributes(ActorAttributes.supervisionStrategy(SuperVisor.decider)) //실패 시 복구 전략 및 exception handler
      .watchTermination() { (_, done) => //모든 작업이 끝난 후 혹은 처리되지 않은 exception 발생 시 호출
        done.onComplete {
          case Success(_) => println("source completed successfully")
          case Failure(e) => println(s"source completed with failure : $e")
        }
      }
      .to(PriceHistory.insertIntoPriceHistory)
      .run()
  }
}
