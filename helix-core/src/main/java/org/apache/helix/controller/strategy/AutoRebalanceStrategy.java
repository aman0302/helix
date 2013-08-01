package org.apache.helix.controller.strategy;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Map.Entry;

import org.apache.helix.HelixManager;
import org.apache.helix.ZNRecord;
import org.apache.helix.controller.rebalancer.Rebalancer;
import org.apache.helix.controller.stages.ClusterDataCache;
import org.apache.helix.controller.stages.CurrentStateOutput;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.LiveInstance;
import org.apache.helix.model.Partition;
import org.apache.helix.model.StateModelDefinition;
import org.apache.helix.model.IdealState.IdealStateModeProperty;
import org.apache.log4j.Logger;

public class AutoRebalanceStrategy implements Rebalancer {
  // These should be final, but are initialized in init rather than a constructor
  private HelixManager _manager;
  private AutoRebalanceModeAlgorithm _algorithm;

  private static Logger LOG = Logger.getLogger(AutoRebalanceStrategy.class);

  @Override
  public void init(HelixManager manager) {
    this._manager = manager;
    this._algorithm = null;
  }

  @Override
  public IdealState computeNewIdealState(String resourceName, IdealState currentIdealState,
      CurrentStateOutput currentStateOutput, ClusterDataCache clusterData) {
    List<String> partitions = new ArrayList<String>(currentIdealState.getPartitionSet());
    String stateModelName = currentIdealState.getStateModelDefRef();
    StateModelDefinition stateModelDef = clusterData.getStateModelDef(stateModelName);
    Map<String, LiveInstance> liveInstance = clusterData.getLiveInstances();
    String replicas = currentIdealState.getReplicas();

    LinkedHashMap<String, Integer> stateCountMap = new LinkedHashMap<String, Integer>();
    stateCountMap = stateCount(stateModelDef, liveInstance.size(), Integer.parseInt(replicas));
    List<String> liveNodes = new ArrayList<String>(liveInstance.keySet());
    Map<String, Map<String, String>> currentMapping = currentMapping(currentStateOutput,
        resourceName, partitions, stateCountMap);

    List<String> allNodes = new ArrayList<String>(clusterData.getInstanceConfigMap().keySet());
    int maxPartition = currentIdealState.getMaxPartitionsPerInstance();

    if (LOG.isInfoEnabled()) {
      LOG.info("currentMapping: " + currentMapping);
      LOG.info("stateCountMap: " + stateCountMap);
      LOG.info("liveNodes: " + liveNodes);
      LOG.info("allNodes: " + allNodes);
      LOG.info("maxPartition: " + maxPartition);
    }
    ReplicaPlacementScheme placementScheme = new DefaultPlacementScheme();
    placementScheme.init(_manager);
    _algorithm = new AutoRebalanceModeAlgorithm(resourceName, partitions, stateCountMap,
        maxPartition, placementScheme);
    ZNRecord newMapping = _algorithm.computePartitionAssignment(liveNodes,currentMapping,
        allNodes);

    if (LOG.isInfoEnabled()) {
      LOG.info("newMapping: " + newMapping);
    }

    IdealState newIdealState = new IdealState(resourceName);
    newIdealState.getRecord().setSimpleFields(currentIdealState.getRecord().getSimpleFields());
    newIdealState.setIdealStateMode(IdealStateModeProperty.AUTO.toString());
    newIdealState.getRecord().setListFields(newMapping.getListFields());
    return newIdealState;
  }

  public static class AutoRebalanceModeAlgorithm {

    private static Logger logger = Logger.getLogger(AutoRebalanceModeAlgorithm.class);

    private final String _resourceName;
    private final List<String> _partitions;
    private final LinkedHashMap<String, Integer> _states;
    private final int _maximumPerNode;
    private final ReplicaPlacementScheme _placementScheme;

    private Map<String, Node> _nodeMap;
    private List<Node> _liveNodesList;
    private Map<Integer, String> _stateMap;

