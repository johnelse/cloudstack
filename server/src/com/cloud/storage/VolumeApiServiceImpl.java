// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.storage;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import javax.inject.Inject;

import org.apache.log4j.Logger;

import org.apache.cloudstack.api.command.user.volume.AttachVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.CreateVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.DetachVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.ExtractVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.MigrateVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.ResizeVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.UploadVolumeCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.ChapInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.HostScope;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.Scope;
import org.apache.cloudstack.engine.subsystem.api.storage.StoragePoolAllocator;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService.VolumeApiResult;
import org.apache.cloudstack.framework.async.AsyncCallFuture;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.jobs.AsyncJob;
import org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.cloudstack.framework.jobs.Outcome;
import org.apache.cloudstack.framework.jobs.dao.VmWorkJobDao;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.framework.jobs.impl.OutcomeImpl;
import org.apache.cloudstack.framework.jobs.impl.VmWorkJobVO;
import org.apache.cloudstack.jobs.JobInfo;
import org.apache.cloudstack.storage.command.AttachAnswer;
import org.apache.cloudstack.storage.command.AttachCommand;
import org.apache.cloudstack.storage.command.DettachCommand;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreVO;
import org.apache.cloudstack.storage.image.datastore.ImageStoreEntity;
import org.apache.cloudstack.utils.identity.ManagementServerNode;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.api.ApiDBUtils;
import com.cloud.configuration.Config;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.domain.Domain;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.StorageUnavailableException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorCapabilitiesVO;
import com.cloud.hypervisor.dao.HypervisorCapabilitiesDao;
import com.cloud.org.Grouping;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.snapshot.SnapshotApiService;
import com.cloud.storage.snapshot.SnapshotManager;
import com.cloud.template.TemplateManager;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.User;
import com.cloud.user.VmDiskStatisticsVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.VmDiskStatisticsDao;
import com.cloud.utils.EnumUtils;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.Predicate;
import com.cloud.utils.UriUtils;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.db.UUIDManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VmWork;
import com.cloud.vm.VmWorkConstants;
import com.cloud.vm.VmWorkJobHandler;
import com.cloud.vm.VmWorkJobHandlerProxy;
import com.cloud.vm.VmWorkSerializer;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;

public class VolumeApiServiceImpl extends ManagerBase implements VolumeApiService, VmWorkJobHandler {
    private final static Logger s_logger = Logger.getLogger(VolumeApiServiceImpl.class);

    public static final String VM_WORK_JOB_HANDLER = VolumeApiServiceImpl.class.getSimpleName();

    @Inject
    private VolumeOrchestrationService _volumeMgr;

    @Inject
    private EntityManager _entityMgr;
    @Inject
    private AgentManager _agentMgr;
    @Inject
    private TemplateManager _tmpltMgr;
    @Inject
    private AsyncJobManager _asyncMgr;
    @Inject
    private SnapshotManager _snapshotMgr;
    @Inject
    private AccountManager _accountMgr;
    @Inject
    private ConfigurationManager _configMgr;
    @Inject
    private VolumeDao _volsDao;
    @Inject
    private HostDao _hostDao;
    @Inject
    private SnapshotDao _snapshotDao;
    @Inject
    StoragePoolDetailsDao storagePoolDetailsDao;
    @Inject
    private UserVmDao _userVmDao;
    @Inject
    VolumeDataStoreDao _volumeStoreDao;
    @Inject
    private VMInstanceDao _vmInstanceDao;
    @Inject
    private final PrimaryDataStoreDao _storagePoolDao = null;
    @Inject
    private DiskOfferingDao _diskOfferingDao;
    @Inject
    private AccountDao _accountDao;
    @Inject
    private final DataCenterDao _dcDao = null;
    @Inject
    private VMTemplateDao _templateDao;
    @Inject
    private VolumeDao _volumeDao;
    @Inject
    private ResourceLimitService _resourceLimitMgr;
    @Inject
    private VmDiskStatisticsDao _vmDiskStatsDao;
    @Inject
    private VMSnapshotDao _vmSnapshotDao;
    private List<StoragePoolAllocator> _storagePoolAllocators;
    @Inject
    private ConfigurationDao _configDao;
    @Inject
    private DataStoreManager dataStoreMgr;
    @Inject
    private VolumeService volService;
    @Inject
    private VolumeDataFactory volFactory;
    @Inject
    private SnapshotApiService snapshotMgr;
    @Inject
    private UUIDManager _uuidMgr;

    @Inject
    private HypervisorCapabilitiesDao _hypervisorCapabilitiesDao;

    @Inject
    private AsyncJobManager _jobMgr;

    @Inject
    protected VmWorkJobDao _workJobDao;

    VmWorkJobHandlerProxy _jobHandlerProxy = new VmWorkJobHandlerProxy(this);

    // TODO
    static final ConfigKey<Boolean> VmJobEnabled = new ConfigKey<Boolean>("Advanced",
            Boolean.class, "vm.job.enabled", "true",
            "True to enable new VM sync model. false to use the old way", false);
    static final ConfigKey<Long> VmJobCheckInterval = new ConfigKey<Long>("Advanced", Long.class, "vm.job.check.interval", "3000",
            "Interval in milliseconds to check if the job is complete", false);

    private long _maxVolumeSizeInGb;
    private final StateMachine2<Volume.State, Volume.Event, Volume> _volStateMachine;

    protected VolumeApiServiceImpl() {
        _volStateMachine = Volume.State.getStateMachine();
    }

