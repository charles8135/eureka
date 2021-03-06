/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.eureka.resources;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.eureka.EurekaServerConfigurationManager;
import com.netflix.eureka.PeerAwareInstanceRegistry;
import com.netflix.eureka.cluster.PeerEurekaNode;

/**
 * A <em>jersey</em> resource that handles operations for a particular instance.
 * 
 * @author Karthik Ranganathan, Greg Kim
 * 
 */
@Produces({ "application/xml", "application/json" })
public class InstanceResource {
    private final static Logger logger = LoggerFactory
            .getLogger(InstanceResource.class);

    private final static PeerAwareInstanceRegistry registry = PeerAwareInstanceRegistry
            .getInstance();

    String id;
    ApplicationResource app;

    public InstanceResource(ApplicationResource app, String id) {
        this.id = id;
        this.app = app;
    }

    /**
     * Get requests returns the information about the instance's
     * {@link InstanceInfo}.
     * 
     * @return response containing information about the the instance's
     *         {@link InstanceInfo}.
     */
    @GET
    public Response getInstanceInfo() {
        InstanceInfo appInfo = registry
                .getInstanceByAppAndId(app.getName(), id);
        if (appInfo != null) {
            logger.debug("Found: {} - {}", app.getName(), id);
            return Response.ok(appInfo).build();
        } else {
            logger.debug("Not Found: {} - {}", app.getName(), id);
            return Response.status(Status.NOT_FOUND).build();
        }
    }

    /**
     * A put request for renewing lease from a client instance.
     * 
     * @param isReplication
     *            a header parameter containing information whether this is
     *            replicated from other nodes.
     * @param overriddenStatus
     *            overridden status if any.
     * @param status
     *            the {@link InstanceStatus} of the instance.
     * @param lastDirtyTimestamp
     *            last timestamp when this instance information was updated.
     * @return response indicating whether the operation was a success or
     *         failure.
     */
    @PUT
    public Response renewLease(
            @HeaderParam(PeerEurekaNode.HEADER_REPLICATION) String isReplication,
            @QueryParam("overriddenstatus") String overriddenStatus,
            @QueryParam("status") String status,
            @QueryParam("lastDirtyTimestamp") String lastDirtyTimestamp) {
        boolean isFromReplicaNode = "true".equals(isReplication);
        boolean isSuccess = registry
                .renew(app.getName(), id, isFromReplicaNode);

        // Not found in the registry, immediately ask for a register
        if (!isSuccess) {
            logger.debug("Not Found (Renew): {} - {}", app.getName(), id);
            return Response.status(Status.NOT_FOUND).build();
        }
        // Check if we need to sync based on dirty time stamp, the client
        // instance might have changed some value
        Response response = null;
        if (lastDirtyTimestamp != null
                && EurekaServerConfigurationManager.getInstance()
                        .getConfiguration().shouldSyncWhenTimestampDiffers()) {
            response = this.validateDirtyTimestamp(
                    Long.valueOf(lastDirtyTimestamp), isFromReplicaNode);
            // Store the overridden status since the validation found out the
            // node that
            // replicates wins
            if (response.getStatus() == Response.Status.NOT_FOUND
                    .getStatusCode()
                    && (overriddenStatus != null)
                    && !(InstanceStatus.UNKNOWN.equals(overriddenStatus))
                    && isFromReplicaNode) {
                registry.storeOverriddenStatusIfRequired(overriddenStatus,
                        InstanceStatus.valueOf(overriddenStatus));
            }
            return response;
        }
        logger.debug("Found (Renew): {} - {}" + app.getName(), id);
        return Response.ok().build();
    }