    private Map<Replica, Node> _preferredAssignment;
    private Map<Replica, Node> _existingPreferredAssignment;
    private Map<Replica, Node> _existingNonPreferredAssignment;
    private Set<Replica> _orphaned;

    public AutoRebalanceModeAlgorithm(String resourceName, final List<String> partitions,
        final LinkedHashMap<String, Integer> states, int maximumPerNode,
        ReplicaPlacementScheme placementScheme) {
      _resourceName = resourceName;
      _partitions = partitions;
      _states = states;
      _maximumPerNode = maximumPerNode;
      if (placementScheme != null){
        _placementScheme = placementScheme;
      } else {
        _placementScheme = new DefaultPlacementScheme();
      }
    }

    public AutoRebalanceModeAlgorithm(String resourceName, final List<String> partitions,
        final LinkedHashMap<String, Integer> states) {
      this(resourceName, partitions, states, Integer.MAX_VALUE, new DefaultPlacementScheme());
    }

    public ZNRecord computePartitionAssignment(final List<String> liveNodes,
        final Map<String, Map<String, String>> currentMapping, final List<String> allNodes) {
      int numReplicas = countStateReplicas();
      ZNRecord znRecord = new ZNRecord(_resourceName);
      if (liveNodes.size() == 0) {
        return znRecord;
      }
      int distRemainder = (numReplicas * _partitions.size()) % liveNodes.size();
      int distFloor = (numReplicas * _partitions.size()) / liveNodes.size();
      _nodeMap = new HashMap<String, Node>();
      _liveNodesList = new ArrayList<Node>();

      for (String id : allNodes) {
        Node node = new Node(id);
        node.capacity = 0;
        _nodeMap.put(id, node);
      }
      for (int i = 0; i < liveNodes.size(); i++) {
        int targetSize = (_maximumPerNode > 0) ? Math.min(distFloor, _maximumPerNode) : distFloor;
        if (distRemainder > 0 && targetSize < _maximumPerNode) {
          targetSize += 1;
          distRemainder = distRemainder - 1;
        }
        Node node = _nodeMap.get(liveNodes.get(i));
        node.isAlive = true;
        node.capacity = targetSize;
        _liveNodesList.add(node);
      }

      // compute states for all replica ids
      _stateMap = generateStateMap();

      // compute the preferred mapping if all nodes were up
      _preferredAssignment = computePreferredPlacement(allNodes);

      // logger.info("preferred mapping:"+ preferredAssignment);
      // from current mapping derive the ones in preferred location
      // this will update the nodes with their current fill status
      _existingPreferredAssignment = computeExistingPreferredPlacement(currentMapping);

      // from current mapping derive the ones not in preferred location
      _existingNonPreferredAssignment = computeExistingNonPreferredPlacement(currentMapping);

      // compute orphaned replicas that are not assigned to any node
      _orphaned = computeOrphaned();
      if (logger.isInfoEnabled()) {
        logger.info("orphan = " + _orphaned);
      }

      moveNonPreferredReplicasToPreferred();

      assignOrphans();

      moveExcessReplicas();

      prepareResult(znRecord);
      return znRecord;
    }

    /**
     * Move replicas assigned to non-preferred nodes if their current node is at capacity
     * and its preferred node is under capacity.
     */
    private void moveNonPreferredReplicasToPreferred() {
      // iterate through non preferred and see if we can move them to
      // preferredlocation if the donor has more than it should and stealer has
      // enough capacity
      Iterator<Entry<Replica, Node>> iterator = _existingNonPreferredAssignment.entrySet()
          .iterator();
      while (iterator.hasNext()) {
        Entry<Replica, Node> entry = iterator.next();
        Replica replica = entry.getKey();
        Node donor = entry.getValue();
        Node receiver = _preferredAssignment.get(replica);
        if (donor.capacity < donor.currentlyAssigned
            && receiver.capacity > receiver.currentlyAssigned && receiver.canAdd(replica)) {
          donor.currentlyAssigned = donor.currentlyAssigned - 1;
          receiver.currentlyAssigned = receiver.currentlyAssigned + 1;
          donor.nonPreferred.remove(replica);
          receiver.preferred.add(replica);
          iterator.remove();
        }
      }
    }

