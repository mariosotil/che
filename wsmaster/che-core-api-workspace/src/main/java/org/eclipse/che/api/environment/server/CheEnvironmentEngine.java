/*******************************************************************************
 * Copyright (c) 2012-2016 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.api.environment.server;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;

import org.eclipse.che.api.core.ApiException;
import org.eclipse.che.api.core.ConflictException;
import org.eclipse.che.api.core.NotFoundException;
import org.eclipse.che.api.core.ServerException;
import org.eclipse.che.api.core.model.machine.MachineConfig;
import org.eclipse.che.api.core.model.machine.MachineLogMessage;
import org.eclipse.che.api.core.model.machine.MachineSource;
import org.eclipse.che.api.core.model.machine.MachineStatus;
import org.eclipse.che.api.core.model.workspace.Environment;
import org.eclipse.che.api.core.notification.EventService;
import org.eclipse.che.api.core.notification.EventSubscriber;
import org.eclipse.che.api.core.util.AbstractLineConsumer;
import org.eclipse.che.api.core.util.CompositeLineConsumer;
import org.eclipse.che.api.core.util.FileLineConsumer;
import org.eclipse.che.api.core.util.LineConsumer;
import org.eclipse.che.api.core.util.MessageConsumer;
import org.eclipse.che.api.environment.server.compose.ComposeFileParser;
import org.eclipse.che.api.environment.server.compose.ComposeMachineInstanceProvider;
import org.eclipse.che.api.environment.server.compose.ComposeServicesStartStrategy;
import org.eclipse.che.api.environment.server.compose.model.ComposeEnvironmentImpl;
import org.eclipse.che.api.environment.server.compose.model.ComposeServiceImpl;
import org.eclipse.che.api.environment.server.exception.EnvironmentNotRunningException;
import org.eclipse.che.api.machine.server.MachineInstanceProviders;
import org.eclipse.che.api.machine.server.event.InstanceStateEvent;
import org.eclipse.che.api.machine.server.exception.MachineException;
import org.eclipse.che.api.machine.server.model.impl.LimitsImpl;
import org.eclipse.che.api.machine.server.model.impl.MachineConfigImpl;
import org.eclipse.che.api.machine.server.model.impl.MachineImpl;
import org.eclipse.che.api.machine.server.model.impl.MachineLogMessageImpl;
import org.eclipse.che.api.machine.server.model.impl.SnapshotImpl;
import org.eclipse.che.api.machine.server.spi.Instance;
import org.eclipse.che.api.machine.server.spi.InstanceProvider;
import org.eclipse.che.api.machine.shared.dto.event.MachineStatusEvent;
import org.eclipse.che.api.workspace.server.StripedLocks;
import org.eclipse.che.commons.env.EnvironmentContext;
import org.eclipse.che.commons.lang.IoUtil;
import org.eclipse.che.commons.lang.NameGenerator;
import org.eclipse.che.commons.lang.Size;
import org.slf4j.Logger;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static java.lang.String.format;
import static org.eclipse.che.api.machine.server.event.InstanceStateEvent.Type.DIE;
import static org.eclipse.che.api.machine.server.event.InstanceStateEvent.Type.OOM;
import static org.eclipse.che.dto.server.DtoFactory.newDto;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Facade for implementation specific operations with environment runtimes.
 *
 * @author Alexander Garagatyi
 * @author Yevhenii Voevodin
 */
@Singleton
public class CheEnvironmentEngine {

    private static final Logger LOG = getLogger(CheEnvironmentEngine.class);

    private final Map<String, EnvironmentHolder> environments;
    private final StripedLocks                   stripedLocks;
    private final File                           machineLogsDir;
    private final MachineInstanceProviders       machineInstanceProviders;
    private final String                         defaultMachineMemorySizeMB;
    private final EventService                   eventService;
    private final ComposeFileParser              composeFileParser;
    private final ComposeServicesStartStrategy   startStrategy;
    private final ComposeMachineInstanceProvider composeProvider;

    private volatile boolean isPreDestroyInvoked;

    @Inject
    public CheEnvironmentEngine(MachineInstanceProviders machineInstanceProviders,
                                @Named("machine.logs.location") String machineLogsDir,
                                @Named("machine.default_mem_size_mb") int defaultMachineMemorySizeMB,
                                EventService eventService,
                                ComposeFileParser composeFileParser,
                                ComposeServicesStartStrategy startStrategy,
                                ComposeMachineInstanceProvider composeProvider) {
        this.eventService = eventService;
        this.composeFileParser = composeFileParser;
        this.startStrategy = startStrategy;
        this.composeProvider = composeProvider;
        this.environments = new ConcurrentHashMap<>();
        this.machineInstanceProviders = machineInstanceProviders;
        this.machineLogsDir = new File(machineLogsDir);
        this.defaultMachineMemorySizeMB = defaultMachineMemorySizeMB + "MB";
        // 16 - experimental value for stripes count, it comes from default hash map size
        this.stripedLocks = new StripedLocks(16);
        eventService.subscribe(new MachineCleaner());
    }

