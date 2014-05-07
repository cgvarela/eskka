package eskka

import java.util.concurrent.TimeUnit

import scala.Some
import scala.collection.JavaConversions._
import scala.concurrent.Promise
import scala.concurrent.duration.Duration
import scala.util.{ Failure, Success }

import akka.actor._
import akka.cluster.Cluster
import akka.pattern.pipe

import org.elasticsearch.cluster.{ ClusterService, ClusterState }
import org.elasticsearch.cluster.block.ClusterBlocks
import org.elasticsearch.cluster.metadata.MetaData
import org.elasticsearch.cluster.node.{ DiscoveryNode, DiscoveryNodes }
import org.elasticsearch.cluster.routing.RoutingTable
import org.elasticsearch.discovery.Discovery
import org.elasticsearch.gateway.GatewayService

object Follower {

  def props(localNode: DiscoveryNode, votingMembers: VotingMembers, clusterService: ClusterService, masterProxyProps: Props) =
    Props(classOf[Follower], localNode, votingMembers, clusterService, masterProxyProps)

  private val QuorumCheckInterval = Duration(250, TimeUnit.MILLISECONDS)
  private val RetryClearStateDelay = Duration(1, TimeUnit.SECONDS)

  private case object QuorumCheck

  private case object ClearState

}

class Follower(localNode: DiscoveryNode, votingMembers: VotingMembers, clusterService: ClusterService, masterProxyProps: Props) extends Actor with ActorLogging {

  import Follower._

  import context.dispatcher

  val cluster = Cluster(context.system)

  val masterProxy = context.actorOf(masterProxyProps)

  val firstSubmit = Promise[Protocol.ClusterStateTransition]()

  val quorumCheckTask = context.system.scheduler.schedule(QuorumCheckInterval, QuorumCheckInterval, self, QuorumCheck)

  var quorumCheckLastResult = true
  var pendingPublishRequest = false

  override def postStop() {
    quorumCheckTask.cancel()
  }

  override def receive = {

    case Protocol.CheckInit =>
      firstSubmit.future pipeTo sender()

    case Protocol.WhoYou =>
      sender() ! Protocol.IAm(self, localNode)

    case Protocol.LocalMasterPublishNotification(transition) =>
      log.debug("received local master publish notification")
      pendingPublishRequest = false
      firstSubmit.tryComplete(transition)

    case Protocol.FollowerPublish(esVersion, serializedClusterState) =>
      if (quorumCheckLastResult) {
        val updatedState = ClusterStateSerialization.fromBytes(esVersion, serializedClusterState, localNode)
        require(updatedState.nodes.masterNodeId != localNode.id, "Master's local follower should not receive Publish messages")

        val publishSender = sender()
        log.info("submitting publish of cluster state version {}...", updatedState.version)
        SubmitClusterStateUpdate(clusterService, "follower{master-publish}", updateClusterState(updatedState)) onComplete {
          res =>
            res match {
              case Success(transition) =>
                log.debug("successfully submitted cluster state version {}", updatedState.version)
                publishSender ! Protocol.PublishAck(localNode, None)
              case Failure(error) =>
                log.error(error, "failed to submit cluster state version {}", updatedState.version)
                publishSender ! Protocol.PublishAck(localNode, Some(error))
            }
            firstSubmit.tryComplete(res)
        }
      } else {
        log.warning("discarding publish of cluster state quorum unavailable")
        sender() ! Protocol.PublishAck(localNode, Some(new Protocol.QuorumUnavailable))
      }

      pendingPublishRequest = false

    case QuorumCheck =>
      val quorumCheckCurrentResult = votingMembers.quorumAvailable(cluster.state)

      if (quorumCheckCurrentResult != quorumCheckLastResult) {
        pendingPublishRequest = quorumCheckCurrentResult
        if (!quorumCheckCurrentResult) {
          self ! ClearState
        }
      }

      if (pendingPublishRequest) {
        log.debug("quorum available, requesting publish from master")
        masterProxy ! Protocol.PleasePublishDiscoveryState(cluster.selfAddress)
      }

      quorumCheckLastResult = quorumCheckCurrentResult

    case ClearState =>
      if (!quorumCheckLastResult) {
        SubmitClusterStateUpdate(clusterService, "follower{quorum-loss}", clearClusterState) onComplete {
          case Success(_) =>
            log.debug("quorum loss -- cleared cluster state")
          case Failure(e) =>
            log.error(e, "quorum loss -- failed to clear cluster state, will retry")
            context.system.scheduler.scheduleOnce(RetryClearStateDelay, self, ClearState)
        }
      }

  }

  def updateClusterState(updatedState: ClusterState)(currentState: ClusterState) = {
    val builder = ClusterState.builder(updatedState)

    // if the routing table did not change, use the original one
    if (updatedState.routingTable.version == currentState.routingTable.version) {
      builder.routingTable(currentState.routingTable)
    }

    // same for metadata
    if (updatedState.metaData.version == currentState.metaData.version) {
      builder.metaData(currentState.metaData)
    } else {
      val metaDataBuilder = MetaData.builder(updatedState.metaData).removeAllIndices()
      for (indexMetaData <- updatedState.metaData) {
        val currentIndexMetaData = currentState.metaData.index(indexMetaData.index)
        metaDataBuilder.put(
          if (currentIndexMetaData == null || currentIndexMetaData.version != indexMetaData.version)
            indexMetaData
          else
            currentIndexMetaData,
          false)
      }
      builder.metaData(metaDataBuilder)
    }

    builder.build
  }

  def clearClusterState(currentState: ClusterState) =
    ClusterState.builder(currentState)
      .blocks(
        ClusterBlocks.builder.blocks(currentState.blocks)
          .addGlobalBlock(Discovery.NO_MASTER_BLOCK)
          .addGlobalBlock(GatewayService.STATE_NOT_RECOVERED_BLOCK)
          .build)
      .nodes(DiscoveryNodes.builder.put(localNode).localNodeId(localNode.id))
      .routingTable(RoutingTable.builder)
      .metaData(MetaData.builder)
      .build

}
