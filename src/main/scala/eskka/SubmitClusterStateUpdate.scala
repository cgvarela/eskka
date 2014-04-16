package eskka

import scala.concurrent.{ Future, Promise }

import org.elasticsearch.cluster.{ ClusterService, ClusterState, ProcessedClusterStateUpdateTask }
import org.elasticsearch.common.Priority
import akka.event.LoggingAdapter

object SubmitClusterStateUpdate {

  def apply(log: LoggingAdapter,
    clusterService: ClusterService,
    source: String,
    update: ClusterState => ClusterState,
    promise: Promise[Protocol.Transition]): Future[Protocol.Transition] = {
    clusterService.submitStateUpdateTask(source, Priority.URGENT, new ProcessedClusterStateUpdateTask {

      override def execute(currentState: ClusterState): ClusterState = {
        val newState = update(currentState)
        log.info("SubmitClusterStateUpdate -- via source [{}] updated state is [{}]", source, newState)
        newState
      }

      override def clusterStateProcessed(source: String, oldState: ClusterState, newState: ClusterState) {
        log.debug("SubmitClusterStateUpdate -- successfully processed new state for source [{}]", source)
        promise.success(Protocol.Transition(source, newState, oldState))
      }

      override def onFailure(source: String, t: Throwable) {
        log.debug("SubmitClusterStateUpdate -- failed to process new state for source [{}]", source)
        promise.failure(t)
      }

    })
    promise.future
  }

}