    /**
     * Returns all machines from environment of specific workspace.
     *
     * @param workspaceId
     *         ID of workspace that owns environment machines
     * @return list of machines
     * @throws EnvironmentNotRunningException
     *         if environment is not running
     */
    public List<Instance> getMachines(String workspaceId) throws EnvironmentNotRunningException {
        EnvironmentHolder environment;
        try (StripedLocks.ReadLock lock = stripedLocks.acquireReadLock(workspaceId)) {
            environment = environments.get(workspaceId);
            if (environment == null) {
                throw new EnvironmentNotRunningException("Environment with ID '" + workspaceId + "' is not found");
            }
            return new ArrayList<>(environment.machines);
        }
    }

    /**
     * Returns specific machine from environment of specific workspace.
     *
     * @param workspaceId
     *         ID of workspace that owns environment machines
     * @param machineId
     *         ID of requested machine
     * @return requested machine
     * @throws EnvironmentNotRunningException
     *         if environment is not running
     * @throws NotFoundException
     *         if machine is not found in the environment
     */
    public Instance getMachine(String workspaceId, String machineId) throws NotFoundException {
        EnvironmentHolder environment;
        try (StripedLocks.ReadLock lock = stripedLocks.acquireReadLock(workspaceId)) {
            environment = environments.get(workspaceId);
        }
        if (environment == null) {
            throw new EnvironmentNotRunningException("Environment with ID '" + workspaceId + "' is not found");
        }
        return environment.machines.stream()
                                   .filter(instance -> instance.getId().equals(machineId))
                                   .findAny()
                                   .orElseThrow(() -> new NotFoundException(
                                           format("Machine with ID '%s' is not found in the environment of workspace '%s'",
                                                  machineId, workspaceId)));
    }

    /**
     * Starts provided environment.
     * <p/>
     * Environment starts if and only all machines in environment definition start successfully.<br/>
     * Otherwise exception is thrown by this method.<br/>
     * It is not defined whether environment start fails right after first failure or in the end of the process.<br/>
     * Starting order of machines is not guarantied. Machines can start sequentially or in parallel.
     *
     * @param workspaceId
     *         ID of workspace that owns provided environment
     * @param envName
     *         name of environment
     * @param env
     *         environment to start
     * @param recover
     *         whether machines from environment should be recovered or not
     * @param messageConsumer
     *         consumer of log messages from machines in the environment
     * @return list of running machines of this environment
     * @throws ServerException
     *         if other error occurs
     */
    public List<Instance> start(String workspaceId,
                                String envName,
                                Environment env,
                                boolean recover,
                                MessageConsumer<MachineLogMessage> messageConsumer) throws ServerException,
                                                                                           ConflictException {
        // add random chars to ensure that old environments that weren't removed by some reason won't prevent start
        String networkId = NameGenerator.generate(workspaceId + "_", 16);

        initializeEnvironment(workspaceId,
                              envName,
                              env,
                              networkId,
                              messageConsumer);

        String devMachineName = findDevMachineName(env);

        String namespace = EnvironmentContext.getCurrent().getSubject().getUserName();
        startEnvironmentQueue(namespace,
                              workspaceId,
                              devMachineName,
                              networkId,
                              recover);

        try (StripedLocks.WriteLock lock = stripedLocks.acquireWriteLock(workspaceId)) {
            EnvironmentHolder environmentHolder = environments.get(workspaceId);
            // possible only if environment was stopped during its start
            if (environmentHolder == null) {
                throw new ServerException("Environment start was interrupted by environment stopping");
            }
            environmentHolder.status = EnvStatus.RUNNING;
            // prevent list modification
            return new ArrayList<>(environmentHolder.machines);
        }
    }