    /**
     * Handles {@link InstanceStatus} updates.
     * 
     * <p>
     * The status updates are normally done for administrative purposes to
     * change the instance status between {@link InstanceStatus#UP} and
     * {@link InstanceStatus#OUT_OF_SERVICE} to select or remove instances for
     * receiving traffic.
     * </p>
     * 
     * @param newStatus
     *            the new status of the instance.
     * @param isReplication
     *            a header parameter containing information whether this is
     *            replicated from other nodes.
     * @param lastDirtyTimestamp
     *            last timestamp when this instance information was updated.
     * @return response indicating whether the operation was a success or
     *         failure.
     */
    @PUT
    @Path("status")
    public Response statusUpdate(@QueryParam("value") String newStatus,
            @HeaderParam(PeerEurekaNode.HEADER_REPLICATION) String isReplication,
            @QueryParam("lastDirtyTimestamp") String lastDirtyTimestamp) {
        try {
            boolean isSuccess = registry.statusUpdate(app.getName(), id,
                    InstanceStatus.valueOf(newStatus), lastDirtyTimestamp,
                    "true".equals(isReplication));

            if (isSuccess) {
                logger.info("Status updated: " + app.getName() + " - " + id
                        + " - " + newStatus);
                return Response.ok().build();
            } else {
                logger.warn("Unable to update status: " + app.getName() + " - "
                        + id + " - " + newStatus);
                return Response.status(Status.NOT_ACCEPTABLE).build();
            }
        } catch (Throwable e) {
            logger.error("Error updating instance {} for status {}", id,
                    newStatus);
            return Response.serverError().build();
        }
    }

    /**
     * Handles cancellation of leases for this particular instance.
     * 
     * @param isReplication
     *            a header parameter containing information whether this is
     *            replicated from other nodes.
     * @return response indicating whether the operation was a success or
     *         failure.
     */
    @DELETE
    public Response cancelLease(
            @HeaderParam(PeerEurekaNode.HEADER_REPLICATION) String isReplication) {
        boolean isSuccess = registry.cancel(app.getName(), id,
                "true".equals(isReplication));

        if (isSuccess) {
            logger.info("Found (Cancel): " + app.getName() + " - " + id);
            return Response.ok().build();
        } else {
            logger.info("Not Found (Cancel): " + app.getName() + " - " + id);
            return Response.status(Status.NOT_FOUND).build();
        }
    }

    private boolean shouldSyncStatus(String status, boolean isReplication) {
        InstanceInfo appInfo = registry
                .getInstanceByAppAndId(app.getName(), id);
        InstanceStatus instanceStatusFromRegistry = null;
        if (appInfo != null) {
            instanceStatusFromRegistry = appInfo.getStatus();
        }
        InstanceStatus instanceStatusFromReplica = (status != null ? InstanceStatus
                .valueOf(status) : null);
        if (instanceStatusFromReplica != null) {
            // Do sync up only for replication - because the client could have
            // different state when the server
            // state is updated by an external tool
            if ((!instanceStatusFromRegistry.equals(instanceStatusFromReplica))
                    && isReplication) {
                Object[] args = { id, instanceStatusFromRegistry.name(),
                        instanceStatusFromReplica.name() };
                logger.warn(
                        "The instance status for {} is {} from registry, whereas it is {} from replica. Requesting a re-register from replica.",
                        args);

                return true;
            }
        }
        return false;

    }

    private Response validateDirtyTimestamp(Long lastDirtyTimestamp,
            boolean isReplication) {
        InstanceInfo appInfo = registry
                .getInstanceByAppAndId(app.getName(), id);
        if (appInfo != null) {
            if ((lastDirtyTimestamp != null)
                    && (!lastDirtyTimestamp.equals(appInfo
                            .getLastDirtyTimestamp()))) {
                Object[] args = { id, appInfo.getLastDirtyTimestamp(),
                        lastDirtyTimestamp, isReplication };
                if (lastDirtyTimestamp > appInfo.getLastDirtyTimestamp()) {
                    logger.warn(
                            "Time to sync, since the last dirty timestamp differs -"
                                    + " Instance id : {},Registry : {} Incoming: {} Replication: {}",
                            args);
                    return Response.status(Status.NOT_FOUND).build();
                } else if (appInfo.getLastDirtyTimestamp() > lastDirtyTimestamp) {
                    // In the case of replication, send the current instance info in the registry for the
                    // replicating node to sync itself with this one.
                    if (isReplication) {
                        logger.warn(
                                "Time to sync, since the last dirty timestamp differs -"
                                        + " Instance id : {},Registry : {} Incoming: {} Replication: {}",
                                args);
                        return Response.ok(appInfo).build();
                    }
                    else {
                        return Response.ok().build();
                    }
                }
            }

        }
        return Response.ok().build();
    }
}