    /**
     * Slot in orphaned partitions randomly so as to maintain even load on live nodes.
     */
    private void assignOrphans() {
      // now iterate over nodes and remaining orphaned partitions and assign
      // partitions randomly
      // Better to iterate over orphaned partitions first
      Iterator<Replica> it = _orphaned.iterator();
      while (it.hasNext()) {
        Replica replica = it.next();
        int startIndex = (replica.hashCode() & 0x7FFFFFFF) % _liveNodesList.size();
        for (int index = startIndex; index < startIndex + _liveNodesList.size(); index++) {
          Node receiver = _liveNodesList.get(index % _liveNodesList.size());
          if (receiver.capacity > receiver.currentlyAssigned && receiver.canAdd(replica)) {
            receiver.currentlyAssigned = receiver.currentlyAssigned + 1;
            receiver.nonPreferred.add(replica);
            it.remove();
            break;
          }
        }
      }
      if (_orphaned.size() > 0 && logger.isInfoEnabled()) {
        logger.info("could not assign nodes to partitions: " + _orphaned);
      }
    }

    /**
     * Move replicas from too-full nodes to nodes that can accept the replicas
     */
    private void moveExcessReplicas() {
      // iterate over nodes and move extra load
      Iterator<Replica> it;
      for (Node donor : _liveNodesList) {
        if (donor.capacity < donor.currentlyAssigned) {
          Collections.sort(donor.nonPreferred);
          it = donor.nonPreferred.iterator();
          while (it.hasNext()) {
            Replica replica = it.next();
            int startIndex = (replica.hashCode() & 0x7FFFFFFF) % _liveNodesList.size();
            for (int index = startIndex; index < startIndex + _liveNodesList.size(); index++) {
              Node receiver = _liveNodesList.get(index % _liveNodesList.size());
              if (receiver.canAdd(replica)) {
                receiver.currentlyAssigned = receiver.currentlyAssigned + 1;
                receiver.nonPreferred.add(replica);
                donor.currentlyAssigned = donor.currentlyAssigned - 1;
                it.remove();
                break;
              }
            }
            if (donor.capacity >= donor.currentlyAssigned) {
              break;
            }
          }
          if (donor.capacity < donor.currentlyAssigned) {
            logger.warn("Could not take partitions out of node:" + donor.id);
          }
        }
      }
    }

    /**
     * Update a ZNRecord with the results of the rebalancing.
     * @param znRecord
     */
    private void prepareResult(ZNRecord znRecord)
    {
      // The map fields are keyed on partition name to a pair of node and state, i.e. it
      // indicates that the partition with given state is served by that node
      //
      // The list fields are also keyed on partition and list all the nodes serving that partition.
      // This is useful to verify that there is no node serving multiple replicas of the same
      // partition.
      for (String partition : _partitions) {
        znRecord.setMapField(partition, new TreeMap<String, String>());
        znRecord.setListField(partition, new ArrayList<String>());
      }
      for (Node node : _liveNodesList) {
        for (Replica replica : node.preferred) {
          znRecord.getMapField(replica.partition).put(node.id, _stateMap.get(replica.replicaId));
        }
        for (Replica replica : node.nonPreferred) {
          znRecord.getMapField(replica.partition).put(node.id, _stateMap.get(replica.replicaId));
        }
      }

      int count = countStateReplicas();
      for (int replicaId = 0; replicaId < count; replicaId++) {
        for (Node node : _liveNodesList) {
          for (Replica replica : node.preferred) {
            if (replicaId == replica.replicaId) {
              znRecord.getListField(replica.partition).add(node.id);
            }
          }
          for (Replica replica : node.nonPreferred) {
            if (replicaId == replica.replicaId) {
              znRecord.getListField(replica.partition).add(node.id);
            }
          }
        }
      }
    }