    /**
     * Stops running environment of specified workspace.
     *
     * @param workspaceId
     *         ID of workspace that owns environment
     * @throws EnvironmentNotRunningException
     *         when environment is not running
     * @throws ServerException
     *         if other error occurs
     */
    public void stop(String workspaceId) throws EnvironmentNotRunningException,
                                                ServerException {
        List<Instance> machinesCopy = null;
        EnvironmentHolder environmentHolder;
        try (StripedLocks.WriteLock lock = stripedLocks.acquireWriteLock(workspaceId)) {
            environmentHolder = environments.get(workspaceId);
            if (environmentHolder == null || environmentHolder.status != EnvStatus.RUNNING) {
                throw new EnvironmentNotRunningException(
                        format("Stop of not running environment of workspace with ID '%s' is not allowed.",
                               workspaceId));
            }
            environments.remove(workspaceId);
            List<Instance> machines = environmentHolder.machines;
            if (machines != null && !machines.isEmpty()) {
                machinesCopy = new ArrayList<>(machines);
            }
        }

        // long operation - perform out of lock
        if (machinesCopy != null) {
            destroyEnvironment(environmentHolder.networkId, machinesCopy);
        }
    }

    /**
     * Starts machine in running environment.
     *
     * @param workspaceId
     *         ID of workspace that owns environment in which machine should be started
     * @param machineConfig
     *         configuration of machine that should be started
     * @return running machine
     * @throws EnvironmentNotRunningException
     *         if environment is not running
     * @throws ConflictException
     *         if machine with the same name already exists in the environment
     * @throws ServerException
     *         if any other error occurs
     */
    // TODO change config to compose service
    public Instance startMachine(String workspaceId,
                                 MachineConfig machineConfig) throws ServerException,
                                                                     EnvironmentNotRunningException,
                                                                     ConflictException {
        MachineConfig machineConfigCopy = new MachineConfigImpl(machineConfig);
        EnvironmentHolder environmentHolder;
        try (StripedLocks.ReadLock lock = stripedLocks.acquireReadLock(workspaceId)) {
            environmentHolder = environments.get(workspaceId);
            if (environmentHolder == null || environmentHolder.status != EnvStatus.RUNNING) {
                throw new EnvironmentNotRunningException(format("Environment '%s' is not running", workspaceId));
            }
            for (Instance machine : environmentHolder.machines) {
                if (machine.getConfig().getName().equals(machineConfigCopy.getName())) {
                    throw new ConflictException(
                            format("Machine with name '%s' already exists in environment of workspace '%s'",
                                   machineConfigCopy.getName(), workspaceId));
                }
            }
        }
        String machineId = generateMachineId();
        final String creator = EnvironmentContext.getCurrent().getSubject().getUserId();

        throw new RuntimeException("Operation is not implemented");
//        return startInstance(workspaceId,
//                             machineId,
//                             environmentHolder.name,
//                             creator,
//                             machineConfig.getName(),
//                             machine,
//                             ,
//                             ,
//                             environmentHolder.logger);
    }

    /**
     * Stops machine in running environment.
     *
     * @param workspaceId
     *         ID of workspace of environment that owns machine
     * @param machineId
     *         ID of machine that should be stopped
     * @throws NotFoundException
     *         if machine in not found in environment
     * @throws EnvironmentNotRunningException
     *         if environment is not running
     * @throws ConflictException
     *         if stop of dev machine is requested
     * @throws ServerException
     *         if other error occurs
     */
    public void stopMachine(String workspaceId, String machineId) throws NotFoundException,
                                                                         ServerException,
                                                                         ConflictException {
        Instance targetMachine = null;
        try (StripedLocks.WriteLock lock = stripedLocks.acquireWriteLock(workspaceId)) {
            EnvironmentHolder environmentHolder = environments.get(workspaceId);
            if (environmentHolder == null || environmentHolder.status != EnvStatus.RUNNING) {
                throw new EnvironmentNotRunningException(format("Environment '%s' is not running", workspaceId));
            }
            for (Instance machine : environmentHolder.machines) {
                if (machine.getId().equals(machineId)) {
                    if (machine.getConfig().isDev()) {
                        throw new ConflictException(
                                "Stop of dev machine is not allowed. Please, stop whole environment");
                    }
                    targetMachine = machine;
                    break;
                }
            }
            environmentHolder.machines.remove(targetMachine);
        }
        if (targetMachine == null) {
            throw new NotFoundException(format("Machine with ID '%s' is not found in environment of workspace '%s'",
                                               machineId, workspaceId));
        }

        // out of lock to prevent blocking by potentially long-running method
        destroyMachine(targetMachine);
    }

