/*
 * Copyright 2019 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 *     https://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.commissioner.tasks;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.yugabyte.yw.commissioner.BaseTaskDependencies;
import com.yugabyte.yw.commissioner.Common.CloudType;
import com.yugabyte.yw.commissioner.ITask.Abortable;
import com.yugabyte.yw.commissioner.ITask.Retryable;
import com.yugabyte.yw.commissioner.UserTaskDetails.SubTaskGroupType;
import com.yugabyte.yw.commissioner.tasks.subtasks.KubernetesCommandExecutor;
import com.yugabyte.yw.common.KubernetesUtil;
import com.yugabyte.yw.common.PlacementInfoUtil;
import com.yugabyte.yw.common.RedactingService;
import com.yugabyte.yw.common.operator.KubernetesOperatorStatusUpdater;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.Cluster;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.NodeDetails;
import com.yugabyte.yw.models.helpers.PlacementInfo;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Abortable
@Retryable
public class CreateKubernetesUniverse extends KubernetesTaskBase {

  // In-memory password store for ysqlPassword and ycqlPassword.
  private static final Cache<UUID, AuthPasswords> passwordStore =
      CacheBuilder.newBuilder().expireAfterAccess(2, TimeUnit.DAYS).maximumSize(1000).build();

  @AllArgsConstructor
  private static class AuthPasswords {
    public String ycqlPassword;
    public String ysqlPassword;
  }

  @Inject
  protected CreateKubernetesUniverse(BaseTaskDependencies baseTaskDependencies) {
    super(baseTaskDependencies);
  }

  @Override
  public void run() {
    Throwable th = null;
    try {
      if (isFirstTry()) {
        // Verify the task params.
        verifyParams(UniverseOpType.CREATE);
      }
      Cluster primaryCluster = taskParams().getPrimaryCluster();
      if (isFirstTry()) {
        if (primaryCluster.userIntent.enableYCQL && primaryCluster.userIntent.enableYCQLAuth) {
          ycqlPassword = primaryCluster.userIntent.ycqlPassword;
          primaryCluster.userIntent.ycqlPassword = RedactingService.redactString(ycqlPassword);
        }
        if (primaryCluster.userIntent.enableYSQL && primaryCluster.userIntent.enableYSQLAuth) {
          ysqlPassword = primaryCluster.userIntent.ysqlPassword;
          primaryCluster.userIntent.ysqlPassword = RedactingService.redactString(ysqlPassword);
        }
        passwordStore.put(
            taskParams().getUniverseUUID(), new AuthPasswords(ycqlPassword, ysqlPassword));
      } else {
        log.debug("Reading password for {}", taskParams().getUniverseUUID());
        // Read from the in-memory store on retry.
        AuthPasswords passwords = passwordStore.getIfPresent(taskParams().getUniverseUUID());
        if (passwords == null) {
          throw new RuntimeException(
              "Auth passwords are not found. Platform might have restarted"
                  + " or task might have expired");
        }
        ycqlPassword = passwords.ycqlPassword;
        ysqlPassword = passwords.ysqlPassword;
      }

      Universe universe = lockUniverseForUpdate(taskParams().expectedUniverseVersion);
      KubernetesOperatorStatusUpdater.createYBUniverseEventStatus(
          universe, getName(), getUserTaskUUID());

      // Set all the in-memory node names first.
      setNodeNames(universe);

      PlacementInfo pi = primaryCluster.placementInfo;

      selectNumMastersAZ(pi);

      // Update the user intent.
      writeUserIntentToUniverse();

      Provider provider =
          Provider.getOrBadRequest(
              UUID.fromString(taskParams().getPrimaryCluster().userIntent.provider));

      KubernetesPlacement placement = new KubernetesPlacement(pi, /*isReadOnlyCluster*/ false);

      boolean newNamingStyle = taskParams().useNewHelmNamingStyle;

      String masterAddresses =
          KubernetesUtil.computeMasterAddresses(
              pi,
              placement.masters,
              taskParams().nodePrefix,
              universe.getName(),
              provider,
              taskParams().communicationPorts.masterRpcPort,
              newNamingStyle);

      boolean isMultiAz = PlacementInfoUtil.isMultiAZ(provider);

      createPodsTask(
          universe.getName(),
          placement,
          masterAddresses,
          false, /*isReadOnlyCluster*/
          taskParams().isEnableYbc());

      createSingleKubernetesExecutorTask(
          universe.getName(),
          KubernetesCommandExecutor.CommandType.POD_INFO,
          pi,
          false); /*isReadOnlyCluster*/

      Set<NodeDetails> tserversAdded =
          getPodsToAdd(placement.tservers, null, ServerType.TSERVER, isMultiAz, false);

      // Check if we need to create read cluster pods also.
      List<Cluster> readClusters = taskParams().getReadOnlyClusters();
      Set<NodeDetails> readOnlyTserversAdded = new HashSet<>();
      if (readClusters.size() > 1) {
        String msg = "Expected at most 1 read cluster but found " + readClusters.size();
        log.error(msg);
        throw new RuntimeException(msg);
      } else if (readClusters.size() == 1) {
        Cluster readCluster = readClusters.get(0);
        PlacementInfo readClusterPI = readCluster.placementInfo;
        Provider readClusterProvider =
            Provider.getOrBadRequest(UUID.fromString(readCluster.userIntent.provider));
        CloudType readClusterProviderType = readCluster.userIntent.providerType;
        if (readClusterProviderType != CloudType.kubernetes) {
          String msg =
              String.format(
                  "Read replica clusters provider type is expected to be kubernetes but found %s",
                  readClusterProviderType.name());
          log.error(msg);
          throw new IllegalArgumentException(msg);
        }

        KubernetesPlacement readClusterPlacement =
            new KubernetesPlacement(readClusterPI, /*isReadOnlyCluster*/ true);
        // Skip choosing masters from read cluster.
        boolean isReadClusterMultiAz = PlacementInfoUtil.isMultiAZ(readClusterProvider);
        createPodsTask(
            universe.getName(),
            readClusterPlacement,
            masterAddresses,
            true,
            taskParams().isEnableYbc());
        createSingleKubernetesExecutorTask(
            universe.getName(),
            KubernetesCommandExecutor.CommandType.POD_INFO,
            readClusterPI,
            true);
        readOnlyTserversAdded =
            getPodsToAdd(
                readClusterPlacement.tservers,
                null,
                ServerType.TSERVER,
                isReadClusterMultiAz,
                true);
      }

      // Wait for new tablet servers to be responsive.
      Set<NodeDetails> allTserversAdded = new HashSet<>();
      allTserversAdded.addAll(tserversAdded);
      allTserversAdded.addAll(readOnlyTserversAdded);
      createWaitForServersTasks(allTserversAdded, ServerType.TSERVER)
          .setSubTaskGroupType(SubTaskGroupType.ConfigureUniverse);

      // Install YBC on the pods
      if (taskParams().isEnableYbc()) {
        installYbcOnThePods(
            universe.getName(), tserversAdded, false, taskParams().getYbcSoftwareVersion());
        if (readClusters.size() == 1) {
          installYbcOnThePods(
              universe.getName(),
              readOnlyTserversAdded,
              true,
              taskParams().getYbcSoftwareVersion());
        }
        createWaitForYbcServerTask(allTserversAdded);
        createUpdateYbcTask(taskParams().getYbcSoftwareVersion())
            .setSubTaskGroupType(SubTaskGroupType.ConfigureUniverse);
      }

      createConfigureUniverseTasks(primaryCluster);
      // Run all the tasks.
      getRunnableTask().runSubTasks();
    } catch (Throwable t) {
      log.error("Error executing task {}, error='{}'", getName(), t.getMessage(), t);
      th = t;
      throw t;
    } finally {
      KubernetesOperatorStatusUpdater.updateYBUniverseStatus(
          getUniverse(), getName(), getUserTaskUUID(), th);
      unlockUniverseForUpdate();
    }
    log.info("Finished {} task.", getName());
  }
}
