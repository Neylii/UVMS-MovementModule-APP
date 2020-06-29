/*
﻿Developed with the contribution of the European Commission - Directorate General for Maritime Affairs and Fisheries
© European Union, 2015-2016.

This file is part of the Integrated Fisheries Data Management (IFDM) Suite. The IFDM Suite is free software: you can
redistribute it and/or modify it under the terms of the GNU General Public License as published by the
Free Software Foundation, either version 3 of the License, or any later version. The IFDM Suite is distributed in
the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details. You should have received a
copy of the GNU General Public License along with the IFDM Suite. If not, see <http://www.gnu.org/licenses/>.
 */
package eu.europa.ec.fisheries.uvms.movement.rest.service;

import eu.europa.ec.fisheries.schema.movement.v1.MovementSourceType;
import eu.europa.ec.fisheries.uvms.commons.date.DateUtils;
import eu.europa.ec.fisheries.uvms.commons.date.JsonBConfigurator;
import eu.europa.ec.fisheries.uvms.movement.rest.RestUtilMapper;
import eu.europa.ec.fisheries.uvms.movement.rest.dto.RealTimeMapInitialData;
import eu.europa.ec.fisheries.uvms.movement.rest.dto.TrackForAssetsQuery;
import eu.europa.ec.fisheries.uvms.movement.service.bean.MovementService;
import eu.europa.ec.fisheries.uvms.movement.service.dao.MovementDao;
import eu.europa.ec.fisheries.uvms.movement.service.dto.MicroMovement;
import eu.europa.ec.fisheries.uvms.movement.service.dto.MicroMovementExtended;
import eu.europa.ec.fisheries.uvms.movement.service.util.JsonBConfiguratorMovement;
import eu.europa.ec.fisheries.uvms.rest.security.RequiresFeature;
import eu.europa.ec.fisheries.uvms.rest.security.UnionVMSFeature;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import javax.annotation.PostConstruct;
import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.json.JsonObject;
import javax.json.bind.Jsonb;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Stateless
@Path("micro")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class MicroMovementRestResource {

    private static final Logger LOG = LoggerFactory.getLogger(MicroMovementRestResource.class);

    @Inject
    private MovementService movementService;

    @Inject
    private MovementDao movementDao;

    private Jsonb jsonb;    //to be able to replace one part of the string beeing sent out since yasson does not allow one to send raw ;(

    @PostConstruct
    public void init(){
        jsonb = new JsonBConfiguratorMovement().getContext(null);
    }

    @POST
    @Path("/track/asset/{id}/")
    @RequiresFeature(UnionVMSFeature.viewMovements)
    public Response getMicroMovementTrackForAssetByDate(@PathParam("id") UUID connectId, @DefaultValue("") @QueryParam("startDate") String startDate, @DefaultValue("") @QueryParam("endDate") String endDate, List<String> sources) {
        try {

            List<MovementSourceType> sourceTypes = RestUtilMapper.convertToMovementSourceTypes(sources);
            Instant startInstant = (startDate.isEmpty() ? Instant.now().minus(8, ChronoUnit.HOURS) : DateUtils.stringToDate(startDate));
            Instant endInstant = (endDate.isEmpty() ? Instant.now() : DateUtils.stringToDate(endDate));
            List<MicroMovement> microList = movementDao.getMicroMovementsForAssetAfterDate(connectId, startInstant, endInstant, sourceTypes);
            return Response.ok(microList).header("MDC", MDC.get("requestId")).build();
        } catch (Exception e) {
            LOG.error("Error when getting Micro Movement for connectId: {}", connectId, e);
            throw e;
        }
    }

    @POST
    @Path("/track/latest/asset/{id}/")
    @RequiresFeature(UnionVMSFeature.viewMovements)
    public Response getMicroMovementTrackForAssetByNumber(@PathParam("id") UUID connectId, @DefaultValue("2000") @QueryParam("maxNbr") Integer maxNumber, List<String> sources) {
        try {

            List<MovementSourceType> sourceTypes = RestUtilMapper.convertToMovementSourceTypes(sources);
            List<MicroMovement> microList = movementDao.getLatestNumberOfMicroMovementsForAsset(connectId, maxNumber, sourceTypes);
            return Response.ok(microList).header("MDC", MDC.get("requestId")).build();
        } catch (Exception e) {
            LOG.error("Error when getting Micro Movement for connectId: {}", connectId, e);
            throw e;
        }
    }

    @POST
    @Path("/track/assets/")
    @RequiresFeature(UnionVMSFeature.viewMovements)
    public Response getMicroMovementTrackForAssets(@DefaultValue("") @QueryParam("startDate") String startDate, @DefaultValue("") @QueryParam("endDate") String endDate, TrackForAssetsQuery query) {
        try {
            if (query.getAssetIds().isEmpty()) {
                return Response.ok(Collections.emptyList()).header("MDC", MDC.get("requestId")).build();
            }

            List<MovementSourceType> sourceTypes = RestUtilMapper.convertToMovementSourceTypes(query.getSources());
            Instant startInstant = (endDate.isEmpty() ? Instant.now().minus(8, ChronoUnit.HOURS) : DateUtils.stringToDate(startDate));
            Instant endInstant = (endDate.isEmpty() ? Instant.now() : DateUtils.stringToDate(endDate));
            List<MicroMovementExtended> microList = movementDao.getMicroMovementsForConnectIdsBetweenDates(query.getAssetIds(), startInstant, endInstant, sourceTypes);
            return Response.ok(microList).header("MDC", MDC.get("requestId")).build();
        } catch (Exception e) {
            LOG.error("Error when getting Micro Movement for connectIds: {}", query.getAssetIds(), e);
            throw e;
        }
    }

    @POST
    @Path("/latest")
    @RequiresFeature(UnionVMSFeature.viewMovements)
    public Response getLastMicroMovementForAllAssets(List<String> sources) {
        try {
            List<MovementSourceType> sourceTypes = RestUtilMapper.convertToMovementSourceTypes(sources);
            List<MicroMovementExtended> microMovements = movementService.getLatestMovementsLast8Hours(sourceTypes);

            List<String> assetIdList = new ArrayList<>(microMovements.size());
            for (MicroMovementExtended micro: microMovements) {
                assetIdList.add(micro.getAsset());
            }

            String assetInfo = movementService.getMicroAssets(assetIdList);
            RealTimeMapInitialData retVal = new RealTimeMapInitialData(microMovements);
            String returnJson = jsonb.toJson(retVal).replace(RealTimeMapInitialData.ASSET_JSON_PLACE_HERE, assetInfo);

            return Response.ok(returnJson).header("MDC", MDC.get("requestId")).build();
        } catch (Exception e) {
            LOG.error("Error when getting latest Micro Movements", e);
            throw e;
        }
    }

}