    /**
     * Saves machine into snapshot.
     *
     * @param namespace
     *         namespace of the workspace
     * @param workspaceId
     *         ID of workspace that owns environment
     * @param machineId
     *         ID of machine to save
     * @return snapshot
     * @throws EnvironmentNotRunningException
     *         if environment of machine is not running
     * @throws NotFoundException
     *         if machine is not running
     * @throws ServerException
     *         if another error occurs
     */
    public SnapshotImpl saveSnapshot(String namespace,
                                     String workspaceId,
                                     String machineId) throws ServerException,
                                                              NotFoundException {
        EnvironmentHolder environmentHolder;
        SnapshotImpl snapshot = null;
        Instance instance = null;
        try (StripedLocks.ReadLock lock = stripedLocks.acquireReadLock(workspaceId)) {
            environmentHolder = environments.get(workspaceId);
            if (environmentHolder == null || environmentHolder.status != EnvStatus.RUNNING) {
                throw new EnvironmentNotRunningException(format("Environment '%s' is not running", workspaceId));
            }
            for (Instance machine : environmentHolder.machines) {
                if (machine.getId().equals(machineId)) {
                    instance = machine;
                    snapshot = SnapshotImpl.builder()
                                           .generateId()
                                           .setType(machine.getConfig().getType())
                                           .setNamespace(namespace)
                                           .setWorkspaceId(machine.getWorkspaceId())
                                           .setDescription(machine.getEnvName())
                                           .setDev(machine.getConfig().isDev())
                                           .setEnvName(machine.getEnvName())
                                           .setMachineName(machine.getConfig().getName())
                                           .useCurrentCreationDate()
                                           .build();
                }
            }
        }
        if (instance == null) {
            throw new NotFoundException(format("Machine with id '%s' is not found in environment of workspace '%s'",
                                               machineId, workspaceId));
        }
        try {
            MachineSource machineSource = instance.saveToSnapshot();
            snapshot.setMachineSource(machineSource);
            return snapshot;
        } catch (ServerException e) {
            try {
                instance.getLogger().writeLine("Snapshot storing failed. " + e.getLocalizedMessage());
            } catch (IOException ignore) {
            }
            throw e;
        }
    }

    /**
     * Removes snapshot of machine.
     *
     * @param snapshot
     *         description of snapshot that should be removed
     * @throws ServerException
     *         if error occurs on snapshot removal
     */
    public void removeSnapshot(SnapshotImpl snapshot) throws ServerException {
        final String instanceType = snapshot.getType();
        try {
            final InstanceProvider instanceProvider = machineInstanceProviders.getProvider(instanceType);
            instanceProvider.removeInstanceSnapshot(snapshot.getMachineSource());
        } catch (NotFoundException e) {
            throw new ServerException(e.getLocalizedMessage(), e);
        }
    }

    private void initializeEnvironment(String workspaceId,
                                       String envName,
                                       Environment env,
                                       String networkId,
                                       MessageConsumer<MachineLogMessage> messageConsumer)
            throws ServerException,
                   ConflictException {

        ComposeEnvironmentImpl composeEnvironment = composeFileParser.parse(env);

        normalizeEnvironment(composeEnvironment);

        List<String> servicesOrder = startStrategy.order(composeEnvironment);

        EnvironmentHolder environmentHolder = new EnvironmentHolder(servicesOrder,
                                                                    composeEnvironment,
                                                                    messageConsumer,
                                                                    EnvStatus.STARTING,
                                                                    envName,
                                                                    networkId);

        try (StripedLocks.WriteLock lock = stripedLocks.acquireWriteLock(workspaceId)) {
            if (environments.putIfAbsent(workspaceId, environmentHolder) != null) {
                throw new ConflictException(format("Environment of workspace '%s' already exists", workspaceId));
            }
        }
    }

    private void normalizeEnvironment(ComposeEnvironmentImpl composeEnvironment) {
        for (Map.Entry<String, ComposeServiceImpl> serviceEntry : composeEnvironment.getServices()
                                                                                    .entrySet()) {
            if (serviceEntry.getValue().getMemLimit() == 0) {
                serviceEntry.getValue().setMemLimit(Size.parseSize(defaultMachineMemorySizeMB));
            }
        }
    }

    private String findDevMachineName(Environment env) throws ServerException {
        return env.getMachines()
                  .entrySet()
                  .stream()
                  .filter(entry -> entry.getValue()
                                        .getAgents()
                                        .contains("ws-agent"))
                  .findAny()
                  .orElseThrow(
                          () -> new ServerException("Agent 'ws-agent' is not found in any of environment machines"))
                  .getKey();
    }

