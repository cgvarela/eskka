# eskka - discovery plugin for elasticsearch

eskka aims at providing a robust [Zen](http://www.elasticsearch.org/guide/en/elasticsearch/reference/current/modules-discovery-zen.html) replacement. It most closely resembles Zen unicast discovery in that no external coordinator is required but you need to configure seed nodes. 

It builds on top of Akka Cluster which uses a [gossip protocol](http://en.wikipedia.org/wiki/Gossip_protocol). It will help to read the [spec](http://doc.akka.io/docs/akka/snapshot/common/cluster.html#cluster).

We use a quorum of configured seed nodes for resolving partitions when a failure is detected (configurable thresholds) by 'downing' the affected node. A node that is downed in this manner will not be able to rejoin the cluster until restarted.

If any node (including the master) loses reachability with a quorum of seed nodes, it clears its internal elasticsearch cluster state. In case it becomes reachable again before it is downed, it will request a publish from current master to get the latest cluster state.

There is no master election per-se, it is [deterministically the 'oldest'](http://doc.akka.io/docs/akka/snapshot/contrib/cluster-singleton.html) master-eligible member of the cluster.

## installation

Clone the repository. Use the [sbt](http://www.scala-sbt.org/#install) target `pack`, which will generate a plugin zip under `target/`. 

Then use the plugin script with the arguments `--url file:///path/to/eskka-0.1.0-SNAPSHOT.zip --install eskka`.

**TODO** upload the plugin package somewhere

## configuration

eskka runs on a different port to both elasticsearch's http and internal transport.

`discovery.type` - you will need to set this to `eskka.EskkaDiscoveryModule`

`discovery.eskka.seed_nodes` - array of `[host:port,..]`. The port is optional and will default to 9400. The seed nodes will default to `[<publish_host>:9400]` if unspecified. It is probably the most important piece of configuration as a quorum `(n/2 + 1)` of seed nodes is used in partition resolution - so ideally you would have 3 or more. A note from the [Akka Cluster docs](http://doc.akka.io/docs/akka/snapshot/java/cluster-usage.html#Joining_to_Seed_Nodes):

> The seed nodes can be started in any order and it is not necessary to have all seed nodes running, but the node configured as the first element in the seed-nodes configuration list must be started when initially starting a cluster, otherwise the other seed-nodes will not become initialized and no other node can join the cluster. The reason for the special first seed node is to avoid forming separated islands when starting from an empty cluster. It is quickest to start all configured seed nodes at the same time (order doesn't matter), otherwise it can take up to the configured seed-node-timeout until the nodes can join.


`discovery.eskka.host` - the [elasticsearch hostname magic](http://www.elasticsearch.org/guide/en/elasticsearch/reference/current/modules-network.html#modules-network) is supported here. It defaults to `_non_loopback_`.

`discovery.eskka.port` - port ranges are not supported, this must be an int. Defaults to 0 in case this is a client node, and 9400 otherwise.

`discovery.eskka.partition.eval-delay` - time value. The delay before which we will start evaluating a partitioned node by arranging for a distributed ping by the seed nodes. It defaults to 5 seconds.

`discovery.eskka.partition.ping-timeout` - time value. If a quorum of seed nodes affirmatively times out in contacting the partitioned node, it will be downed. It defaults to 2 seconds.

Any Akka-level configuration is supported as system properties, e.g. `-Dakka.cluster.failure-detector.acceptable-heartbeat-pause=3s`.

*NOTE* Akka remoting logging is at this time fairly verbose, so you may want to consider setting the log-level for everything under the `akka` namespace to be `WARN`.