package eskka

import scala.concurrent.Promise

import akka.event.LoggingAdapter

import org.elasticsearch.cluster.{ ClusterService, ClusterState, ProcessedClusterStateUpdateTask }
import org.elasticsearch.common.Priority

object SubmitClusterStateUpdate {

  def apply(log: LoggingAdapter,
    clusterService: ClusterService,
    source: String,
    update: ClusterState => ClusterState) = {
    val promise = Promise[Protocol.Transition]()
    clusterService.submitStateUpdateTask(source, Priority.URGENT, new ProcessedClusterStateUpdateTask {

      override def execute(currentState: ClusterState): ClusterState = {
        log.info("SubmitClusterStateUpdate -- via source [{}]", source)
        update(currentState)
      }

      override def clusterStateProcessed(source: String, oldState: ClusterState, newState: ClusterState) {
        log.info("SubmitClusterStateUpdate -- successfully processed new state for source [{}]", source)
        promise.success(Protocol.Transition(source, newState, oldState))
      }

      override def onFailure(source: String, t: Throwable) {
        log.info("SubmitClusterStateUpdate -- failed to process new state for source [{}]", source)
        promise.failure(t)
      }

    })
    promise.future
  }

}