    /**
     * Starts all machine from machine queue of environment.
     */
    private void startEnvironmentQueue(String namespace,
                                       String workspaceId,
                                       String devMachineName,
                                       String networkId,
                                       boolean recover)
            throws ServerException {
        // Starting all machines in environment one by one by getting configs
        // from the corresponding starting queue.
        // Config will be null only if there are no machines left in the queue
        String envName;
        MessageConsumer<MachineLogMessage> envLogger;
        try (StripedLocks.ReadLock lock = stripedLocks.acquireReadLock(workspaceId)) {
            EnvironmentHolder environmentHolder = environments.get(workspaceId);
            if (environmentHolder == null) {
                throw new ServerException("Environment start is interrupted.");
            }
            envName = environmentHolder.name;
            envLogger = environmentHolder.logger;
        }

        try {
            composeProvider.createNetwork(networkId);// TODO find why if several networks exists network creation fails

            String machineName = queuePeekOrFail(workspaceId);
            while (machineName != null) {
                boolean isDev = devMachineName.equals(machineName);
                // Environment start is failed when any machine start is failed, so if any error
                // occurs during machine creation then environment start fail is reported and
                // start resources such as queue and descriptor must be cleaned up
                String machineId = generateMachineId();
                String creator = EnvironmentContext.getCurrent().getSubject().getUserId();

                ComposeServiceImpl composeService;
                try (StripedLocks.ReadLock lock = stripedLocks.acquireReadLock(workspaceId)) {
                    EnvironmentHolder environmentHolder = environments.get(workspaceId);
                    if (environmentHolder == null) {
                        throw new ServerException("Environment start is interrupted.");
                    }
                    composeService = environmentHolder.composeEnvironment.getServices().get(machineName);
                }
                // should not happen
                if (composeService == null) {
                    LOG.error("Compose service with name {} is missing in compose environment", machineName);
                    throw new ServerException("Environment of workspace with ID '%s' failed due to internal error");
                }

                Instance instance = startInstance(namespace,
                                                  workspaceId,
                                                  machineId,
                                                  envName,
                                                  creator,
                                                  machineName,
                                                  composeService,
                                                  networkId,
                                                  isDev,
                                                  recover,
                                                  envLogger);

                // Machine destroying is an expensive operation which must be
                // performed outside of the lock, this section checks if
                // the environment wasn't stopped while it is starting and sets
                // polled flag to true if the environment wasn't stopped.
                // Also polls the proceeded machine configuration from the queue
                boolean queuePolled = false;
                try (StripedLocks.WriteLock lock = stripedLocks.acquireWriteLock(workspaceId)) {
                    ensurePreDestroyIsNotExecuted();
                    EnvironmentHolder environmentHolder = environments.get(workspaceId);
                    if (environmentHolder != null) {
                        final Queue<String> queue = environmentHolder.startQueue;
                        if (queue != null) {
                            queue.poll();
                            queuePolled = true;
                        }
                    }
                }

                // If machine config is not polled from the queue
                // then environment was stopped and newly created machine
                // must be destroyed
                if (!queuePolled) {
                    try {
                        eventService.publish(newDto(MachineStatusEvent.class)
                                                     .withEventType(MachineStatusEvent.EventType.DESTROYING)
                                                     .withDev(isDev)
                                                     .withMachineName(machineName)
                                                     .withMachineId(machineId)
                                                     .withWorkspaceId(workspaceId));

                        instance.destroy();

                        removeMachine(workspaceId, machineId);

                        eventService.publish(newDto(MachineStatusEvent.class)
                                                     .withEventType(MachineStatusEvent.EventType.DESTROYED)
                                                     .withDev(isDev)
                                                     .withMachineName(machineName)
                                                     .withMachineId(machineId)
                                                     .withWorkspaceId(workspaceId));
                    } catch (MachineException e) {
                        LOG.error(e.getLocalizedMessage(), e);
                    }
                    throw new ServerException("Workspace '" + workspaceId +
                                              "' start interrupted. Workspace stopped before all its machines started");
                }

                machineName = queuePeekOrFail(workspaceId);
            }
        } catch (RuntimeException | ServerException e) {
            EnvironmentHolder env;
            try (StripedLocks.WriteLock lock = stripedLocks.acquireWriteLock(workspaceId)) {
                env = environments.remove(workspaceId);
            }

            try {
                destroyEnvironment(env.networkId, env.machines);
            } catch (Exception remEx) {
                LOG.error(remEx.getLocalizedMessage(), remEx);
            }
            throw new ServerException(e.getLocalizedMessage(), e);
        }
    }