    /**
     * Compute the subset of the current mapping where replicas are not mapped according to their
     * preferred assignment.
     * @param currentMapping Current mapping of replicas to nodes
     * @return The current assignments that do not conform to the preferred assignment
     */
    private Map<Replica, Node> computeExistingNonPreferredPlacement(
        Map<String, Map<String, String>> currentMapping) {
      Map<Replica, Node> existingNonPreferredAssignment = new TreeMap<Replica, Node>();
      int count = countStateReplicas();
      for (String partition : currentMapping.keySet()) {
        Map<String, String> nodeStateMap = currentMapping.get(partition);
        for (String nodeId : nodeStateMap.keySet()) {
          Node node = _nodeMap.get(nodeId);
          boolean skip = false;
          for(Replica replica: node.preferred){
            if(replica.partition.equals(partition)){
              skip = true;
              break;
            }
          }
          if (skip) {
            continue;
          }
          // check if its in one of the preferred position
          for (int replicaId = 0; replicaId < count; replicaId++) {
            Replica replica = new Replica(partition, replicaId);
            if (_preferredAssignment.get(replica).id != node.id
                && !_existingPreferredAssignment.containsKey(replica)
                && !existingNonPreferredAssignment.containsKey(replica)) {
              existingNonPreferredAssignment.put(replica, node);
              node.nonPreferred.add(replica);
              break;
            }
          }
        }
      }
      return existingNonPreferredAssignment;
    }

    /**
     * Get a set of replicas not currently assigned to any node
     * @return Unassigned replicas
     */
    private Set<Replica> computeOrphaned() {
      Set<Replica> orphanedPartitions = new TreeSet<Replica>(_preferredAssignment.keySet());
      for (Replica r : _existingPreferredAssignment.keySet()) {
        if (orphanedPartitions.contains(r)) {
          orphanedPartitions.remove(r);
        }
      }
      for (Replica r : _existingNonPreferredAssignment.keySet()) {
        if (orphanedPartitions.contains(r)) {
          orphanedPartitions.remove(r);
        }
      }

      return orphanedPartitions;
    }

    /**
     * Determine the replicas already assigned to their preferred nodes
     * @param currentMapping Current assignment of replicas to nodes
     * @return Assignments that conform to the preferred placement
     */
    private Map<Replica, Node> computeExistingPreferredPlacement(
        final Map<String, Map<String, String>> currentMapping) {
      Map<Replica, Node> existingPreferredAssignment = new TreeMap<Replica, Node>();
      int count = countStateReplicas();
      for (String partition : currentMapping.keySet()) {
        Map<String, String> nodeStateMap = currentMapping.get(partition);
        for (String nodeId : nodeStateMap.keySet()) {
          Node node = _nodeMap.get(nodeId);
          node.currentlyAssigned = node.currentlyAssigned + 1;
          // check if its in one of the preferred position
          for (int replicaId = 0; replicaId < count; replicaId++) {
            Replica replica = new Replica(partition, replicaId);
            if (_preferredAssignment.containsKey(replica)
                && !existingPreferredAssignment.containsKey(replica)
                && _preferredAssignment.get(replica).id == node.id) {
              existingPreferredAssignment.put(replica, node);
              node.preferred.add(replica);
              break;
            }
          }
        }
      }

      return existingPreferredAssignment;
    }