    /*
     * Upload the volume to secondary storage.
     */
    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_VOLUME_UPLOAD, eventDescription = "uploading volume", async = true)
    public VolumeVO uploadVolume(UploadVolumeCmd cmd) throws ResourceAllocationException {
        Account caller = CallContext.current().getCallingAccount();
        long ownerId = cmd.getEntityOwnerId();
        Account owner = _entityMgr.findById(Account.class, ownerId);
        Long zoneId = cmd.getZoneId();
        String volumeName = cmd.getVolumeName();
        String url = cmd.getUrl();
        String format = cmd.getFormat();
        String imageStoreUuid = cmd.getImageStoreUuid();
        DataStore store = _tmpltMgr.getImageStore(imageStoreUuid, zoneId);

        validateVolume(caller, ownerId, zoneId, volumeName, url, format);

        VolumeVO volume = persistVolume(owner, zoneId, volumeName, url, cmd.getFormat());

        VolumeInfo vol = volFactory.getVolume(volume.getId());

        RegisterVolumePayload payload = new RegisterVolumePayload(cmd.getUrl(), cmd.getChecksum(), cmd.getFormat());
        vol.addPayload(payload);

        volService.registerVolume(vol, store);
        return volume;
    }

    private boolean validateVolume(Account caller, long ownerId, Long zoneId, String volumeName, String url, String format) throws ResourceAllocationException {

        // permission check
        _accountMgr.checkAccess(caller, null, true, _accountMgr.getActiveAccountById(ownerId));

        // Check that the resource limit for volumes won't be exceeded
        _resourceLimitMgr.checkResourceLimit(_accountMgr.getAccount(ownerId), ResourceType.volume);

        // Verify that zone exists
        DataCenterVO zone = _dcDao.findById(zoneId);
        if (zone == null) {
            throw new InvalidParameterValueException("Unable to find zone by id " + zoneId);
        }

        // Check if zone is disabled
        if (Grouping.AllocationState.Disabled == zone.getAllocationState() && !_accountMgr.isRootAdmin(caller.getType())) {
            throw new PermissionDeniedException("Cannot perform this operation, Zone is currently disabled: " + zoneId);
        }

        if (url.toLowerCase().contains("file://")) {
            throw new InvalidParameterValueException("File:// type urls are currently unsupported");
        }

        ImageFormat imgfmt = ImageFormat.valueOf(format.toUpperCase());
        if (imgfmt == null) {
            throw new IllegalArgumentException("Image format is incorrect " + format + ". Supported formats are " + EnumUtils.listValues(ImageFormat.values()));
        }

        String userSpecifiedName = volumeName;
        if (userSpecifiedName == null) {
            userSpecifiedName = getRandomVolumeName();
        }
        if ((!url.toLowerCase().endsWith("vhd")) && (!url.toLowerCase().endsWith("vhd.zip")) && (!url.toLowerCase().endsWith("vhd.bz2")) && (!url.toLowerCase().endsWith("vhdx"))
                && (!url.toLowerCase().endsWith("vhdx.zip")) && (!url.toLowerCase().endsWith("vhdx.gz")) && (!url.toLowerCase().endsWith("vhdx.bz2"))
                && (!url.toLowerCase().endsWith("vhd.gz")) && (!url.toLowerCase().endsWith("qcow2")) && (!url.toLowerCase().endsWith("qcow2.zip"))
                && (!url.toLowerCase().endsWith("qcow2.bz2")) && (!url.toLowerCase().endsWith("qcow2.gz")) && (!url.toLowerCase().endsWith("ova"))
                && (!url.toLowerCase().endsWith("ova.zip")) && (!url.toLowerCase().endsWith("ova.bz2")) && (!url.toLowerCase().endsWith("ova.gz"))
                && (!url.toLowerCase().endsWith("img")) && (!url.toLowerCase().endsWith("raw"))) {
            throw new InvalidParameterValueException("Please specify a valid " + format.toLowerCase());
        }

        if ((format.equalsIgnoreCase("vhd") && (!url.toLowerCase().endsWith(".vhd") && !url.toLowerCase().endsWith("vhd.zip") && !url.toLowerCase().endsWith("vhd.bz2") && !url
                .toLowerCase().endsWith("vhd.gz")))
                || (format.equalsIgnoreCase("vhdx") && (!url.toLowerCase().endsWith(".vhdx") && !url.toLowerCase().endsWith("vhdx.zip") && !url.toLowerCase().endsWith("vhdx.bz2") && !url
                        .toLowerCase().endsWith("vhdx.gz")))
                || (format.equalsIgnoreCase("qcow2") && (!url.toLowerCase().endsWith(".qcow2") && !url.toLowerCase().endsWith("qcow2.zip")
                        && !url.toLowerCase().endsWith("qcow2.bz2") && !url.toLowerCase().endsWith("qcow2.gz")))
                || (format.equalsIgnoreCase("ova") && (!url.toLowerCase().endsWith(".ova") && !url.toLowerCase().endsWith("ova.zip") && !url.toLowerCase().endsWith("ova.bz2") && !url
                        .toLowerCase().endsWith("ova.gz"))) || (format.equalsIgnoreCase("raw") && (!url.toLowerCase().endsWith(".img") && !url.toLowerCase().endsWith("raw")))) {
            throw new InvalidParameterValueException("Please specify a valid URL. URL:" + url + " is an invalid for the format " + format.toLowerCase());
        }
        UriUtils.validateUrl(url);

        // Check that the resource limit for secondary storage won't be exceeded
        _resourceLimitMgr.checkResourceLimit(_accountMgr.getAccount(ownerId), ResourceType.secondary_storage, UriUtils.getRemoteSize(url));

        return false;
    }

    public String getRandomVolumeName() {
        return UUID.randomUUID().toString();
    }

    @DB
    protected VolumeVO persistVolume(final Account owner, final Long zoneId, final String volumeName, final String url, final String format) {
        return Transaction.execute(new TransactionCallback<VolumeVO>() {
            @Override
            public VolumeVO doInTransaction(TransactionStatus status) {
                VolumeVO volume = new VolumeVO(volumeName, zoneId, -1, -1, -1, new Long(-1), null, null, 0, Volume.Type.DATADISK);
                volume.setPoolId(null);
                volume.setDataCenterId(zoneId);
                volume.setPodId(null);
                // to prevent a nullpointer deref I put the system account id here when no owner is given.
                // TODO Decide if this is valid or whether  throwing a CloudRuntimeException is more appropriate
                volume.setAccountId((owner == null) ? Account.ACCOUNT_ID_SYSTEM : owner.getAccountId());
                volume.setDomainId((owner == null) ? Domain.ROOT_DOMAIN : owner.getDomainId());
                long diskOfferingId = _diskOfferingDao.findByUniqueName("Cloud.com-Custom").getId();
                volume.setDiskOfferingId(diskOfferingId);
                // volume.setSize(size);
                volume.setInstanceId(null);
                volume.setUpdated(new Date());
                volume.setDomainId((owner == null) ? Domain.ROOT_DOMAIN : owner.getDomainId());
                volume.setFormat(ImageFormat.valueOf(format));
                volume = _volsDao.persist(volume);
                CallContext.current().setEventDetails("Volume Id: " + volume.getId());

                // Increment resource count during allocation; if actual creation fails,
                // decrement it
                _resourceLimitMgr.incrementResourceCount(volume.getAccountId(), ResourceType.volume);
                _resourceLimitMgr.incrementResourceCount(volume.getAccountId(), ResourceType.secondary_storage, UriUtils.getRemoteSize(url));

                return volume;
            }
        });
    }

    /*
     * Just allocate a volume in the database, don't send the createvolume cmd
     * to hypervisor. The volume will be finally created only when it's attached
     * to a VM.
     */
    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_VOLUME_CREATE, eventDescription = "creating volume", create = true)
    public VolumeVO allocVolume(CreateVolumeCmd cmd) throws ResourceAllocationException {
        // FIXME: some of the scheduled event stuff might be missing here...
        Account caller = CallContext.current().getCallingAccount();

        long ownerId = cmd.getEntityOwnerId();
        Boolean displayVolume = cmd.getDisplayVolume();

        // permission check
        _accountMgr.checkAccess(caller, null, true, _accountMgr.getActiveAccountById(ownerId));

        if (displayVolume == null) {
            displayVolume = true;
        } else {
            if (!_accountMgr.isRootAdmin(caller.getType())) {
                throw new PermissionDeniedException("Cannot update parameter displayvolume, only admin permitted ");
            }
        }

        // Check that the resource limit for volumes won't be exceeded
        _resourceLimitMgr.checkResourceLimit(_accountMgr.getAccount(ownerId), ResourceType.volume, displayVolume);

        Long zoneId = cmd.getZoneId();
        Long diskOfferingId = null;
        DiskOfferingVO diskOffering = null;
        Long size = null;
        Long minIops = null;
        Long maxIops = null;
        // Volume VO used for extracting the source template id
        VolumeVO parentVolume = null;

        // validate input parameters before creating the volume
        if ((cmd.getSnapshotId() == null && cmd.getDiskOfferingId() == null) || (cmd.getSnapshotId() != null && cmd.getDiskOfferingId() != null)) {
            throw new InvalidParameterValueException("Either disk Offering Id or snapshot Id must be passed whilst creating volume");
        }

        if (cmd.getSnapshotId() == null) {// create a new volume

            diskOfferingId = cmd.getDiskOfferingId();
            size = cmd.getSize();
            Long sizeInGB = size;
            if (size != null) {
                if (size > 0) {
                    size = size * 1024 * 1024 * 1024; // user specify size in GB
                } else {
                    throw new InvalidParameterValueException("Disk size must be larger than 0");
                }
            }

            // Check that the the disk offering is specified
            diskOffering = _diskOfferingDao.findById(diskOfferingId);
            if ((diskOffering == null) || diskOffering.getRemoved() != null || !DiskOfferingVO.Type.Disk.equals(diskOffering.getType())) {
                throw new InvalidParameterValueException("Please specify a valid disk offering.");
            }

            if (diskOffering.isCustomized()) {
                if (size == null) {
                    throw new InvalidParameterValueException("This disk offering requires a custom size specified");
                }
                Long customDiskOfferingMaxSize = _volumeMgr.CustomDiskOfferingMaxSize.value();
                Long customDiskOfferingMinSize = _volumeMgr.CustomDiskOfferingMinSize.value();

                if ((sizeInGB < customDiskOfferingMinSize) || (sizeInGB > customDiskOfferingMaxSize)) {
                    throw new InvalidParameterValueException("Volume size: " + sizeInGB + "GB is out of allowed range. Max: " + customDiskOfferingMaxSize + " Min:"
                            + customDiskOfferingMinSize);
                }
            }

            if (!diskOffering.isCustomized() && size != null) {
                throw new InvalidParameterValueException("This disk offering does not allow custom size");
            }

            if (diskOffering.getDomainId() == null) {
                // do nothing as offering is public
            } else {
                _configMgr.checkDiskOfferingAccess(caller, diskOffering);
            }

            if (diskOffering.getDiskSize() > 0) {
                size = diskOffering.getDiskSize();
            }

            Boolean isCustomizedIops = diskOffering.isCustomizedIops();

            if (isCustomizedIops != null) {
                if (isCustomizedIops) {
                    minIops = cmd.getMinIops();
                    maxIops = cmd.getMaxIops();

                    if (minIops == null && maxIops == null) {
                        minIops = 0L;
                        maxIops = 0L;
                    } else {
                        if (minIops == null || minIops <= 0) {
                            throw new InvalidParameterValueException("The min IOPS must be greater than 0.");
                        }

                        if (maxIops == null) {
                            maxIops = 0L;
                        }

                        if (minIops > maxIops) {
                            throw new InvalidParameterValueException("The min IOPS must be less than or equal to the max IOPS.");
                        }
                    }
                } else {
                    minIops = diskOffering.getMinIops();
                    maxIops = diskOffering.getMaxIops();
                }
            }

            if (!validateVolumeSizeRange(size)) {// convert size from mb to gb
                // for validation
                throw new InvalidParameterValueException("Invalid size for custom volume creation: " + size + " ,max volume size is:" + _maxVolumeSizeInGb);
            }
        } else { // create volume from snapshot
            Long snapshotId = cmd.getSnapshotId();
            SnapshotVO snapshotCheck = _snapshotDao.findById(snapshotId);
            if (snapshotCheck == null) {
                throw new InvalidParameterValueException("unable to find a snapshot with id " + snapshotId);
            }

            if (snapshotCheck.getState() != Snapshot.State.BackedUp) {
                throw new InvalidParameterValueException("Snapshot id=" + snapshotId + " is not in " + Snapshot.State.BackedUp + " state yet and can't be used for volume creation");
            }
            parentVolume = _volsDao.findByIdIncludingRemoved(snapshotCheck.getVolumeId());

            diskOfferingId = snapshotCheck.getDiskOfferingId();
            diskOffering = _diskOfferingDao.findById(diskOfferingId);
            if (zoneId == null) {
                // if zoneId is not provided, we default to create volume in the same zone as the snapshot zone.
                zoneId = snapshotCheck.getDataCenterId();
            }
            size = snapshotCheck.getSize(); // ; disk offering is used for tags
            // purposes

            // check snapshot permissions
            _accountMgr.checkAccess(caller, null, true, snapshotCheck);

            // one step operation - create volume in VM's cluster and attach it
            // to the VM
            Long vmId = cmd.getVirtualMachineId();
            if (vmId != null) {
                // Check that the virtual machine ID is valid and it's a user vm
                UserVmVO vm = _userVmDao.findById(vmId);
                if (vm == null || vm.getType() != VirtualMachine.Type.User) {
                    throw new InvalidParameterValueException("Please specify a valid User VM.");
                }

                // Check that the VM is in the correct state
                if (vm.getState() != State.Running && vm.getState() != State.Stopped) {
                    throw new InvalidParameterValueException("Please specify a VM that is either running or stopped.");
                }

                // permission check
                _accountMgr.checkAccess(caller, null, false, vm);
            }

        }

        // Check that the resource limit for primary storage won't be exceeded
        _resourceLimitMgr.checkResourceLimit(_accountMgr.getAccount(ownerId), ResourceType.primary_storage, displayVolume, new Long(size));

        // Verify that zone exists
        DataCenterVO zone = _dcDao.findById(zoneId);
        if (zone == null) {
            throw new InvalidParameterValueException("Unable to find zone by id " + zoneId);
        }

        // Check if zone is disabled
        if (Grouping.AllocationState.Disabled == zone.getAllocationState() && !_accountMgr.isRootAdmin(caller.getType())) {
            throw new PermissionDeniedException("Cannot perform this operation, Zone is currently disabled: " + zoneId);
        }

        // If local storage is disabled then creation of volume with local disk
        // offering not allowed
        if (!zone.isLocalStorageEnabled() && diskOffering.getUseLocalStorage()) {
            throw new InvalidParameterValueException("Zone is not configured to use local storage but volume's disk offering " + diskOffering.getName() + " uses it");
        }

        String userSpecifiedName = cmd.getVolumeName();
        if (userSpecifiedName == null) {
            userSpecifiedName = getRandomVolumeName();
        }

        VolumeVO volume = commitVolume(cmd, caller, ownerId, displayVolume, zoneId, diskOfferingId, size, minIops, maxIops, parentVolume, userSpecifiedName,
                _uuidMgr.generateUuid(Volume.class, cmd.getCustomId()));

        return volume;
    }

    private VolumeVO commitVolume(final CreateVolumeCmd cmd, final Account caller, final long ownerId, final Boolean displayVolume, final Long zoneId,
            final Long diskOfferingId, final Long size, final Long minIops, final Long maxIops, final VolumeVO parentVolume, final String userSpecifiedName, final String uuid) {
        return Transaction.execute(new TransactionCallback<VolumeVO>() {
            @Override
            public VolumeVO doInTransaction(TransactionStatus status) {
                VolumeVO volume = new VolumeVO(userSpecifiedName, -1, -1, -1, -1, new Long(-1), null, null, 0, Volume.Type.DATADISK);
                volume.setPoolId(null);
                volume.setUuid(uuid);
                volume.setDataCenterId(zoneId);
                volume.setPodId(null);
                volume.setAccountId(ownerId);
                volume.setDomainId(((caller == null) ? Domain.ROOT_DOMAIN : caller.getDomainId()));
                volume.setDiskOfferingId(diskOfferingId);
                volume.setSize(size);
                volume.setMinIops(minIops);
                volume.setMaxIops(maxIops);
                volume.setInstanceId(null);
                volume.setUpdated(new Date());
                volume.setDomainId((caller == null) ? Domain.ROOT_DOMAIN : caller.getDomainId());
                volume.setDisplayVolume(displayVolume);
                if (parentVolume != null) {
                    volume.setTemplateId(parentVolume.getTemplateId());
                    volume.setFormat(parentVolume.getFormat());
                } else {
                    volume.setTemplateId(null);
                }

                volume = _volsDao.persist(volume);
                if (cmd.getSnapshotId() == null) {
                    // for volume created from snapshot, create usage event after volume creation
                    UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VOLUME_CREATE, volume.getAccountId(), volume.getDataCenterId(), volume.getId(), volume.getName(),
                            diskOfferingId, null, size, Volume.class.getName(), volume.getUuid());
                }

                CallContext.current().setEventDetails("Volume Id: " + volume.getId());

                // Increment resource count during allocation; if actual creation fails,
                // decrement it
                _resourceLimitMgr.incrementResourceCount(volume.getAccountId(), ResourceType.volume, displayVolume);
                _resourceLimitMgr.incrementResourceCount(volume.getAccountId(), ResourceType.primary_storage, displayVolume, new Long(volume.getSize()));
                return volume;
            }
        });
    }

    public boolean validateVolumeSizeRange(long size) {
        if (size < 0 || (size > 0 && size < (1024 * 1024 * 1024))) {
            throw new InvalidParameterValueException("Please specify a size of at least 1 Gb.");
        } else if (size > (_maxVolumeSizeInGb * 1024 * 1024 * 1024)) {
            throw new InvalidParameterValueException("volume size " + size + ", but the maximum size allowed is " + _maxVolumeSizeInGb + " Gb.");
        }

        return true;
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_VOLUME_CREATE, eventDescription = "creating volume", async = true)
    public VolumeVO createVolume(CreateVolumeCmd cmd) {
        VolumeVO volume = _volsDao.findById(cmd.getEntityId());
        boolean created = true;

        try {
            if (cmd.getSnapshotId() != null) {
                volume = createVolumeFromSnapshot(volume, cmd.getSnapshotId(), cmd.getVirtualMachineId());
                if (volume.getState() != Volume.State.Ready) {
                    created = false;
                }

                // if VM Id is provided, attach the volume to the VM
                if (cmd.getVirtualMachineId() != null) {
                    try {
                        attachVolumeToVM(cmd.getVirtualMachineId(), volume.getId(), volume.getDeviceId());
                    } catch (Exception ex) {
                        StringBuilder message = new StringBuilder("Volume: ");
                        message.append(volume.getUuid());
                        message.append(" created successfully, but failed to attach the newly created volume to VM: ");
                        message.append(cmd.getVirtualMachineId());
                        message.append(" due to error: ");
                        message.append(ex.getMessage());
                        if (s_logger.isDebugEnabled()) {
                            s_logger.debug(message, ex);
                        }
                        throw new CloudRuntimeException(message.toString());
                    }
                }
            }
            return volume;
        } catch (Exception e) {
            created = false;
            s_logger.debug("Failed to create volume: " + volume.getId(), e);
            return null;
        } finally {
            if (!created) {
                s_logger.trace("Decrementing volume resource count for account id=" + volume.getAccountId() + " as volume failed to create on the backend");
                _resourceLimitMgr.decrementResourceCount(volume.getAccountId(), ResourceType.volume, cmd.getDisplayVolume());
                _resourceLimitMgr.recalculateResourceCount(volume.getAccountId(), volume.getDomainId(), ResourceType.primary_storage.getOrdinal());
            }
        }
    }

    protected VolumeVO createVolumeFromSnapshot(VolumeVO volume, long snapshotId, Long vmId) throws StorageUnavailableException {
        VolumeInfo createdVolume = null;
        SnapshotVO snapshot = _snapshotDao.findById(snapshotId);
        long snapshotVolId = snapshot.getVolumeId();

        UserVmVO vm = null;
        if (vmId != null) {
            vm = _userVmDao.findById(vmId);
        }

        // sync old snapshots to region store if necessary

        createdVolume = _volumeMgr.createVolumeFromSnapshot(volume, snapshot, vm);

        UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VOLUME_CREATE, createdVolume.getAccountId(), createdVolume.getDataCenterId(), createdVolume.getId(),
                createdVolume.getName(), createdVolume.getDiskOfferingId(), null, createdVolume.getSize(), Volume.class.getName(), createdVolume.getUuid());

        return _volsDao.findById(createdVolume.getId());
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_VOLUME_RESIZE, eventDescription = "resizing volume", async = true)
    public VolumeVO resizeVolume(ResizeVolumeCmd cmd) throws ResourceAllocationException {
        Long newSize = null;
        boolean shrinkOk = cmd.getShrinkOk();

        VolumeVO volume = _volsDao.findById(cmd.getEntityId());
        if (volume == null) {
            throw new InvalidParameterValueException("No such volume");
        }

        DiskOfferingVO diskOffering = _diskOfferingDao.findById(volume.getDiskOfferingId());
        DiskOfferingVO newDiskOffering = null;

        newDiskOffering = _diskOfferingDao.findById(cmd.getNewDiskOfferingId());

        /*
         * Volumes with no hypervisor have never been assigned, and can be
         * resized by recreating. perhaps in the future we can just update the
         * db entry for the volume
         */
        if (_volsDao.getHypervisorType(volume.getId()) == HypervisorType.None) {
            throw new InvalidParameterValueException("Can't resize a volume that has never been attached, not sure which hypervisor type. Recreate volume to resize.");
        }

        /* Only works for KVM/Xen for now */
        if (_volsDao.getHypervisorType(volume.getId()) != HypervisorType.KVM && _volsDao.getHypervisorType(volume.getId()) != HypervisorType.XenServer
                && _volsDao.getHypervisorType(volume.getId()) != HypervisorType.VMware) {
            throw new InvalidParameterValueException("Cloudstack currently only supports volumes marked as KVM or XenServer hypervisor for resize");
        }

        if (volume.getState() != Volume.State.Ready) {
            throw new InvalidParameterValueException("Volume should be in ready state before attempting a resize");
        }

        if (!volume.getVolumeType().equals(Volume.Type.DATADISK)) {
            throw new InvalidParameterValueException("Can only resize DATA volumes");
        }

        /*
         * figure out whether or not a new disk offering or size parameter is
         * required, get the correct size value
         */
        if (newDiskOffering == null) {
            if (diskOffering.isCustomized()) {
                newSize = cmd.getSize();

                if (newSize == null) {
                    throw new InvalidParameterValueException("new offering is of custom size, need to specify a size");
                }

                newSize = (newSize << 30);
            } else {
                throw new InvalidParameterValueException("current offering" + volume.getDiskOfferingId() + " cannot be resized, need to specify a disk offering");
            }
        } else {

            if (newDiskOffering.getRemoved() != null || !DiskOfferingVO.Type.Disk.equals(newDiskOffering.getType())) {
                throw new InvalidParameterValueException("Disk offering ID is missing or invalid");
            }

            if (diskOffering.getTags() != null) {
                if (newDiskOffering.getTags() == null || !newDiskOffering.getTags().equals(diskOffering.getTags())) {
                    throw new InvalidParameterValueException("Tags on new and old disk offerings must match");
                }
            } else if (newDiskOffering.getTags() != null) {
                throw new InvalidParameterValueException("There are no tags on current disk offering, new disk offering needs to have no tags");
            }

            if (newDiskOffering.getDomainId() == null) {
                // do nothing as offering is public
            } else {
                _configMgr.checkDiskOfferingAccess(CallContext.current().getCallingAccount(), newDiskOffering);
            }

            if (newDiskOffering.isCustomized()) {
                newSize = cmd.getSize();

                if (newSize == null) {
                    throw new InvalidParameterValueException("new offering is of custom size, need to specify a size");
                }

                newSize = (newSize << 30);
            } else {
                newSize = newDiskOffering.getDiskSize();
            }
        }

        if (newSize == null) {
            throw new InvalidParameterValueException("could not detect a size parameter or fetch one from the diskofferingid parameter");
        }

        if (!validateVolumeSizeRange(newSize)) {
            throw new InvalidParameterValueException("Requested size out of range");
        }

        /* does the caller have the authority to act on this volume? */
        _accountMgr.checkAccess(CallContext.current().getCallingAccount(), null, true, volume);

        UserVmVO userVm = _userVmDao.findById(volume.getInstanceId());

        long currentSize = volume.getSize();

        /*
         * lets make certain they (think they) know what they're doing if they
         * want to shrink, by forcing them to provide the shrinkok parameter.
         * This will be checked again at the hypervisor level where we can see
         * the actual disk size
         */
        if (currentSize > newSize && !shrinkOk) {
            throw new InvalidParameterValueException("Going from existing size of " + currentSize + " to size of " + newSize
                    + " would shrink the volume, need to sign off by supplying the shrinkok parameter with value of true");
        }

        if (!shrinkOk) {
            /* Check resource limit for this account on primary storage resource */
            _resourceLimitMgr.checkResourceLimit(_accountMgr.getAccount(volume.getAccountId()), ResourceType.primary_storage, volume.isDisplayVolume(), new Long(newSize
                    - currentSize));
        }

        if (userVm != null) {
            // serialize VM operation
            AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
            if (!VmJobEnabled.value() || jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
                // avoid re-entrance

                VmWorkJobVO placeHolder = null;
                if (VmJobEnabled.value()) {
                    placeHolder = createPlaceHolderWork(userVm.getId());
                }
                try {
                    return orchestrateResizeVolume(volume.getId(), currentSize, newSize,
                            newDiskOffering != null ? cmd.getNewDiskOfferingId() : null, shrinkOk);
                } finally {
                    if (VmJobEnabled.value())
                        _workJobDao.expunge(placeHolder.getId());
                }

            } else {
                Outcome<Volume> outcome = resizeVolumeThroughJobQueue(userVm.getId(), volume.getId(), currentSize, newSize,
                        newDiskOffering != null ? cmd.getNewDiskOfferingId() : null, shrinkOk);

                Volume vol = null;
                try {
                    outcome.get();
                } catch (InterruptedException e) {
                    throw new RuntimeException("Operation is interrupted", e);
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new RuntimeException("Execution excetion", e);
                }

                Object jobResult = _jobMgr.unmarshallResultObject(outcome.getJob());
                if (jobResult != null) {
                    if (jobResult instanceof ConcurrentOperationException)
                        throw (ConcurrentOperationException)jobResult;
                    else if (jobResult instanceof Throwable)
                        throw new RuntimeException("Unexpected exception", (Throwable)jobResult);
                    else if (jobResult instanceof Long) {
                        vol = _volumeDao.findById((Long)jobResult);
                    }
                }
                return volume;
            }
        }
        return orchestrateResizeVolume(volume.getId(), currentSize, newSize,
                newDiskOffering != null ? cmd.getNewDiskOfferingId() : null, shrinkOk);
    }

    private VolumeVO orchestrateResizeVolume(long volumeId, long currentSize, long newSize, Long newDiskOfferingId, boolean shrinkOk) {
        VolumeVO volume = _volsDao.findById(volumeId);
        UserVmVO userVm = _userVmDao.findById(volume.getInstanceId());
        /*
         * get a list of hosts to send the commands to, try the system the
         * associated vm is running on first, then the last known place it ran.
         * If not attached to a userVm, we pass 'none' and resizevolume.sh is ok
         * with that since it only needs the vm name to live resize
         */
        long[] hosts = null;
        String instanceName = "none";
        if (userVm != null) {
            instanceName = userVm.getInstanceName();
            if (userVm.getHostId() != null) {
                hosts = new long[] {userVm.getHostId()};
            } else if (userVm.getLastHostId() != null) {
                hosts = new long[] {userVm.getLastHostId()};
            }

            /* Xen only works offline, SR does not support VDI.resizeOnline */
            if (_volsDao.getHypervisorType(volume.getId()) == HypervisorType.XenServer && !userVm.getState().equals(State.Stopped)) {
                throw new InvalidParameterValueException("VM must be stopped or disk detached in order to resize with the Xen HV");
            }
        }

        ResizeVolumePayload payload = new ResizeVolumePayload(newSize, shrinkOk, instanceName, hosts);

        try {
            VolumeInfo vol = volFactory.getVolume(volume.getId());
            vol.addPayload(payload);

            AsyncCallFuture<VolumeApiResult> future = volService.resize(vol);
            VolumeApiResult result = future.get();
            if (result.isFailed()) {
                s_logger.warn("Failed to resize the volume " + volume);
                return null;
            }

            volume = _volsDao.findById(volume.getId());

            if (newDiskOfferingId != null) {
                volume.setDiskOfferingId(newDiskOfferingId);
            }
            _volsDao.update(volume.getId(), volume);
            // Log usage event for volumes belonging user VM's only
            UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VOLUME_RESIZE, volume.getAccountId(), volume.getDataCenterId(), volume.getId(), volume.getName(),
                    volume.getDiskOfferingId(), volume.getTemplateId(), volume.getSize(), Volume.class.getName(), volume.getUuid());

            /* Update resource count for the account on primary storage resource */
            if (!shrinkOk) {
                _resourceLimitMgr.incrementResourceCount(volume.getAccountId(), ResourceType.primary_storage, volume.isDisplayVolume(), new Long(newSize - currentSize));
            } else {
                _resourceLimitMgr.decrementResourceCount(volume.getAccountId(), ResourceType.primary_storage, volume.isDisplayVolume(), new Long(currentSize - newSize));
            }
            return volume;
        } catch (InterruptedException e) {
            s_logger.warn("failed get resize volume result", e);
        } catch (ExecutionException e) {
            s_logger.warn("failed get resize volume result", e);
        } catch (Exception e) {
            s_logger.warn("failed get resize volume result", e);
        }

        return null;
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_VOLUME_DELETE, eventDescription = "deleting volume")
    public boolean deleteVolume(long volumeId, Account caller) throws ConcurrentOperationException {

        VolumeVO volume = _volsDao.findById(volumeId);
        if (volume == null) {
            throw new InvalidParameterValueException("Unable to aquire volume with ID: " + volumeId);
        }

        if (!_snapshotMgr.canOperateOnVolume(volume)) {
            throw new InvalidParameterValueException("There are snapshot creating on it, Unable to delete the volume");
        }

        _accountMgr.checkAccess(caller, null, true, volume);

        if (volume.getInstanceId() != null) {
            throw new InvalidParameterValueException("Please specify a volume that is not attached to any VM.");
        }

        if (volume.getState() == Volume.State.UploadOp) {
            VolumeDataStoreVO volumeStore = _volumeStoreDao.findByVolume(volume.getId());
            if (volumeStore.getDownloadState() == VMTemplateStorageResourceAssoc.Status.DOWNLOAD_IN_PROGRESS) {
                throw new InvalidParameterValueException("Please specify a volume that is not uploading");
            }
        }

        try {
            if (volume.getState() != Volume.State.Destroy && volume.getState() != Volume.State.Expunging && volume.getState() != Volume.State.Expunging) {
                Long instanceId = volume.getInstanceId();
                if (!volService.destroyVolume(volume.getId())) {
                    return false;
                }

                VMInstanceVO vmInstance = _vmInstanceDao.findById(instanceId);
                if (instanceId == null || (vmInstance.getType().equals(VirtualMachine.Type.User))) {
                    // Decrement the resource count for volumes and primary storage belonging user VM's only
                    _resourceLimitMgr.decrementResourceCount(volume.getAccountId(), ResourceType.volume, volume.isDisplayVolume());
                    /* If volume is in primary storage, decrement primary storage count else decrement secondary
                     storage count (in case of upload volume). */
                    if (volume.getFolder() != null || volume.getPath() != null || volume.getState() == Volume.State.Allocated) {
                        _resourceLimitMgr.recalculateResourceCount(volume.getAccountId(), volume.getDomainId(), ResourceType.primary_storage.getOrdinal());
                    } else {
                        _resourceLimitMgr.recalculateResourceCount(volume.getAccountId(), volume.getDomainId(), ResourceType.secondary_storage.getOrdinal());
                    }

                    // Log usage event for volumes belonging user VM's only
                    UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VOLUME_DELETE, volume.getAccountId(), volume.getDataCenterId(), volume.getId(), volume.getName(),
                            Volume.class.getName(), volume.getUuid());
                }
            }
            // Mark volume as removed if volume has not been created on primary or secondary
            if (volume.getState() == Volume.State.Allocated) {
                _volsDao.remove(volumeId);
                stateTransitTo(volume, Volume.Event.DestroyRequested);
                return true;
            }
            // expunge volume from primary if volume is on primary
            VolumeInfo volOnPrimary = volFactory.getVolume(volume.getId(), DataStoreRole.Primary);
            if (volOnPrimary != null) {
                s_logger.info("Expunging volume " + volume.getId() + " from primary data store");
                AsyncCallFuture<VolumeApiResult> future = volService.expungeVolumeAsync(volOnPrimary);
                future.get();
            }
            // expunge volume from secondary if volume is on image store
            VolumeInfo volOnSecondary = volFactory.getVolume(volume.getId(), DataStoreRole.Image);
            if (volOnSecondary != null) {
                s_logger.info("Expunging volume " + volume.getId() + " from secondary data store");
                AsyncCallFuture<VolumeApiResult> future2 = volService.expungeVolumeAsync(volOnSecondary);
                future2.get();
            }
            // delete all cache entries for this volume
            List<VolumeInfo> cacheVols = volFactory.listVolumeOnCache(volume.getId());
            for (VolumeInfo volOnCache : cacheVols) {
                s_logger.info("Delete volume from image cache store: " + volOnCache.getDataStore().getName());
                volOnCache.delete();
            }

        } catch (Exception e) {
            s_logger.warn("Failed to expunge volume:", e);
            return false;
        }

        return true;
    }

    private boolean stateTransitTo(Volume vol, Volume.Event event) throws NoTransitionException {
        return _volStateMachine.transitTo(vol, event, null, _volsDao);
    }

    @Override
    public Volume attachVolumeToVM(AttachVolumeCmd command) {

        AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (!VmJobEnabled.value() || jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            // avoid re-entrance

            VmWorkJobVO placeHolder = null;
            if (VmJobEnabled.value()) {
                placeHolder = createPlaceHolderWork(command.getVirtualMachineId());
            }
            try {
                return orchestrateAttachVolumeToVM(command.getVirtualMachineId(), command.getId(), command.getDeviceId());
            } finally {
                if (VmJobEnabled.value())
                    _workJobDao.expunge(placeHolder.getId());
            }

        } else {
            Outcome<Volume> outcome = attachVolumeToVmThroughJobQueue(command.getVirtualMachineId(), command.getId(), command.getDeviceId());

            Volume vol = null;
            try {
                outcome.get();
            } catch (InterruptedException e) {
                throw new RuntimeException("Operation is interrupted", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new RuntimeException("Execution excetion", e);
            }

            Object jobResult = _jobMgr.unmarshallResultObject(outcome.getJob());
            if (jobResult != null) {
                if (jobResult instanceof ConcurrentOperationException)
                    throw (ConcurrentOperationException)jobResult;
                else if (jobResult instanceof Throwable)
                    throw new RuntimeException("Unexpected exception", (Throwable)jobResult);
                else if (jobResult instanceof Long) {
                    vol = _volumeDao.findById((Long)jobResult);
                }
            }
            return vol;
        }
    }

    private Volume orchestrateAttachVolumeToVM(Long vmId, Long volumeId, Long deviceId) {
        return attachVolumeToVM(vmId, volumeId, deviceId);
    }

    @ActionEvent(eventType = EventTypes.EVENT_VOLUME_ATTACH, eventDescription = "attaching volume", async = true)
    public Volume attachVolumeToVM(Long vmId, Long volumeId, Long deviceId) {
        Account caller = CallContext.current().getCallingAccount();

        // Check that the volume ID is valid
        VolumeInfo volume = volFactory.getVolume(volumeId);
        // Check that the volume is a data volume
        if (volume == null || volume.getVolumeType() != Volume.Type.DATADISK) {
            throw new InvalidParameterValueException("Please specify a valid data volume.");
        }

        // Check that the volume is not currently attached to any VM
        if (volume.getInstanceId() != null) {
            throw new InvalidParameterValueException("Please specify a volume that is not attached to any VM.");
        }

        // Check that the volume is not destroyed
        if (volume.getState() == Volume.State.Destroy) {
            throw new InvalidParameterValueException("Please specify a volume that is not destroyed.");
        }

        // Check that the virtual machine ID is valid and it's a user vm
        UserVmVO vm = _userVmDao.findById(vmId);
        if (vm == null || vm.getType() != VirtualMachine.Type.User) {
            throw new InvalidParameterValueException("Please specify a valid User VM.");
        }

        // Check that the VM is in the correct state
        if (vm.getState() != State.Running && vm.getState() != State.Stopped) {
            throw new InvalidParameterValueException("Please specify a VM that is either running or stopped.");
        }

        // Check that the device ID is valid
        if (deviceId != null) {
            if (deviceId.longValue() == 0) {
                throw new InvalidParameterValueException("deviceId can't be 0, which is used by Root device");
            }
        }

        // Check that the number of data volumes attached to VM is less than
        // that supported by hypervisor
        List<VolumeVO> existingDataVolumes = _volsDao.findByInstanceAndType(vmId, Volume.Type.DATADISK);
        int maxDataVolumesSupported = getMaxDataVolumesSupported(vm);
        if (existingDataVolumes.size() >= maxDataVolumesSupported) {
            throw new InvalidParameterValueException("The specified VM already has the maximum number of data disks (" + maxDataVolumesSupported + "). Please specify another VM.");
        }

        // Check that the VM and the volume are in the same zone
        if (vm.getDataCenterId() != volume.getDataCenterId()) {
            throw new InvalidParameterValueException("Please specify a VM that is in the same zone as the volume.");
        }

        // If local storage is disabled then attaching a volume with local disk
        // offering not allowed
        DataCenterVO dataCenter = _dcDao.findById(volume.getDataCenterId());
        if (!dataCenter.isLocalStorageEnabled()) {
            DiskOfferingVO diskOffering = _diskOfferingDao.findById(volume.getDiskOfferingId());
            if (diskOffering.getUseLocalStorage()) {
                throw new InvalidParameterValueException("Zone is not configured to use local storage but volume's disk offering " + diskOffering.getName() + " uses it");
            }
        }

        // if target VM has associated VM snapshots
        List<VMSnapshotVO> vmSnapshots = _vmSnapshotDao.findByVm(vmId);
        if (vmSnapshots.size() > 0) {
            throw new InvalidParameterValueException("Unable to attach volume, please specify a VM that does not have VM snapshots");
        }

        // permission check
        _accountMgr.checkAccess(caller, null, true, volume, vm);

        if (!(Volume.State.Allocated.equals(volume.getState()) || Volume.State.Ready.equals(volume.getState()) || Volume.State.Uploaded.equals(volume.getState()))) {
            throw new InvalidParameterValueException("Volume state must be in Allocated, Ready or in Uploaded state");
        }

        VolumeVO rootVolumeOfVm = null;
        List<VolumeVO> rootVolumesOfVm = _volsDao.findByInstanceAndType(vmId, Volume.Type.ROOT);
        if (rootVolumesOfVm.size() != 1) {
            throw new CloudRuntimeException("The VM " + vm.getHostName() + " has more than one ROOT volume and is in an invalid state.");
        } else {
            rootVolumeOfVm = rootVolumesOfVm.get(0);
        }

        HypervisorType rootDiskHyperType = vm.getHypervisorType();

        HypervisorType dataDiskHyperType = _volsDao.getHypervisorType(volume.getId());

        VolumeVO dataDiskVol = _volsDao.findById(volume.getId());
        StoragePoolVO dataDiskStoragePool = _storagePoolDao.findById(dataDiskVol.getPoolId());

        // managed storage can be used for different types of hypervisors
        // only perform this check if the volume's storage pool is not null and not managed
        if (dataDiskStoragePool != null && !dataDiskStoragePool.isManaged()) {
            if (dataDiskHyperType != HypervisorType.None && rootDiskHyperType != dataDiskHyperType) {
                throw new InvalidParameterValueException("Can't attach a volume created by: " + dataDiskHyperType + " to a " + rootDiskHyperType + " vm");
            }
        }

        deviceId = getDeviceId(vmId, deviceId);
        VolumeInfo volumeOnPrimaryStorage = volume;
        //don't create volume on primary storage if its being attached to the vm which Root's volume hasn't been created yet
        List<VolumeVO> existingRootVolumes = _volsDao.findByInstanceAndType(vmId, Volume.Type.ROOT);
        boolean rootVolumeCreatedOnPrimary = true;
        if (existingRootVolumes.get(0).getState().equals(Volume.State.Allocated)) {
            rootVolumeCreatedOnPrimary = false;
        }

        if ((volume.getState().equals(Volume.State.Allocated) && rootVolumeCreatedOnPrimary) || volume.getState() == Volume.State.Uploaded) {
            try {
                volumeOnPrimaryStorage = _volumeMgr.createVolumeOnPrimaryStorage(vm, rootVolumeOfVm, volume, rootDiskHyperType);
            } catch (NoTransitionException e) {
                s_logger.debug("Failed to create volume on primary storage", e);
                throw new CloudRuntimeException("Failed to create volume on primary storage", e);
            }
        }

        // reload the volume from db
        volumeOnPrimaryStorage = volFactory.getVolume(volumeOnPrimaryStorage.getId());
        boolean moveVolumeNeeded = needMoveVolume(rootVolumeOfVm, volumeOnPrimaryStorage);

        if (moveVolumeNeeded) {
            PrimaryDataStoreInfo primaryStore = (PrimaryDataStoreInfo)volumeOnPrimaryStorage.getDataStore();
            if (primaryStore.isLocal()) {
                throw new CloudRuntimeException("Failed to attach local data volume " + volume.getName() + " to VM " + vm.getDisplayName()
                        + " as migration of local data volume is not allowed");
            }
            StoragePoolVO vmRootVolumePool = _storagePoolDao.findById(rootVolumeOfVm.getPoolId());

            try {
                volumeOnPrimaryStorage = _volumeMgr.moveVolume(volumeOnPrimaryStorage, vmRootVolumePool.getDataCenterId(), vmRootVolumePool.getPodId(),
                        vmRootVolumePool.getClusterId(), dataDiskHyperType);
            } catch (ConcurrentOperationException e) {
                s_logger.debug("move volume failed", e);
                throw new CloudRuntimeException("move volume failed", e);
            } catch (StorageUnavailableException e) {
                s_logger.debug("move volume failed", e);
                throw new CloudRuntimeException("move volume failed", e);
            }
        }

        AsyncJobExecutionContext asyncExecutionContext = AsyncJobExecutionContext.getCurrentExecutionContext();

        if (asyncExecutionContext != null) {
            AsyncJob job = asyncExecutionContext.getJob();

            if (s_logger.isInfoEnabled()) {
                s_logger.info("Trying to attaching volume " + volumeId + " to vm instance:" + vm.getId() + ", update async job-" + job.getId() + " progress status");
            }

            _asyncMgr.updateAsyncJobAttachment(job.getId(), "volume", volumeId);
        }

        VolumeVO newVol = _volumeDao.findById(volumeOnPrimaryStorage.getId());
        newVol = sendAttachVolumeCommand(vm, newVol, deviceId);
        return newVol;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VOLUME_UPDATE, eventDescription = "updating volume", async = true)
    public Volume updateVolume(long volumeId, String path, String state, Long storageId, Boolean displayVolume, String customId, long entityOwnerId, String chainInfo) {

        VolumeVO volume = _volumeDao.findById(volumeId);

        if (path != null) {
            volume.setPath(path);
        }

        if (displayVolume != null) {
            volume.setDisplayVolume(displayVolume);
        }

        if(chainInfo != null){
            volume.setChainInfo(chainInfo);
        }

        if (state != null) {
            try {
                Volume.State volumeState = Volume.State.valueOf(state);
                volume.setState(volumeState);
            } catch (IllegalArgumentException ex) {
                throw new InvalidParameterValueException("Invalid volume state specified");
            }
        }

        if (storageId != null) {
            StoragePool pool = _storagePoolDao.findById(storageId);
            if (pool.getDataCenterId() != volume.getDataCenterId()) {
                throw new InvalidParameterValueException("Invalid storageId specified; refers to the pool outside of the volume's zone");
            }
            volume.setPoolId(pool.getId());
        }

        if (customId != null) {
            volume.setUuid(customId);
        }

        if (displayVolume != null && displayVolume != volume.isDisplayVolume()) { // No need to check permissions since only Admin allowed to call this API.
            volume.setDisplayVolume(displayVolume);
            _resourceLimitMgr.changeResourceCount(entityOwnerId, ResourceType.volume, displayVolume);
            _resourceLimitMgr.changeResourceCount(entityOwnerId, ResourceType.primary_storage, displayVolume, new Long(volume.getSize()));
        }

        _volumeDao.update(volumeId, volume);

        return volume;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VOLUME_DETACH, eventDescription = "detaching volume", async = true)
    public Volume detachVolumeFromVM(DetachVolumeCmd cmmd) {
        Account caller = CallContext.current().getCallingAccount();
        if ((cmmd.getId() == null && cmmd.getDeviceId() == null && cmmd.getVirtualMachineId() == null)
                || (cmmd.getId() != null && (cmmd.getDeviceId() != null || cmmd.getVirtualMachineId() != null))
                || (cmmd.getId() == null && (cmmd.getDeviceId() == null || cmmd.getVirtualMachineId() == null))) {
            throw new InvalidParameterValueException("Please provide either a volume id, or a tuple(device id, instance id)");
        }

        Long volumeId = cmmd.getId();
        VolumeVO volume = null;

        if (volumeId != null) {
            volume = _volsDao.findById(volumeId);
        } else {
            volume = _volsDao.findByInstanceAndDeviceId(cmmd.getVirtualMachineId(), cmmd.getDeviceId()).get(0);
        }

        // Check that the volume ID is valid
        if (volume == null) {
            throw new InvalidParameterValueException("Unable to find volume with ID: " + volumeId);
        }

        Long vmId = null;

        if (cmmd.getVirtualMachineId() == null) {
            vmId = volume.getInstanceId();
        } else {
            vmId = cmmd.getVirtualMachineId();
        }

        // Permissions check
        _accountMgr.checkAccess(caller, null, true, volume);

        // Check that the volume is a data volume
        if (volume.getVolumeType() != Volume.Type.DATADISK) {
            throw new InvalidParameterValueException("Please specify a data volume.");
        }

        // Check that the volume is currently attached to a VM
        if (vmId == null) {
            throw new InvalidParameterValueException("The specified volume is not attached to a VM.");
        }

        // Check that the VM is in the correct state
        UserVmVO vm = _userVmDao.findById(vmId);
        if (vm.getState() != State.Running && vm.getState() != State.Stopped && vm.getState() != State.Destroyed) {
            throw new InvalidParameterValueException("Please specify a VM that is either running or stopped.");
        }

        // Don't allow detach if target VM has associated VM snapshots
        List<VMSnapshotVO> vmSnapshots = _vmSnapshotDao.findByVm(vmId);
        if (vmSnapshots.size() > 0) {
            throw new InvalidParameterValueException("Unable to detach volume, please specify a VM that does not have VM snapshots");
        }

        AsyncJobExecutionContext asyncExecutionContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (asyncExecutionContext != null) {
            AsyncJob job = asyncExecutionContext.getJob();

            if (s_logger.isInfoEnabled()) {
                s_logger.info("Trying to attaching volume " + volumeId + "to vm instance:" + vm.getId() + ", update async job-" + job.getId() + " progress status");
            }

            _asyncMgr.updateAsyncJobAttachment(job.getId(), "volume", volumeId);
        }

        AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (!VmJobEnabled.value() || jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            // avoid re-entrance
            VmWorkJobVO placeHolder = null;
            if (VmJobEnabled.value()) {
                placeHolder = createPlaceHolderWork(vmId);
            }
            try {
                return orchestrateDetachVolumeFromVM(vmId, volumeId);
            } finally {
                if (VmJobEnabled.value())
                    _workJobDao.expunge(placeHolder.getId());
            }
        } else {
            Outcome<Volume> outcome = detachVolumeFromVmThroughJobQueue(vmId, volumeId);

            Volume vol = null;
            try {
                outcome.get();
            } catch (InterruptedException e) {
                throw new RuntimeException("Operation is interrupted", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new RuntimeException("Execution excetion", e);
            }

            Object jobResult = _jobMgr.unmarshallResultObject(outcome.getJob());
            if (jobResult != null) {
                if (jobResult instanceof ConcurrentOperationException)
                    throw (ConcurrentOperationException)jobResult;
                else if (jobResult instanceof Throwable)
                    throw new RuntimeException("Unexpected exception", (Throwable)jobResult);
                else if (jobResult instanceof Long) {
                    vol = _volumeDao.findById((Long)jobResult);
                }
            }
            return vol;
        }
    }

    private Volume orchestrateDetachVolumeFromVM(long vmId, long volumeId) {

        Volume volume = _volumeDao.findById(volumeId);
        VMInstanceVO vm = _vmInstanceDao.findById(vmId);

        String errorMsg = "Failed to detach volume " + volume.getName() + " from VM " + vm.getHostName();
        boolean sendCommand = vm.getState() == State.Running;

        Long hostId = vm.getHostId();

        if (hostId == null) {
            hostId = vm.getLastHostId();

            HostVO host = _hostDao.findById(hostId);

            if (host != null && host.getHypervisorType() == HypervisorType.VMware) {
                sendCommand = true;
            }
        }

        HostVO host = null;
        StoragePoolVO volumePool = _storagePoolDao.findById(volume.getPoolId());

        if (hostId != null) {
            host = _hostDao.findById(hostId);

            if (host != null && host.getHypervisorType() == HypervisorType.XenServer && volumePool.isManaged()) {
                sendCommand = true;
            }
        }

        Answer answer = null;

        if (sendCommand) {
            DataTO volTO = volFactory.getVolume(volume.getId()).getTO();
            DiskTO disk = new DiskTO(volTO, volume.getDeviceId(), volume.getPath(), volume.getVolumeType());

            DettachCommand cmd = new DettachCommand(disk, vm.getInstanceName());

            cmd.setManaged(volumePool.isManaged());

            cmd.setStorageHost(volumePool.getHostAddress());
            cmd.setStoragePort(volumePool.getPort());

            cmd.set_iScsiName(volume.get_iScsiName());

            try {
                answer = _agentMgr.send(hostId, cmd);
            } catch (Exception e) {
                throw new CloudRuntimeException(errorMsg + " due to: " + e.getMessage());
            }
        }

        if (!sendCommand || (answer != null && answer.getResult())) {
            // Mark the volume as detached
            _volsDao.detachVolume(volume.getId());

            // volume.getPoolId() should be null if the VM we are attaching the disk to has never been started before
            DataStore dataStore = volume.getPoolId() != null ? dataStoreMgr.getDataStore(volume.getPoolId(), DataStoreRole.Primary) : null;

            volService.disconnectVolumeFromHost(volFactory.getVolume(volume.getId()), host, dataStore);

            return _volsDao.findById(volumeId);
        } else {

            if (answer != null) {
                String details = answer.getDetails();
                if (details != null && !details.isEmpty()) {
                    errorMsg += "; " + details;
                }
            }

            throw new CloudRuntimeException(errorMsg);
        }
    }

    @DB
    @Override
    public Volume migrateVolume(MigrateVolumeCmd cmd) {
        Long volumeId = cmd.getVolumeId();
        Long storagePoolId = cmd.getStoragePoolId();

        VolumeVO vol = _volsDao.findById(volumeId);
        if (vol == null) {
            throw new InvalidParameterValueException("Failed to find the volume id: " + volumeId);
        }

        if (vol.getState() != Volume.State.Ready) {
            throw new InvalidParameterValueException("Volume must be in ready state");
        }

        boolean liveMigrateVolume = false;
        Long instanceId = vol.getInstanceId();
        VMInstanceVO vm = null;
        if (instanceId != null) {
            vm = _vmInstanceDao.findById(instanceId);
        }

        if (vm != null && vm.getState() == State.Running) {
            // Check if the underlying hypervisor supports storage motion.
            Long hostId = vm.getHostId();
            if (hostId != null) {
                HostVO host = _hostDao.findById(hostId);
                HypervisorCapabilitiesVO capabilities = null;
                if (host != null) {
                    capabilities = _hypervisorCapabilitiesDao.findByHypervisorTypeAndVersion(host.getHypervisorType(), host.getHypervisorVersion());
                }

                if (capabilities != null) {
                    liveMigrateVolume = capabilities.isStorageMotionSupported();
                }
            }
        }

        // If the disk is not attached to any VM then it can be moved. Otherwise, it needs to be attached to a vm
        // running on a hypervisor that supports storage motion so that it be be migrated.
        if (instanceId != null && !liveMigrateVolume) {
            throw new InvalidParameterValueException("Volume needs to be detached from VM");
        }

        if (liveMigrateVolume && !cmd.isLiveMigrate()) {
            throw new InvalidParameterValueException("The volume " + vol + "is attached to a vm and for migrating it " + "the parameter livemigrate should be specified");
        }

        StoragePool destPool = (StoragePool)dataStoreMgr.getDataStore(storagePoolId, DataStoreRole.Primary);
        if (destPool == null) {
            throw new InvalidParameterValueException("Failed to find the destination storage pool: " + storagePoolId);
        }

        if (_volumeMgr.volumeOnSharedStoragePool(vol)) {
            if (destPool.isLocal()) {
                throw new InvalidParameterValueException("Migration of volume from shared to local storage pool is not supported");
            }
        } else {
            throw new InvalidParameterValueException("Migration of volume from local storage pool is not supported");
        }

        if (vm != null) {
            // serialize VM operation
            AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
            if (!VmJobEnabled.value() || jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
                // avoid re-entrance

                VmWorkJobVO placeHolder = null;
                if (VmJobEnabled.value()) {
                    placeHolder = createPlaceHolderWork(vm.getId());
                }
                try {
                    return orchestrateMigrateVolume(vol.getId(), destPool.getId(), liveMigrateVolume);
                } finally {
                    if (VmJobEnabled.value())
                        _workJobDao.expunge(placeHolder.getId());
                }

            } else {
                Outcome<Volume> outcome = migrateVolumeThroughJobQueue(vm.getId(), vol.getId(), destPool.getId(), liveMigrateVolume);

                try {
                    outcome.get();
                } catch (InterruptedException e) {
                    throw new RuntimeException("Operation is interrupted", e);
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new RuntimeException("Execution excetion", e);
                }

                Object jobResult = _jobMgr.unmarshallResultObject(outcome.getJob());
                if (jobResult != null) {
                    if (jobResult instanceof ConcurrentOperationException)
                        throw (ConcurrentOperationException)jobResult;
                    else if (jobResult instanceof Throwable)
                        throw new RuntimeException("Unexpected exception", (Throwable)jobResult);
                }

                // retrieve the migrated new volume from job result
                if (jobResult != null && jobResult instanceof Long) {
                    return _entityMgr.findById(VolumeVO.class, ((Long)jobResult).longValue());
                }
                return null;
            }
        }

        return orchestrateMigrateVolume(vol.getId(), destPool.getId(), liveMigrateVolume);
    }

    private Volume orchestrateMigrateVolume(long volumeId, long destPoolId, boolean liveMigrateVolume) {
        VolumeVO vol = _volsDao.findById(volumeId);
        assert (vol != null);
        StoragePool destPool = (StoragePool)dataStoreMgr.getDataStore(destPoolId, DataStoreRole.Primary);
        assert (destPool != null);

        Volume newVol = null;
        if (liveMigrateVolume) {
            newVol = liveMigrateVolume(vol, destPool);
        } else {
            try {
                newVol = _volumeMgr.migrateVolume(vol, destPool);
            } catch (StorageUnavailableException e) {
                s_logger.debug("Failed to migrate volume", e);
            }
        }
        return newVol;
    }

    @DB
    protected Volume liveMigrateVolume(Volume volume, StoragePool destPool) {
        VolumeInfo vol = volFactory.getVolume(volume.getId());
        AsyncCallFuture<VolumeApiResult> future = volService.migrateVolume(vol, (DataStore)destPool);
        try {
            VolumeApiResult result = future.get();
            if (result.isFailed()) {
                s_logger.debug("migrate volume failed:" + result.getResult());
                return null;
            }
            return result.getVolume();
        } catch (InterruptedException e) {
            s_logger.debug("migrate volume failed", e);
            return null;
        } catch (ExecutionException e) {
            s_logger.debug("migrate volume failed", e);
            return null;
        }
    }

    @Override
    public Snapshot takeSnapshot(Long volumeId, Long policyId, Long snapshotId, Account account, boolean quiescevm) throws ResourceAllocationException {

        VolumeInfo volume = volFactory.getVolume(volumeId);
        if (volume == null) {
            throw new InvalidParameterValueException("Creating snapshot failed due to volume:" + volumeId + " doesn't exist");
        }

        if (volume.getState() != Volume.State.Ready) {
            throw new InvalidParameterValueException("VolumeId: " + volumeId + " is not in " + Volume.State.Ready + " state but " + volume.getState() + ". Cannot take snapshot.");
        }

        VMInstanceVO vm = null;
        if (volume.getInstanceId() != null)
            vm = _vmInstanceDao.findById(volume.getInstanceId());

        if (vm != null) {
            // serialize VM operation
            AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
            if (!VmJobEnabled.value() || jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
                // avoid re-entrance

                VmWorkJobVO placeHolder = null;
                if (VmJobEnabled.value()) {
                    placeHolder = createPlaceHolderWork(vm.getId());
                }
                try {
                    return orchestrateTakeVolumeSnapshot(volumeId, policyId, snapshotId, account, quiescevm);
                } finally {
                    if (VmJobEnabled.value())
                        _workJobDao.expunge(placeHolder.getId());
                }

            } else {
                Outcome<Snapshot> outcome = takeVolumeSnapshotThroughJobQueue(vm.getId(), volumeId, policyId, snapshotId, account.getId(), quiescevm);

                try {
                    outcome.get();
                } catch (InterruptedException e) {
                    throw new RuntimeException("Operation is interrupted", e);
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new RuntimeException("Execution excetion", e);
                }

                Object jobResult = _jobMgr.unmarshallResultObject(outcome.getJob());
                if (jobResult != null) {
                    if (jobResult instanceof ConcurrentOperationException)
                        throw (ConcurrentOperationException)jobResult;
                    else if (jobResult instanceof ResourceAllocationException)
                        throw (ResourceAllocationException)jobResult;
                    else if (jobResult instanceof Throwable)
                        throw new RuntimeException("Unexpected exception", (Throwable)jobResult);
                }

                return _snapshotDao.findById(snapshotId);
            }
        } else {
            CreateSnapshotPayload payload = new CreateSnapshotPayload();
            payload.setSnapshotId(snapshotId);
            payload.setSnapshotPolicyId(policyId);
            payload.setAccount(account);
            payload.setQuiescevm(quiescevm);
            volume.addPayload(payload);
            return volService.takeSnapshot(volume);
        }
    }

    private Snapshot orchestrateTakeVolumeSnapshot(Long volumeId, Long policyId, Long snapshotId, Account account, boolean quiescevm)
            throws ResourceAllocationException {

        VolumeInfo volume = volFactory.getVolume(volumeId);

        if (volume == null) {
            throw new InvalidParameterValueException("Creating snapshot failed due to volume:" + volumeId + " doesn't exist");
        }

        if (volume.getState() != Volume.State.Ready) {
            throw new InvalidParameterValueException("VolumeId: " + volumeId + " is not in " + Volume.State.Ready + " state but " + volume.getState() + ". Cannot take snapshot.");
        }

        CreateSnapshotPayload payload = new CreateSnapshotPayload();
        payload.setSnapshotId(snapshotId);
        payload.setSnapshotPolicyId(policyId);
        payload.setAccount(account);
        payload.setQuiescevm(quiescevm);
        volume.addPayload(payload);
        return volService.takeSnapshot(volume);
    }

    @Override
    public Snapshot allocSnapshot(Long volumeId, Long policyId) throws ResourceAllocationException {
        Account caller = CallContext.current().getCallingAccount();

        VolumeInfo volume = volFactory.getVolume(volumeId);
        if (volume == null) {
            throw new InvalidParameterValueException("Creating snapshot failed due to volume:" + volumeId + " doesn't exist");
        }
        DataCenter zone = _dcDao.findById(volume.getDataCenterId());
        if (zone == null) {
            throw new InvalidParameterValueException("Can't find zone by id " + volume.getDataCenterId());
        }

        if (Grouping.AllocationState.Disabled == zone.getAllocationState() && !_accountMgr.isRootAdmin(caller.getType())) {
            throw new PermissionDeniedException("Cannot perform this operation, Zone is currently disabled: " + zone.getName());
        }

        if (volume.getState() != Volume.State.Ready) {
            throw new InvalidParameterValueException("VolumeId: " + volumeId + " is not in " + Volume.State.Ready + " state but " + volume.getState() + ". Cannot take snapshot.");
        }

        if (volume.getTemplateId() != null) {
            VMTemplateVO template = _templateDao.findById(volume.getTemplateId());
            if (template != null && template.getTemplateType() == Storage.TemplateType.SYSTEM) {
                throw new InvalidParameterValueException("VolumeId: " + volumeId + " is for System VM , Creating snapshot against System VM volumes is not supported");
            }
        }

        StoragePool storagePool = (StoragePool)volume.getDataStore();
        if (storagePool == null) {
            throw new InvalidParameterValueException("VolumeId: " + volumeId + " please attach this volume to a VM before create snapshot for it");
        }

        return snapshotMgr.allocSnapshot(volumeId, policyId);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VOLUME_EXTRACT, eventDescription = "extracting volume", async = true)
    public String extractVolume(ExtractVolumeCmd cmd) {
        Long volumeId = cmd.getId();
        Long zoneId = cmd.getZoneId();
        String mode = cmd.getMode();
        Account account = CallContext.current().getCallingAccount();

        if (!_accountMgr.isRootAdmin(account.getType()) && ApiDBUtils.isExtractionDisabled()) {
            throw new PermissionDeniedException("Extraction has been disabled by admin");
        }

        VolumeVO volume = _volumeDao.findById(volumeId);
        if (volume == null) {
            InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find volume with specified volumeId");
            ex.addProxyObject(volumeId.toString(), "volumeId");
            throw ex;
        }

        // perform permission check
        _accountMgr.checkAccess(account, null, true, volume);

        if (_dcDao.findById(zoneId) == null) {
            throw new InvalidParameterValueException("Please specify a valid zone.");
        }
        if (volume.getPoolId() == null) {
            throw new InvalidParameterValueException("The volume doesnt belong to a storage pool so cant extract it");
        }
        // Extract activity only for detached volumes or for volumes whose
        // instance is stopped
        if (volume.getInstanceId() != null && ApiDBUtils.findVMInstanceById(volume.getInstanceId()).getState() != State.Stopped) {
            s_logger.debug("Invalid state of the volume with ID: " + volumeId + ". It should be either detached or the VM should be in stopped state.");
            PermissionDeniedException ex = new PermissionDeniedException(
                    "Invalid state of the volume with specified ID. It should be either detached or the VM should be in stopped state.");
            ex.addProxyObject(volume.getUuid(), "volumeId");
            throw ex;
        }

        if (volume.getVolumeType() != Volume.Type.DATADISK) {
            // Datadisk dont have any template dependence.

            VMTemplateVO template = ApiDBUtils.findTemplateById(volume.getTemplateId());
            if (template != null) { // For ISO based volumes template = null and
                // we allow extraction of all ISO based
                // volumes
                boolean isExtractable = template.isExtractable() && template.getTemplateType() != Storage.TemplateType.SYSTEM;
                if (!isExtractable && account != null && account.getType() != Account.ACCOUNT_TYPE_ADMIN) {
                    // Global admins are always allowed to extract
                    PermissionDeniedException ex = new PermissionDeniedException("The volume with specified volumeId is not allowed to be extracted");
                    ex.addProxyObject(volume.getUuid(), "volumeId");
                    throw ex;
                }
            }
        }

        Upload.Mode extractMode;
        if (mode == null || (!mode.equals(Upload.Mode.FTP_UPLOAD.toString()) && !mode.equals(Upload.Mode.HTTP_DOWNLOAD.toString()))) {
            throw new InvalidParameterValueException("Please specify a valid extract Mode ");
        } else {
            extractMode = mode.equals(Upload.Mode.FTP_UPLOAD.toString()) ? Upload.Mode.FTP_UPLOAD : Upload.Mode.HTTP_DOWNLOAD;
        }

        // Check if the url already exists
        VolumeDataStoreVO volumeStoreRef = _volumeStoreDao.findByVolume(volumeId);
        if (volumeStoreRef != null && volumeStoreRef.getExtractUrl() != null) {
            return volumeStoreRef.getExtractUrl();
        }

        // Clean up code to remove all those previous uploadVO and uploadMonitor code. Previous code is trying to fake an async operation purely in
        // db table with uploadVO and async_job entry, but internal implementation is actually synchronous.
        StoragePool srcPool = (StoragePool)dataStoreMgr.getPrimaryDataStore(volume.getPoolId());
        ImageStoreEntity secStore = (ImageStoreEntity)dataStoreMgr.getImageStore(zoneId);
        String secondaryStorageURL = secStore.getUri();

        String value = _configDao.getValue(Config.CopyVolumeWait.toString());
        int copyvolumewait = NumbersUtil.parseInt(value, Integer.parseInt(Config.CopyVolumeWait.getDefaultValue()));
        // Copy volume from primary to secondary storage
        VolumeInfo srcVol = volFactory.getVolume(volume.getId());
        AsyncCallFuture<VolumeApiResult> cvAnswer = volService.copyVolume(srcVol, secStore);
        // Check if you got a valid answer.
        VolumeApiResult cvResult = null;
        try {
            cvResult = cvAnswer.get();
        } catch (InterruptedException e1) {
            s_logger.debug("failed copy volume", e1);
            throw new CloudRuntimeException("Failed to copy volume", e1);
        } catch (ExecutionException e1) {
            s_logger.debug("failed copy volume", e1);
            throw new CloudRuntimeException("Failed to copy volume", e1);
        }
        if (cvResult == null || cvResult.isFailed()) {
            String errorString = "Failed to copy the volume from the source primary storage pool to secondary storage.";
            throw new CloudRuntimeException(errorString);
        }

        VolumeInfo vol = cvResult.getVolume();

        String extractUrl = secStore.createEntityExtractUrl(vol.getPath(), vol.getFormat(), vol);
        volumeStoreRef = _volumeStoreDao.findByVolume(volumeId);
        volumeStoreRef.setExtractUrl(extractUrl);
        _volumeStoreDao.update(volumeStoreRef.getId(), volumeStoreRef);

        return extractUrl;
    }

    @Override
    public boolean isDisplayResourceEnabled(Long id) {
        Volume volume = _volumeDao.findById(id);
        if (volume == null) {
            return true; // bad id given, default to true
        }
        return volume.isDisplayVolume();
    }

    private String getFormatForPool(StoragePool pool) {
        ClusterVO cluster = ApiDBUtils.findClusterById(pool.getClusterId());

        if (cluster.getHypervisorType() == HypervisorType.XenServer) {
            return "vhd";
        } else if (cluster.getHypervisorType() == HypervisorType.KVM) {
            return "qcow2";
        } else if (cluster.getHypervisorType() == HypervisorType.Hyperv) {
            return "vhdx";
        } else if (cluster.getHypervisorType() == HypervisorType.VMware) {
            return "ova";
        } else if (cluster.getHypervisorType() == HypervisorType.Ovm) {
            return "raw";
        } else {
            return null;
        }
    }

    private boolean needMoveVolume(VolumeVO rootVolumeOfVm, VolumeInfo volume) {
        if (rootVolumeOfVm.getPoolId() == null || volume.getPoolId() == null) {
            return false;
        }

        DataStore storeForRootVol = dataStoreMgr.getPrimaryDataStore(rootVolumeOfVm.getPoolId());
        DataStore storeForDataVol = dataStoreMgr.getPrimaryDataStore(volume.getPoolId());

        Scope storeForRootStoreScope = storeForRootVol.getScope();
        if (storeForRootStoreScope == null) {
            throw new CloudRuntimeException("Can't get scope of data store: " + storeForRootVol.getId());
        }

        Scope storeForDataStoreScope = storeForDataVol.getScope();
        if (storeForDataStoreScope == null) {
            throw new CloudRuntimeException("Can't get scope of data store: " + storeForDataVol.getId());
        }

        if (storeForDataStoreScope.getScopeType() == ScopeType.ZONE) {
            return false;
        }

        if (storeForRootStoreScope.getScopeType() != storeForDataStoreScope.getScopeType()) {
            if (storeForDataStoreScope.getScopeType() == ScopeType.CLUSTER) {
                Long vmClusterId = null;
                if (storeForRootStoreScope.getScopeType() == ScopeType.HOST) {
                    HostScope hs = (HostScope)storeForRootStoreScope;
                    vmClusterId = hs.getClusterId();
                } else if (storeForRootStoreScope.getScopeType() == ScopeType.ZONE) {
                    Long hostId = _vmInstanceDao.findById(rootVolumeOfVm.getInstanceId()).getHostId();
                    if (hostId != null) {
                        HostVO host = _hostDao.findById(hostId);
                        vmClusterId = host.getClusterId();
                    }
                }
                if (storeForDataStoreScope.getScopeId().equals(vmClusterId)) {
                    return false;
                }
            } else if (storeForDataStoreScope.getScopeType() == ScopeType.HOST
                    && (storeForRootStoreScope.getScopeType() == ScopeType.CLUSTER || storeForRootStoreScope.getScopeType() == ScopeType.ZONE)) {
                Long hostId = _vmInstanceDao.findById(rootVolumeOfVm.getInstanceId()).getHostId();
                if (storeForDataStoreScope.getScopeId().equals(hostId)) {
                    return false;
                }
            }
            throw new CloudRuntimeException("Can't move volume between scope: " + storeForDataStoreScope.getScopeType() + " and " + storeForRootStoreScope.getScopeType());
        }

        return !storeForRootStoreScope.isSameScope(storeForDataStoreScope);
    }

    private VolumeVO sendAttachVolumeCommand(UserVmVO vm, VolumeVO volumeToAttach, Long deviceId) {
        String errorMsg = "Failed to attach volume " + volumeToAttach.getName() + " to VM " + vm.getHostName();
        boolean sendCommand = vm.getState() == State.Running;
        AttachAnswer answer = null;
        Long hostId = vm.getHostId();

        if (hostId == null) {
            hostId = vm.getLastHostId();

            HostVO host = _hostDao.findById(hostId);

            if (host != null && host.getHypervisorType() == HypervisorType.VMware) {
                sendCommand = true;
            }
        }

        HostVO host = null;
        StoragePoolVO volumeToAttachStoragePool = _storagePoolDao.findById(volumeToAttach.getPoolId());

        if (hostId != null) {
            host = _hostDao.findById(hostId);

            if (host != null && host.getHypervisorType() == HypervisorType.XenServer && volumeToAttachStoragePool.isManaged()) {
                sendCommand = true;
            }
        }

        // volumeToAttachStoragePool should be null if the VM we are attaching the disk to has never been started before
        DataStore dataStore = volumeToAttachStoragePool != null ? dataStoreMgr.getDataStore(volumeToAttachStoragePool.getId(), DataStoreRole.Primary) : null;

        // if we don't have a host, the VM we are attaching the disk to has never been started before
        if (host != null) {
            try {
                volService.connectVolumeToHost(volFactory.getVolume(volumeToAttach.getId()), host, dataStore);
            }
            catch (Exception e) {
                volService.disconnectVolumeFromHost(volFactory.getVolume(volumeToAttach.getId()), host, dataStore);

                throw new CloudRuntimeException(e.getMessage());
            }
        }

        if (sendCommand) {
            if (host.getHypervisorType() == HypervisorType.KVM &&
                volumeToAttachStoragePool.isManaged() &&
                volumeToAttach.getPath() == null) {
                volumeToAttach.setPath(volumeToAttach.get_iScsiName());

                _volsDao.update(volumeToAttach.getId(), volumeToAttach);
            }

            DataTO volTO = volFactory.getVolume(volumeToAttach.getId()).getTO();
            DiskTO disk = new DiskTO(volTO, deviceId, volumeToAttach.getPath(), volumeToAttach.getVolumeType());

            AttachCommand cmd = new AttachCommand(disk, vm.getInstanceName());

            ChapInfo chapInfo = volService.getChapInfo(volFactory.getVolume(volumeToAttach.getId()), dataStore);

            Map<String, String> details = new HashMap<String, String>();

            disk.setDetails(details);

            details.put(DiskTO.MANAGED, String.valueOf(volumeToAttachStoragePool.isManaged()));
            details.put(DiskTO.STORAGE_HOST, volumeToAttachStoragePool.getHostAddress());
            details.put(DiskTO.STORAGE_PORT, String.valueOf(volumeToAttachStoragePool.getPort()));
            details.put(DiskTO.VOLUME_SIZE, String.valueOf(volumeToAttach.getSize()));
            details.put(DiskTO.IQN, volumeToAttach.get_iScsiName());

            if (chapInfo != null) {
                details.put(DiskTO.CHAP_INITIATOR_USERNAME, chapInfo.getInitiatorUsername());
                details.put(DiskTO.CHAP_INITIATOR_SECRET, chapInfo.getInitiatorSecret());
                details.put(DiskTO.CHAP_TARGET_USERNAME, chapInfo.getTargetUsername());
                details.put(DiskTO.CHAP_TARGET_SECRET, chapInfo.getTargetSecret());
            }

            try {
                answer = (AttachAnswer)_agentMgr.send(hostId, cmd);
            } catch (Exception e) {
                volService.disconnectVolumeFromHost(volFactory.getVolume(volumeToAttach.getId()), host, dataStore);

                throw new CloudRuntimeException(errorMsg + " due to: " + e.getMessage());
            }
        }

        if (!sendCommand || (answer != null && answer.getResult())) {
            // Mark the volume as attached
            if (sendCommand) {
                DiskTO disk = answer.getDisk();
                _volsDao.attachVolume(volumeToAttach.getId(), vm.getId(), disk.getDiskSeq());

                volumeToAttach = _volsDao.findById(volumeToAttach.getId());

                if (volumeToAttachStoragePool.isManaged() && volumeToAttach.getPath() == null) {
                    volumeToAttach.setPath(answer.getDisk().getPath());

                    _volsDao.update(volumeToAttach.getId(), volumeToAttach);
                }
            } else {
                _volsDao.attachVolume(volumeToAttach.getId(), vm.getId(), deviceId);
            }

            // insert record for disk I/O statistics
            VmDiskStatisticsVO diskstats = _vmDiskStatsDao.findBy(vm.getAccountId(), vm.getDataCenterId(), vm.getId(), volumeToAttach.getId());
            if (diskstats == null) {
                diskstats = new VmDiskStatisticsVO(vm.getAccountId(), vm.getDataCenterId(), vm.getId(), volumeToAttach.getId());
                _vmDiskStatsDao.persist(diskstats);
            }

            return _volsDao.findById(volumeToAttach.getId());
        } else {
            if (answer != null) {
                String details = answer.getDetails();
                if (details != null && !details.isEmpty()) {
                    errorMsg += "; " + details;
                }
            }

            volService.disconnectVolumeFromHost(volFactory.getVolume(volumeToAttach.getId()), host, dataStore);

            throw new CloudRuntimeException(errorMsg);
        }
    }

    private int getMaxDataVolumesSupported(UserVmVO vm) {
        Long hostId = vm.getHostId();
        if (hostId == null) {
            hostId = vm.getLastHostId();
        }
        HostVO host = _hostDao.findById(hostId);
        Integer maxDataVolumesSupported = null;
        if (host != null) {
            _hostDao.loadDetails(host);
            maxDataVolumesSupported = _hypervisorCapabilitiesDao.getMaxDataVolumesLimit(host.getHypervisorType(), host.getDetail("product_version"));
        }
        if (maxDataVolumesSupported == null) {
            maxDataVolumesSupported = 6; // 6 data disks by default if nothing
            // is specified in
            // 'hypervisor_capabilities' table
        }

        return maxDataVolumesSupported.intValue();
    }

    private Long getDeviceId(long vmId, Long deviceId) {
        // allocate deviceId
        List<VolumeVO> vols = _volsDao.findByInstance(vmId);
        if (deviceId != null) {
            if (deviceId.longValue() > 15 || deviceId.longValue() == 0 || deviceId.longValue() == 3) {
                throw new RuntimeException("deviceId should be 1,2,4-15");
            }
            for (VolumeVO vol : vols) {
                if (vol.getDeviceId().equals(deviceId)) {
                    throw new RuntimeException("deviceId " + deviceId + " is used by vm" + vmId);
                }
            }
        } else {
            // allocate deviceId here
            List<String> devIds = new ArrayList<String>();
            for (int i = 1; i < 15; i++) {
                devIds.add(String.valueOf(i));
            }
            devIds.remove("3");
            for (VolumeVO vol : vols) {
                devIds.remove(vol.getDeviceId().toString().trim());
            }
            deviceId = Long.parseLong(devIds.iterator().next());
        }

        return deviceId;
    }

    @Override
    public boolean configure(String name, Map<String, Object> params) {

        String maxVolumeSizeInGbString = _configDao.getValue(Config.MaxVolumeSize.toString());
        _maxVolumeSizeInGb = NumbersUtil.parseLong(maxVolumeSizeInGbString, 2000);
        return true;
    }

    public List<StoragePoolAllocator> getStoragePoolAllocators() {
        return _storagePoolAllocators;
    }

    @Inject
    public void setStoragePoolAllocators(List<StoragePoolAllocator> storagePoolAllocators) {
        _storagePoolAllocators = storagePoolAllocators;
    }

    public class VmJobVolumeOutcome extends OutcomeImpl<Volume> {
        private long _volumeId;

        public VmJobVolumeOutcome(final AsyncJob job, final long volumeId) {
            super(Volume.class, job, VmJobCheckInterval.value(), new Predicate() {
                @Override
                public boolean checkCondition() {
                    AsyncJobVO jobVo = _entityMgr.findById(AsyncJobVO.class, job.getId());
                    assert (jobVo != null);
                    if (jobVo == null || jobVo.getStatus() != JobInfo.Status.IN_PROGRESS)
                        return true;

                    return false;
                }
            }, AsyncJob.Topics.JOB_STATE);
            _volumeId = volumeId;
        }

        @Override
        protected Volume retrieve() {
            return _volumeDao.findById(_volumeId);
        }
    }

    public class VmJobSnapshotOutcome extends OutcomeImpl<Snapshot> {
        private long _snapshotId;

        public VmJobSnapshotOutcome(final AsyncJob job, final long snapshotId) {
            super(Snapshot.class, job, VmJobCheckInterval.value(), new Predicate() {
                @Override
                public boolean checkCondition() {
                    AsyncJobVO jobVo = _entityMgr.findById(AsyncJobVO.class, job.getId());
                    assert (jobVo != null);
                    if (jobVo == null || jobVo.getStatus() != JobInfo.Status.IN_PROGRESS)
                        return true;

                    return false;
                }
            }, AsyncJob.Topics.JOB_STATE);
            _snapshotId = snapshotId;
        }

        @Override
        protected Snapshot retrieve() {
            return _snapshotDao.findById(_snapshotId);
        }
    }

    public Outcome<Volume> attachVolumeToVmThroughJobQueue(final Long vmId, final Long volumeId, final Long deviceId) {

        final CallContext context = CallContext.current();
        final User callingUser = context.getCallingUser();
        final Account callingAccount = context.getCallingAccount();

        final VMInstanceVO vm = _vmInstanceDao.findById(vmId);

        Object[] result = Transaction.execute(new TransactionCallback<Object[]>() {
            @Override
            public Object[] doInTransaction(TransactionStatus status) {
                VmWorkJobVO workJob = null;

                _vmInstanceDao.lockInLockTable(String.valueOf(vm.getId()), Integer.MAX_VALUE);
                try {
                    workJob = new VmWorkJobVO(context.getContextId());

                    workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_DISPATCHER);
                    workJob.setCmd(VmWorkAttachVolume.class.getName());

                    workJob.setAccountId(callingAccount.getId());
                    workJob.setUserId(callingUser.getId());
                    workJob.setStep(VmWorkJobVO.Step.Starting);
                    workJob.setVmType(VirtualMachine.Type.Instance);
                    workJob.setVmInstanceId(vm.getId());
                    workJob.setRelated(AsyncJobExecutionContext.getOriginJobId());

                    // save work context info (there are some duplications)
                    VmWorkAttachVolume workInfo = new VmWorkAttachVolume(callingUser.getId(), callingAccount.getId(), vm.getId(),
                            VolumeApiServiceImpl.VM_WORK_JOB_HANDLER, volumeId, deviceId);
                    workJob.setCmdInfo(VmWorkSerializer.serialize(workInfo));

                    _jobMgr.submitAsyncJob(workJob, VmWorkConstants.VM_WORK_QUEUE, vm.getId());

                    AsyncJobVO jobVo = _jobMgr.getAsyncJob(workJob.getId());
                    s_logger.debug("New job " + workJob.getId() + ", result field: " + jobVo.getResult());

                    return new Object[] {workJob, new Long(workJob.getId())};
                } finally {
                    _vmInstanceDao.unlockFromLockTable(String.valueOf(vm.getId()));
                }
            }
        });

        final long jobId = (Long)result[1];
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(jobId);

        return new VmJobVolumeOutcome((VmWorkJobVO)result[0],
                volumeId);
    }

    public Outcome<Volume> detachVolumeFromVmThroughJobQueue(final Long vmId, final Long volumeId) {

        final CallContext context = CallContext.current();
        final User callingUser = context.getCallingUser();
        final Account callingAccount = context.getCallingAccount();

        final VMInstanceVO vm = _vmInstanceDao.findById(vmId);

        Object[] result = Transaction.execute(new TransactionCallback<Object[]>() {
            @Override
            public Object[] doInTransaction(TransactionStatus status) {
                VmWorkJobVO workJob = null;

                _vmInstanceDao.lockInLockTable(String.valueOf(vm.getId()), Integer.MAX_VALUE);
                try {
                    workJob = new VmWorkJobVO(context.getContextId());

                    workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_DISPATCHER);
                    workJob.setCmd(VmWorkDetachVolume.class.getName());

                    workJob.setAccountId(callingAccount.getId());
                    workJob.setUserId(callingUser.getId());
                    workJob.setStep(VmWorkJobVO.Step.Starting);
                    workJob.setVmType(VirtualMachine.Type.Instance);
                    workJob.setVmInstanceId(vm.getId());
                    workJob.setRelated(AsyncJobExecutionContext.getOriginJobId());

                    // save work context info (there are some duplications)
                    VmWorkDetachVolume workInfo = new VmWorkDetachVolume(callingUser.getId(), callingAccount.getId(), vm.getId(),
                            VolumeApiServiceImpl.VM_WORK_JOB_HANDLER, volumeId);
                    workJob.setCmdInfo(VmWorkSerializer.serialize(workInfo));

                    _jobMgr.submitAsyncJob(workJob, VmWorkConstants.VM_WORK_QUEUE, vm.getId());

                    return new Object[] {workJob, new Long(workJob.getId())};
                } finally {
                    _vmInstanceDao.unlockFromLockTable(String.valueOf(vm.getId()));
                }
            }
        });

        final long jobId = (Long)result[1];
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(jobId);

        return new VmJobVolumeOutcome((VmWorkJobVO)result[0],
                volumeId);
    }

    public Outcome<Volume> resizeVolumeThroughJobQueue(final Long vmId, final long volumeId,
            final long currentSize, final long newSize, final Long newServiceOfferingId, final boolean shrinkOk) {

        final CallContext context = CallContext.current();
        final User callingUser = context.getCallingUser();
        final Account callingAccount = context.getCallingAccount();

        final VMInstanceVO vm = _vmInstanceDao.findById(vmId);

        Object[] result = Transaction.execute(new TransactionCallback<Object[]>() {
            @Override
            public Object[] doInTransaction(TransactionStatus status) {
                VmWorkJobVO workJob = null;

                _vmInstanceDao.lockInLockTable(String.valueOf(vm.getId()), Integer.MAX_VALUE);

                try {
                    workJob = new VmWorkJobVO(context.getContextId());

                    workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_DISPATCHER);
                    workJob.setCmd(VmWorkResizeVolume.class.getName());

                    workJob.setAccountId(callingAccount.getId());
                    workJob.setUserId(callingUser.getId());
                    workJob.setStep(VmWorkJobVO.Step.Starting);
                    workJob.setVmType(VirtualMachine.Type.Instance);
                    workJob.setVmInstanceId(vm.getId());
                    workJob.setRelated(AsyncJobExecutionContext.getOriginJobId());

                    // save work context info (there are some duplications)
                    VmWorkResizeVolume workInfo = new VmWorkResizeVolume(callingUser.getId(), callingAccount.getId(), vm.getId(),
                            VolumeApiServiceImpl.VM_WORK_JOB_HANDLER, volumeId, currentSize, newSize, newServiceOfferingId, shrinkOk);
                    workJob.setCmdInfo(VmWorkSerializer.serialize(workInfo));

                    _jobMgr.submitAsyncJob(workJob, VmWorkConstants.VM_WORK_QUEUE, vm.getId());

                    return new Object[] {workJob, new Long(workJob.getId())};
                } finally {
                    _vmInstanceDao.unlockFromLockTable(String.valueOf(vm.getId()));
                }
            }
        });

        final long jobId = (Long)result[1];
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(jobId);

        return new VmJobVolumeOutcome((VmWorkJobVO)result[0],
                volumeId);
    }

    public Outcome<Volume> migrateVolumeThroughJobQueue(final Long vmId, final long volumeId,
            final long destPoolId, final boolean liveMigrate) {

        final CallContext context = CallContext.current();
        final User callingUser = context.getCallingUser();
        final Account callingAccount = context.getCallingAccount();

        final VMInstanceVO vm = _vmInstanceDao.findById(vmId);

        Object[] result = Transaction.execute(new TransactionCallback<Object[]>() {
            @Override
            public Object[] doInTransaction(TransactionStatus status) {
                VmWorkJobVO workJob = null;

                _vmInstanceDao.lockInLockTable(String.valueOf(vm.getId()), Integer.MAX_VALUE);
                try {
                    workJob = new VmWorkJobVO(context.getContextId());

                    workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_DISPATCHER);
                    workJob.setCmd(VmWorkMigrateVolume.class.getName());

                    workJob.setAccountId(callingAccount.getId());
                    workJob.setUserId(callingUser.getId());
                    workJob.setStep(VmWorkJobVO.Step.Starting);
                    workJob.setVmType(VirtualMachine.Type.Instance);
                    workJob.setVmInstanceId(vm.getId());
                    workJob.setRelated(AsyncJobExecutionContext.getOriginJobId());

                    // save work context info (there are some duplications)
                    VmWorkMigrateVolume workInfo = new VmWorkMigrateVolume(callingUser.getId(), callingAccount.getId(), vm.getId(),
                            VolumeApiServiceImpl.VM_WORK_JOB_HANDLER, volumeId, destPoolId, liveMigrate);
                    workJob.setCmdInfo(VmWorkSerializer.serialize(workInfo));

                    _jobMgr.submitAsyncJob(workJob, VmWorkConstants.VM_WORK_QUEUE, vm.getId());

                    return new Object[] {workJob, new Long(workJob.getId())};
                } finally {
                    _vmInstanceDao.unlockFromLockTable(String.valueOf(vm.getId()));
                }
            }
        });

        final long jobId = (Long)result[1];
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(jobId);

        return new VmJobVolumeOutcome((VmWorkJobVO)result[0],
                volumeId);
    }

    public Outcome<Snapshot> takeVolumeSnapshotThroughJobQueue(final Long vmId, final Long volumeId,
            final Long policyId, final Long snapshotId, final Long accountId, final boolean quiesceVm) {

        final CallContext context = CallContext.current();
        final User callingUser = context.getCallingUser();
        final Account callingAccount = context.getCallingAccount();

        final VMInstanceVO vm = _vmInstanceDao.findById(vmId);

        Object[] result = Transaction.execute(new TransactionCallback<Object[]>() {
            @Override
            public Object[] doInTransaction(TransactionStatus status) {
                VmWorkJobVO workJob = null;

                _vmInstanceDao.lockInLockTable(String.valueOf(vm.getId()), Integer.MAX_VALUE);
                try {
                    workJob = new VmWorkJobVO(context.getContextId());

                    workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_DISPATCHER);
                    workJob.setCmd(VmWorkTakeVolumeSnapshot.class.getName());

                    workJob.setAccountId(callingAccount.getId());
                    workJob.setUserId(callingUser.getId());
                    workJob.setStep(VmWorkJobVO.Step.Starting);
                    workJob.setVmType(VirtualMachine.Type.Instance);
                    workJob.setVmInstanceId(vm.getId());
                    workJob.setRelated(AsyncJobExecutionContext.getOriginJobId());

                    // save work context info (there are some duplications)
                    VmWorkTakeVolumeSnapshot workInfo = new VmWorkTakeVolumeSnapshot(
                            callingUser.getId(), accountId != null ? accountId : callingAccount.getId(), vm.getId(),
                            VolumeApiServiceImpl.VM_WORK_JOB_HANDLER, volumeId, policyId, snapshotId, quiesceVm);
                    workJob.setCmdInfo(VmWorkSerializer.serialize(workInfo));

                    _jobMgr.submitAsyncJob(workJob, VmWorkConstants.VM_WORK_QUEUE, vm.getId());

                    return new Object[] {workJob, new Long(workJob.getId())};
                } finally {
                    _vmInstanceDao.unlockFromLockTable(String.valueOf(vm.getId()));
                }
            }
        });

        final long jobId = (Long)result[1];
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(jobId);

        return new VmJobSnapshotOutcome((VmWorkJobVO)result[0],
                snapshotId);
    }

    private Pair<JobInfo.Status, String> orchestrateAttachVolumeToVM(VmWorkAttachVolume work) throws Exception {
        Volume vol = orchestrateAttachVolumeToVM(work.getVmId(), work.getVolumeId(), work.getDeviceId());

        return new Pair<JobInfo.Status, String>(JobInfo.Status.SUCCEEDED,
                _jobMgr.marshallResultObject(new Long(vol.getId())));
    }

    private Pair<JobInfo.Status, String> orchestrateDetachVolumeFromVM(VmWorkDetachVolume work) throws Exception {
        Volume vol = orchestrateDetachVolumeFromVM(work.getVmId(), work.getVolumeId());
        return new Pair<JobInfo.Status, String>(JobInfo.Status.SUCCEEDED,
                _jobMgr.marshallResultObject(new Long(vol.getId())));
    }

    private Pair<JobInfo.Status, String> orchestrateResizeVolume(VmWorkResizeVolume work) throws Exception {
        Volume vol = orchestrateResizeVolume(work.getVolumeId(), work.getCurrentSize(), work.getNewSize(),
                work.getNewServiceOfferingId(), work.isShrinkOk());
        return new Pair<JobInfo.Status, String>(JobInfo.Status.SUCCEEDED,
                _jobMgr.marshallResultObject(new Long(vol.getId())));
    }

    private Pair<JobInfo.Status, String> orchestrateMigrateVolume(VmWorkMigrateVolume work) throws Exception {
        Volume newVol = orchestrateMigrateVolume(work.getVolumeId(), work.getDestPoolId(), work.isLiveMigrate());
        return new Pair<JobInfo.Status, String>(JobInfo.Status.SUCCEEDED,
                _jobMgr.marshallResultObject(new Long(newVol.getId())));
    }

    private Pair<JobInfo.Status, String> orchestrateTakeVolumeSnapshot(VmWorkTakeVolumeSnapshot work) throws Exception {
        Account account = _accountDao.findById(work.getAccountId());
        orchestrateTakeVolumeSnapshot(work.getVolumeId(), work.getPolicyId(), work.getSnapshotId(),
                account, work.isQuiesceVm());
        return new Pair<JobInfo.Status, String>(JobInfo.Status.SUCCEEDED,
                _jobMgr.marshallResultObject(work.getSnapshotId()));
    }

    @Override
    public Pair<JobInfo.Status, String> handleVmWorkJob(VmWork work) throws Exception {
        return _jobHandlerProxy.handleVmWorkJob(work);
    }

    private VmWorkJobVO createPlaceHolderWork(long instanceId) {
        VmWorkJobVO workJob = new VmWorkJobVO("");

        workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_PLACEHOLDER);
        workJob.setCmd("");
        workJob.setCmdInfo("");

        workJob.setAccountId(0);
        workJob.setUserId(0);
        workJob.setStep(VmWorkJobVO.Step.Starting);
        workJob.setVmType(VirtualMachine.Type.Instance);
        workJob.setVmInstanceId(instanceId);
        workJob.setInitMsid(ManagementServerNode.getManagementServerId());

        _workJobDao.persist(workJob);

        return workJob;
    }
}