    private Instance startInstance(String namespace,
                                   String workspaceId,
                                   String machineId,
                                   String envName,
                                   String creator,
                                   String machineName,
                                   ComposeServiceImpl service,
                                   String networkId,
                                   boolean isDev,
                                   boolean recover,
                                   MessageConsumer<MachineLogMessage> environmentLogger)
            throws ServerException {

        LineConsumer machineLogger = null;
        Instance instance = null;
        try {
            addMachine(workspaceId,
                       envName,
                       machineId,
                       machineName,
                       creator,
                       isDev,
                       service.getMemLimit());

            eventService.publish(newDto(MachineStatusEvent.class)
                                         .withEventType(MachineStatusEvent.EventType.CREATING)
                                         .withDev(isDev)
                                         .withMachineName(machineName)
                                         .withMachineId(machineId)
                                         .withWorkspaceId(workspaceId));

            machineLogger = getMachineLogger(environmentLogger,
                                             machineId,
                                             machineName);

            instance = composeProvider.startService(namespace,
                                                    workspaceId,
                                                    envName,
                                                    machineId,
                                                    machineName,
                                                    isDev,
                                                    networkId,
                                                    service,
                                                    machineLogger);
//            try {
//            TODO modify compose service image value
//            if (recover) {
//                final SnapshotImpl snapshot = snapshotDao.getSnapshot(workspaceId,
//                                                                      environmentName,
//                                                                      machine.getConfig().getName());
//                machine.getConfig().setSource(snapshot.getMachineSource());
//            }
//            instance = instanceInstance();
//            } catch (SourceNotFoundException e) {
//                if (recover) {
//                TODO modify compose service image back and rerun
//                    LOG.error("Image of snapshot for machine " + machine.getConfig().getName() + " not found. " +
//                              "Machine will be created from origin source");
//                    machine.getConfig().setSource(sourceCopy);
//                    instance = instanceProvider.createInstance(machine, machineLogger);
//                } else {
//                    throw e;
//                }
//            }
            replaceMachine(instance);

            eventService.publish(newDto(MachineStatusEvent.class)
                                         .withEventType(MachineStatusEvent.EventType.RUNNING)
                                         .withDev(isDev)
                                         .withMachineName(machineName)
                                         .withMachineId(instance.getId())
                                         .withWorkspaceId(workspaceId));

            return instance;
        } catch (ApiException | RuntimeException e) {
            removeMachine(workspaceId, machineId);

            if (instance != null) {
                try {
                    instance.destroy();
                } catch (Exception destroyingExc) {
                    LOG.error(destroyingExc.getLocalizedMessage(), destroyingExc);
                }
            }

            if (machineLogger != null) {
                try {
                    machineLogger.writeLine("[ERROR] " + e.getLocalizedMessage());
                } catch (IOException ioEx) {
                    LOG.error(ioEx.getLocalizedMessage(), ioEx);
                }
                try {
                    machineLogger.close();
                } catch (IOException ioEx) {
                    LOG.error(ioEx.getLocalizedMessage(), ioEx);
                }
            }

            eventService.publish(newDto(MachineStatusEvent.class)
                                         .withEventType(MachineStatusEvent.EventType.ERROR)
                                         .withDev(isDev)
                                         .withMachineName(machineName)
                                         .withMachineId(machineId)
                                         .withWorkspaceId(workspaceId));

            throw e;
        }
    }

    private void addMachine(String workspaceId,
                            String envName,
                            String machineId,
                            String machineName,
                            String creator,
                            boolean isDev,
                            long memLimitBytes) throws ServerException {
        MachineImpl machineImpl = MachineImpl.builder()
                                             .setConfig(new MachineConfigImpl(isDev,
                                                                              machineName,
                                                                              "docker",
                                                                              null,
                                                                              new LimitsImpl(bytesToMB(memLimitBytes)),
                                                                              Collections.emptyList(),
                                                                              Collections.emptyMap()))
                                             .setId(machineId)
                                             .setWorkspaceId(workspaceId)
                                             .setStatus(MachineStatus.CREATING)
                                             .setEnvName(envName)
                                             .setOwner(creator)
                                             .build();
        Instance machine = new NoOpMachineInstance(machineImpl);
        try (StripedLocks.WriteLock lock = stripedLocks.acquireWriteLock(workspaceId)) {
            ensurePreDestroyIsNotExecuted();
            EnvironmentHolder environmentHolder = environments.get(workspaceId);
            if (environmentHolder != null && environmentHolder.status != EnvStatus.STOPPING) {
                environmentHolder.machines.add(machine);
            } else {
                throw new ServerException(
                        format("Can't add machine into environment. Environment of workspace '%s' is missing",
                               workspaceId));
            }
        }
    }

    private int bytesToMB(long bytes) {
        return (int)Size.parseSizeToMegabytes(Long.toString(bytes) + "b");
    }

    private void removeMachine(String workspaceId,
                               String machineId) {
        try (StripedLocks.WriteLock lock = stripedLocks.acquireWriteLock(workspaceId)) {
            EnvironmentHolder environmentHolder = environments.get(workspaceId);
            if (environmentHolder != null) {
                removeFirstMatching(environmentHolder.machines, m -> m.getId().equals(machineId));
            }
        }
    }