    /**
     * Given a predefined set of all possible nodes, compute an assignment of replicas to
     * nodes that evenly assigns all replicas to nodes.
     * @param allNodes Identifiers to all nodes, live and non-live
     * @return Preferred assignment of replicas
     */
    private Map<Replica, Node> computePreferredPlacement(final List<String> allNodes) {
      Map<Replica, Node> preferredMapping;
      preferredMapping = new HashMap<Replica, Node>();
      int partitionId = 0;
      int numReplicas = countStateReplicas();
      int count = countStateReplicas();
      for (String partition : _partitions) {
        for (int replicaId = 0; replicaId < count; replicaId++) {
          Replica replica = new Replica(partition, replicaId);
          String nodeName = _placementScheme.getLocation(partitionId, replicaId,
              _partitions.size(), numReplicas, allNodes);
          preferredMapping.put(replica, _nodeMap.get(nodeName));
        }
        partitionId = partitionId + 1;
      }
      return preferredMapping;
    }

    /**
     * Counts the total number of replicas given a state-count mapping
     *
     * @param states
     * @return
     */
    private int countStateReplicas() {
      int total = 0;
      for (Integer count : _states.values()) {
        total += count;
      }
      return total;
    }

    /**
     * Compute a map of replica ids to state names
     * @return Map: replica id -> state name
     */
    private Map<Integer, String> generateStateMap() {
      int replicaId = 0;
      Map<Integer, String> stateMap = new HashMap<Integer, String>();
      for (String state : _states.keySet()) {
        Integer count = _states.get(state);
        for (int i = 0; i < count; i++) {
          stateMap.put(replicaId, state);
          replicaId++;
        }
      }
      return stateMap;
    }

    /**
     * A Node is an entity that can serve replicas. It has a capacity and knowledge
     * of replicas assigned to it, so it can decide if it can receive additional replicas.
     */
    class Node {

      public int currentlyAssigned;
      public int capacity;
      private String id;
      boolean isAlive;
      private List<Replica> preferred;
      private List<Replica> nonPreferred;

      public Node(String id) {
        preferred = new ArrayList<Replica>();
        nonPreferred = new ArrayList<Replica>();
        currentlyAssigned = 0;
        isAlive = false;
        this.id = id;
      }

      /**
       * Check if this replica can be legally added to this node
       * @param replica The replica to test
       * @return true if the assignment can be made, false otherwise
       */
      public boolean canAdd(Replica replica) {
        if (!isAlive) {
          return false;
        }
        if (currentlyAssigned >= capacity) {
          return false;
        }
        for (Replica r : preferred) {
          if (r.partition.equals(replica.partition)) {
            return false;
          }
        }
        for (Replica r : nonPreferred) {
          if (r.partition.equals(replica.partition)) {
            return false;
          }
        }
        return true;
      }

      @Override
      public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("##########\nname=").append(id).append("\npreferred:").append(preferred.size())
            .append("\nnonpreferred:").append(nonPreferred.size());
        return sb.toString();
      }
    }

    /**
     * A Replica is a combination of a partition of the resource, the state the replica is in
     * and an identifier signifying a specific replica of a given partition and state.
     */
    class Replica implements Comparable<Replica> {

      private String partition;
      private int replicaId; // this is a partition-relative id
      private String format;

      public Replica(String partition, int replicaId) {
        this.partition = partition;
        this.replicaId = replicaId;
        this.format = partition + "|" + replicaId;
      }

      @Override
      public String toString() {
        return format;
      }

      @Override
      public boolean equals(Object that) {
        if (that instanceof Replica) {
          return this.format.equals(((Replica) that).format);
        }
        return false;
      }

      @Override
      public int hashCode() {
        return this.format.hashCode();
      }

