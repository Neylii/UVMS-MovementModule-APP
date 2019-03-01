package eu.europa.ec.fisheries.uvms.movement.rest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.europa.ec.fisheries.schema.movement.search.v1.ListCriteria;
import eu.europa.ec.fisheries.schema.movement.search.v1.ListPagination;
import eu.europa.ec.fisheries.schema.movement.search.v1.MovementQuery;
import eu.europa.ec.fisheries.schema.movement.search.v1.SearchKey;
import eu.europa.ec.fisheries.schema.movement.source.v1.GetMovementListByQueryResponse;
import eu.europa.ec.fisheries.schema.movement.source.v1.GetMovementMapByQueryResponse;
import eu.europa.ec.fisheries.schema.movement.v1.MovementType;
import eu.europa.ec.fisheries.uvms.movement.model.util.DateUtil;
import eu.europa.ec.fisheries.uvms.movement.rest.BuildMovementRestDeployment;
import eu.europa.ec.fisheries.uvms.movement.rest.MovementTestHelper;
import eu.europa.ec.fisheries.uvms.movement.service.bean.MovementService;
import eu.europa.ec.fisheries.uvms.movement.service.entity.Movement;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.junit.Arquillian;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.inject.Inject;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

@RunWith(Arquillian.class)
public class InternalRestResourceTest extends BuildMovementRestDeployment {

    @Inject
    private MovementService movementService;

    private ObjectMapper mapper = new ObjectMapper();

    @Test
    @OperateOnDeployment("movement")
    public void getMovementListByQuery() throws IOException {
        Movement movementBaseType = MovementTestHelper.createMovement();
        Movement createdMovement = movementService.createMovement(movementBaseType);

        assertNotNull(createdMovement.getId());

        MovementQuery query = createMovementQuery(null);

        String response = getWebTarget()
                .path("internal/list")
                .request(MediaType.APPLICATION_JSON)
                .post(Entity.json(query), String.class);
        assertNotNull(response);

        GetMovementListByQueryResponse movList =
                mapper.readValue(response, GetMovementListByQueryResponse.class);
        assertNotNull(movList);
        assertTrue(movList.getMovement().size() > 0);
    }

    @Test
    @OperateOnDeployment("movement")
    public void getMovementMinimalListByQuery() throws IOException {
        Movement movementBaseType = MovementTestHelper.createMovement();
        Movement createdMovement = movementService.createMovement(movementBaseType);

        assertNotNull(createdMovement.getId());

        MovementQuery query = createMovementQuery(null);

        String response = getWebTarget()
                .path("internal/list/minimal")
                .request(MediaType.APPLICATION_JSON)
                .post(Entity.json(query), String.class);
        assertNotNull(response);

        GetMovementListByQueryResponse movList =
                mapper.readValue(response, GetMovementListByQueryResponse.class);
        assertNotNull(movList);
        assertTrue(movList.getMovement().size() > 0);
    }

    @Test
    @OperateOnDeployment("movement")
    public void getLatestMovementsByConnectIds() throws IOException {
        Movement movementBaseType = MovementTestHelper.createMovement();
        Movement createdMovement = movementService.createMovement(movementBaseType);

        assertNotNull(createdMovement.getId());

        UUID movConnectId = createdMovement.getMovementConnect().getId();

        String response = getWebTarget()
                .path("internal/latest")
                .request(MediaType.APPLICATION_JSON)
                .post(Entity.json(Collections.singletonList(movConnectId)), String.class);
        assertNotNull(response);

        List<MovementType> movements = Arrays.asList(mapper.readValue(response, MovementType[].class));

        assertNotNull(movements);
        assertEquals(1, movements.size());
    }


    @Test
    @OperateOnDeployment("movement")
    public void countMovementsInTheLastDayForAssetTest() {
        Movement movementBaseType = MovementTestHelper.createMovement();
        Movement createdMovement = movementService.createMovement(movementBaseType);
        Instant now = Instant.now().plusSeconds(60);

        long response = getWebTarget()
                .path("internal/countMovementsInDateAndTheDayBeforeForAsset/" + createdMovement.getMovementConnect().getId().toString())
                .queryParam("after", DateUtil.parseUTCDateToString(now))    //yyyy-MM-dd HH:mm:ss Z
                .request(MediaType.APPLICATION_JSON)
                .get(long.class);

        assertEquals(1, response);
    }

    @Test
    @OperateOnDeployment("movement")
    public void getMovementMapByQuery() throws IOException {
        Movement movementBaseType = MovementTestHelper.createMovement();
        Movement createdMovement = movementService.createMovement(movementBaseType);

        MovementQuery query = createMovementQuery(createdMovement);

        String response = getWebTarget()
                .path("internal/movementMapByQuery")
                .request(MediaType.APPLICATION_JSON)
                .post(Entity.json(query), String.class);
        assertNotNull(response);

        GetMovementMapByQueryResponse movMap = mapper.readValue(response, GetMovementMapByQueryResponse.class);
        assertNotNull(movMap);
        assertEquals(1, movMap.getMovementMap().size());
    }



    private MovementQuery createMovementQuery(Movement createdMovement) {
        MovementQuery query = new MovementQuery();
        if(createdMovement != null) {
            ListCriteria criteria = new ListCriteria();
            criteria.setKey(SearchKey.MOVEMENT_ID);
            criteria.setValue(createdMovement.getId().toString());
            query.getMovementSearchCriteria().add(criteria);
        } else {
            ListCriteria criteria = new ListCriteria();
            criteria.setKey(SearchKey.STATUS);
            criteria.setValue("TEST");
            query.getMovementSearchCriteria().add(criteria);

            ListPagination pagination = new ListPagination();
            pagination.setPage(BigInteger.ONE);
            pagination.setListSize(BigInteger.valueOf(100));
            query.setPagination(pagination);
        }
        return query;
    }
}