    private void replaceMachine(Instance machine) throws ServerException {
        try (StripedLocks.WriteLock lock = stripedLocks.acquireWriteLock(machine.getWorkspaceId())) {
            ensurePreDestroyIsNotExecuted();
            EnvironmentHolder environmentHolder = environments.get(machine.getWorkspaceId());
            if (environmentHolder != null) {
                for (int i = 0; i < environmentHolder.machines.size(); i++) {
                    if (environmentHolder.machines.get(i).getId().equals(machine.getId())) {
                        environmentHolder.machines.set(i, machine);
                        return;
                    }
                }
            }
        }
        // if this area is reachable then environment/machine is not found and machine should be stopped
        try {
            machine.destroy();
        } catch (MachineException e) {
            LOG.error(e.getLocalizedMessage(), e);
        }
        // should not happen
        throw new ServerException(format(
                "Machine with ID '%s' and name '%s' has been stopped because its configuration is not found in the environment of workspace '%s'",
                machine.getId(), machine.getConfig().getName(), machine.getWorkspaceId()));
    }

    /**
     * Gets head config from the queue associated with the given {@code workspaceId}.
     *
     * <p>Note that this method won't actually poll the queue.
     *
     * <p>Fails if environment start was interrupted by stop(queue doesn't exist).
     *
     * @return machine config which is in the queue head, or null
     * if there are no machine configs left
     * @throws ServerException
     *         if queue doesn't exist which means that {@link #stop(String)} executed
     *         before all the machines started
     * @throws ServerException
     *         if pre destroy has been invoked before peek config retrieved
     */
    private String queuePeekOrFail(String workspaceId) throws ServerException {
        try (StripedLocks.ReadLock lock = stripedLocks.acquireReadLock(workspaceId)) {
            ensurePreDestroyIsNotExecuted();
            EnvironmentHolder environmentHolder = environments.get(workspaceId);
            if (environmentHolder == null || environmentHolder.startQueue == null) {
                throw new ServerException("Workspace " + workspaceId +
                                          " start interrupted. Workspace was stopped before all its machines were started");
            }
            return environmentHolder.startQueue.peek();
        }
    }

    /**
     * Destroys provided machines and associated network.
     */
    private void destroyEnvironment(String networkId,
                                    List<Instance> machines) {
        for (Instance machine : machines) {
            try {
                destroyMachine(machine);
            } catch (RuntimeException | MachineException ex) {
                LOG.error(format("Could not destroy machine '%s' of workspace '%s'",
                                 machine.getId(),
                                 machine.getWorkspaceId()),
                          ex);
            }
        }
        try {
            composeProvider.destroyNetwork(networkId);
        } catch (RuntimeException | ServerException netExc) {
            LOG.error(netExc.getLocalizedMessage(), netExc);
        }
    }

    private void destroyMachine(Instance machine) throws MachineException {
        eventService.publish(newDto(MachineStatusEvent.class)
                                     .withEventType(MachineStatusEvent.EventType.DESTROYING)
                                     .withDev(machine.getConfig().isDev())
                                     .withMachineName(machine.getConfig().getName())
                                     .withMachineId(machine.getId())
                                     .withWorkspaceId(machine.getWorkspaceId()));

        machine.destroy();

        eventService.publish(newDto(MachineStatusEvent.class)
                                     .withEventType(MachineStatusEvent.EventType.DESTROYED)
                                     .withDev(machine.getConfig().isDev())
                                     .withMachineName(machine.getConfig().getName())
                                     .withMachineId(machine.getId())
                                     .withWorkspaceId(machine.getWorkspaceId()));
    }

    @SuppressWarnings("unused")
    @PostConstruct
    private void createLogsDir() {
        if (!(machineLogsDir.exists() || machineLogsDir.mkdirs())) {
            throw new IllegalStateException("Unable create directory " + machineLogsDir.getAbsolutePath());
        }
    }

    /**
     * Removes all descriptors from the in-memory storage, while
     * {@link MachineProcessManager#cleanup()} is responsible for machines destroying.
     */
    @PreDestroy
    @VisibleForTesting
    @SuppressWarnings("unused")
    void cleanup() {
        isPreDestroyInvoked = true;
        final java.io.File[] files = machineLogsDir.listFiles();
        if (files != null && files.length > 0) {
            for (java.io.File f : files) {
                if (!IoUtil.deleteRecursive(f)) {
                    LOG.warn("Failed delete {}", f);
                }
            }
        }
    }