      @Override
      public int compareTo(Replica that) {
        if (that instanceof Replica) {
          return this.format.compareTo(((Replica) that).format);
        }
        return -1;
      }
    }
  }

  /**
   *
   * @return state count map: state->count
   */
  LinkedHashMap<String, Integer> stateCount(StateModelDefinition stateModelDef, int liveNodesNb,
      int totalReplicas) {
    LinkedHashMap<String, Integer> stateCountMap = new LinkedHashMap<String, Integer>();
    List<String> statesPriorityList = stateModelDef.getStatesPriorityList();

    int replicas = totalReplicas;
    for (String state : statesPriorityList) {
      String num = stateModelDef.getNumInstancesPerState(state);
      if ("N".equals(num)) {
        stateCountMap.put(state, liveNodesNb);
      } else if ("R".equals(num)) {
        // wait until we get the counts for all other states
        continue;
      } else {
        int stateCount = -1;
        try {
          stateCount = Integer.parseInt(num);
        } catch (Exception e) {
          // LOG.error("Invalid count for state: " + state + ", count: " + num +
          // ", use -1 instead");
        }

        if (stateCount > 0) {
          stateCountMap.put(state, stateCount);
          replicas -= stateCount;
        }
      }
    }

    // get state count for R
    for (String state : statesPriorityList) {
      String num = stateModelDef.getNumInstancesPerState(state);
      if ("R".equals(num)) {
        stateCountMap.put(state, replicas);
        // should have at most one state using R
        break;
      }
    }
    return stateCountMap;
  }

  Map<String, Map<String, String>> currentMapping(CurrentStateOutput currentStateOutput,
      String resourceName, List<String> partitions, Map<String, Integer> stateCountMap) {

    Map<String, Map<String, String>> map = new HashMap<String, Map<String, String>>();

    for (String partition : partitions) {
      Map<String, String> curStateMap = currentStateOutput.getCurrentStateMap(resourceName,
          new Partition(partition));
      map.put(partition, new HashMap<String, String>());
      for (String node : curStateMap.keySet()) {
        String state = curStateMap.get(node);
        if (stateCountMap.containsKey(state)) {
          map.get(partition).put(node, state);
        }
      }

      Map<String, String> pendingStateMap = currentStateOutput.getPendingStateMap(resourceName,
          new Partition(partition));
      for (String node : pendingStateMap.keySet()) {
        String state = pendingStateMap.get(node);
        if (stateCountMap.containsKey(state)) {
          map.get(partition).put(node, state);
        }
      }
    }
    return map;
  }

  /**
   * Interface for providing a custom approach to computing a replica's affinity to a node.
   */
  public interface ReplicaPlacementScheme {
    /**
     * Initialize global state
     * @param manager The instance to which this placement is associated
     */
    public void init(final HelixManager manager);

    /**
     * Given properties of this replica, determine the node it would prefer to be served by
     * @param partitionId The current partition
     * @param replicaId The current replica with respect to the current partition
     * @param numPartitions The total number of partitions
     * @param numReplicas The total number of replicas per partition
     * @param nodeNames A list of identifiers of all nodes, live and non-live
     * @return The name of the node that would prefer to serve this replica
     */
    public String getLocation(int partitionId, int replicaId, int numPartitions, int numReplicas,
        final List<String> nodeNames);
  }

  /**
   * Compute preferred placements based on a default strategy that assigns replicas to nodes as
   * evenly as possible while avoiding placing two replicas of the same partition on any node.
   */
  public static class DefaultPlacementScheme implements ReplicaPlacementScheme {
    @Override
    public void init(final HelixManager manager) {
      // do nothing since this is independent of the manager
    }

    @Override
    public String getLocation(int partitionId, int replicaId, int numPartitions, int numReplicas,
        final List<String> nodeNames) {
      int index;
      if (nodeNames.size() > numPartitions) {
        // assign replicas in partition order in case there are more nodes than partitions
        index = (partitionId + replicaId * numPartitions) % nodeNames.size();
      } else if (nodeNames.size() == numPartitions) {
        // need a replica offset in case the sizes of these sets are the same
        index = ((partitionId + replicaId * numPartitions) % nodeNames.size()
            + replicaId) % nodeNames.size();
      } else {
        // in all other cases, assigning a replica at a time for each partition is reasonable
        index = (partitionId + replicaId) % nodeNames.size();
      }
      return nodeNames.get(index);
    }
  }
}