    private LineConsumer getMachineLogger(MessageConsumer<MachineLogMessage> environmentLogger,
                                          String machineId,
                                          String machineName) throws ServerException {
        createMachineLogsDir(machineId);

        LineConsumer lineConsumer = new AbstractLineConsumer() {
            @Override
            public void writeLine(String line) throws IOException {
                environmentLogger.consume(new MachineLogMessageImpl(machineName, line));
            }
        };
        try {
            return new CompositeLineConsumer(new FileLineConsumer(getMachineLogsFile(machineId)),
                                             lineConsumer);
        } catch (IOException e) {
            throw new MachineException(format("Unable create log file '%s' for machine '%s'.",
                                              e.getLocalizedMessage(),
                                              machineId));
        }
    }

    private void createMachineLogsDir(String machineId) throws MachineException {
        File dir = new File(machineLogsDir, machineId);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new MachineException("Can't create folder for the logs of machine");
        }
    }

    private File getMachineLogsFile(String machineId) {
        return new File(new File(machineLogsDir, machineId), "machineId.logs");
    }

    @VisibleForTesting
    String generateMachineId() {
        return NameGenerator.generate("machine", 16);
    }

    private static <T> T removeFirstMatching(List<? extends T> elements, Predicate<T> predicate) {
        T element = null;
        for (final Iterator<? extends T> it = elements.iterator(); it.hasNext() && element == null; ) {
            final T next = it.next();
            if (predicate.test(next)) {
                element = next;
                it.remove();
            }
        }
        return element;
    }

    private void ensurePreDestroyIsNotExecuted() throws ServerException {
        if (isPreDestroyInvoked) {
            throw new ServerException("Could not perform operation because application server is stopping");
        }
    }

    private enum EnvStatus {
        STARTING,
        RUNNING,
        STOPPING
    }

    private static class EnvironmentHolder {
        final Queue<String>                      startQueue;
        final ComposeEnvironmentImpl             composeEnvironment;
        final MessageConsumer<MachineLogMessage> logger;
        final String                             name;
        final String                             networkId;

        List<Instance> machines;
        EnvStatus      status;

        EnvironmentHolder(List<String> startQueue,
                          ComposeEnvironmentImpl composeEnvironment,
                          MessageConsumer<MachineLogMessage> envLogger,
                          EnvStatus envStatus,
                          String name,
                          String networkId) {
            this.startQueue = new ArrayDeque<>(startQueue);
            this.machines = new CopyOnWriteArrayList<>();
            this.logger = envLogger;
            this.status = envStatus;
            this.name = name;
            this.composeEnvironment = composeEnvironment;
            this.networkId = networkId;
        }

        public EnvironmentHolder(EnvironmentHolder environmentHolder) {
            this.startQueue = environmentHolder.startQueue;
            this.machines = environmentHolder.machines;
            this.logger = environmentHolder.logger;
            this.status = environmentHolder.status;
            this.name = environmentHolder.name;
            this.composeEnvironment = environmentHolder.composeEnvironment;
            this.networkId = environmentHolder.networkId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof EnvironmentHolder)) return false;
            EnvironmentHolder that = (EnvironmentHolder)o;
            return Objects.equals(startQueue, that.startQueue) &&
                   Objects.equals(machines, that.machines) &&
                   status == that.status &&
                   Objects.equals(logger, that.logger) &&
                   Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(startQueue, machines, status, logger, name);
        }
    }

    // cleanup machine if event about instance failure comes
    private class MachineCleaner implements EventSubscriber<InstanceStateEvent> {
        @Override
        public void onEvent(InstanceStateEvent event) {
            if ((event.getType() == OOM) || (event.getType() == DIE)) {
                EnvironmentHolder environmentHolder;
                try (StripedLocks.ReadLock lock = stripedLocks.acquireReadLock("workspaceId")) {
                    environmentHolder = environments.get(event.getWorkspaceId());
                }
                if (environmentHolder != null) {
                    for (Instance instance : environmentHolder.machines) {
                        if (instance.getId().equals(event.getMachineId())) {
                            String message = "Machine is destroyed. ";
                            if (event.getType() == OOM) {
                                message = message +
                                          "The processes in this machine need more RAM. This machine started with " +
                                          instance.getConfig().getLimits().getRam() +
                                          "MB. Create a new machine configuration that allocates additional RAM or increase " +
                                          "the workspace RAM limit in the user dashboard.";
                            }

                            try {
                                if (!Strings.isNullOrEmpty(message)) {
                                    instance.getLogger().writeLine(message);
                                }
                            } catch (IOException ignore) {
                            }

                            eventService.publish(newDto(MachineStatusEvent.class)
                                                         .withEventType(MachineStatusEvent.EventType.DESTROYED)
                                                         .withDev(instance.getConfig().isDev())
                                                         .withMachineId(instance.getId())
                                                         .withWorkspaceId(instance.getWorkspaceId())
                                                         .withMachineName(instance.getConfig().getName()));
                        }
                    }
                }
            }
        }
    }
}
