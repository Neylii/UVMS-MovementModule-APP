package eu.europa.ec.fisheries.uvms.movement.service.message.consumer.bean;

import eu.europa.ec.fisheries.schema.exchange.module.v1.ProcessedMovementResponse;
import eu.europa.ec.fisheries.schema.exchange.movement.v1.MovementRefTypeType;
import eu.europa.ec.fisheries.schema.movement.module.v1.GetMovementListByQueryResponse;
import eu.europa.ec.fisheries.schema.movement.module.v1.PingResponse;
import eu.europa.ec.fisheries.schema.movement.search.v1.*;
import eu.europa.ec.fisheries.schema.movement.v1.MovementBaseType;
import eu.europa.ec.fisheries.schema.movement.v1.MovementSourceType;
import eu.europa.ec.fisheries.schema.movement.v1.MovementType;
import eu.europa.ec.fisheries.schema.movement.v1.MovementTypeType;
import eu.europa.ec.fisheries.uvms.commons.date.DateUtils;
import eu.europa.ec.fisheries.uvms.commons.message.api.MessageConstants;
import eu.europa.ec.fisheries.uvms.movement.service.BuildMovementServiceTestDeployment;
import eu.europa.ec.fisheries.uvms.movement.service.bean.MovementService;
import eu.europa.ec.fisheries.uvms.movement.service.constant.SatId;
import eu.europa.ec.fisheries.uvms.movement.service.entity.IncomingMovement;
import eu.europa.ec.fisheries.uvms.movement.service.entity.Movement;
import eu.europa.ec.fisheries.uvms.movement.service.message.JMSHelper;
import eu.europa.ec.fisheries.uvms.movement.service.message.MovementTestHelper;
import eu.europa.ec.fisheries.uvms.movement.service.util.JsonBConfiguratorMovement;
import eu.europa.ec.fisheries.uvms.movementrules.model.dto.MovementDetails;
import eu.europa.ec.fisheries.uvms.movementrules.model.mapper.JAXBMarshaller;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.junit.Arquillian;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.annotation.Resource;
import javax.inject.Inject;
import javax.jms.ConnectionFactory;
import javax.jms.Message;
import javax.jms.TextMessage;
import javax.json.bind.Jsonb;
import java.math.BigInteger;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;

@RunWith(Arquillian.class)
public class MovementMessageConsumerBeanTest extends BuildMovementServiceTestDeployment {

    @Resource(mappedName = "java:/ConnectionFactory")
    private ConnectionFactory connectionFactory;

    @Inject
    private MovementService movementService;

    private static Jsonb jsonb;
    private JMSHelper jmsHelper;

    @Before
    public void cleanJMS() throws Exception {
        jmsHelper = new JMSHelper(connectionFactory);
        jmsHelper.clearQueue("UVMSMovementRulesEvent");

        jsonb = new JsonBConfiguratorMovement().getContext(null);
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void pingMovement() throws Exception {
        PingResponse pingResponse = jmsHelper.pingMovement();
        assertThat(pingResponse.getResponse(), is("pong"));
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void createMovementConcurrentProcessing() throws Exception {
        int numberOfPositions = 20;
        String connectId = UUID.randomUUID().toString();

        Instant timestamp = Instant.now().minusSeconds(3600);

        // Send positions to movement
        for (int i = 0; i < numberOfPositions; i++) {
            IncomingMovement im = MovementTestHelper.createIncomingMovement(0d, 0d);
            im.setAssetHistoryId(connectId);
            im.setPositionTime(timestamp);
            timestamp = timestamp.plusSeconds(10);
            String json = jsonb.toJson(im);
            jmsHelper.sendMovementMessage(json, connectId, "CREATE");
        }

        Instant maxTime = Instant.now().plusSeconds(30);
        while (jmsHelper.checkQueueSize(MessageConstants.QUEUE_EXCHANGE_EVENT_NAME) < 20) {
            if (Instant.now().isAfter(maxTime)) {
                break;
            }
            Thread.sleep(100);
        }

        MovementQuery query = MovementTestHelper.createMovementQuery(true, false, false);
        query.getPagination().setListSize(BigInteger.valueOf(100L));
        ListCriteria criteria = new ListCriteria();
        criteria.setKey(SearchKey.CONNECT_ID);
        criteria.setValue(connectId);
        query.getMovementSearchCriteria().add(criteria);

        GetMovementListByQueryResponse movementResponse = jmsHelper.getMovementListByQuery(query, connectId);
        List<MovementType> movements = movementResponse.getMovement();

        movements.sort(Comparator.comparing(MovementBaseType::getPositionTime));
        MovementType previous = null;
        for (MovementType movementType : movements) {
            if (previous != null) {
                assertFalse(Collections.disjoint(previous.getSegmentIds(), movementType.getSegmentIds()));
            }
            previous = movementType;
        }
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void createMovementVerifyBasicData() throws Exception {
        IncomingMovement incomingMovement = MovementTestHelper.createIncomingMovementType();
        incomingMovement.setAssetGuid(null);
        incomingMovement.setAssetHistoryId(null);
        MovementDetails movementDetails = sendIncomingMovementAndWaitForResponse(incomingMovement);

        assertNotNull(movementDetails);
        assertNotNull(movementDetails.getMovementGuid());
        assertNotNull(movementDetails.getAssetGuid());
        assertNotNull(movementDetails.getConnectId());

        assertThat(movementDetails.getLongitude(), is(incomingMovement.getLongitude()));
        assertThat(movementDetails.getLatitude(), is(incomingMovement.getLatitude()));
        assertEquals(movementDetails.getPositionTime().toEpochMilli(), incomingMovement.getPositionTime().toEpochMilli());
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void createMovementVerifySatelliteTest() throws Exception {
        IncomingMovement incomingMovement = MovementTestHelper.createIncomingMovementType();
        incomingMovement.setAssetGuid(null);
        incomingMovement.setAssetHistoryId(null);
        Short satelliteId = 3;
        incomingMovement.setSourceSatelliteId(satelliteId);
        MovementDetails movementDetails = sendIncomingMovementAndWaitForResponse(incomingMovement);

        Movement createdMovement = movementService.getById(UUID.fromString(movementDetails.getMovementGuid()));
        assertEquals(SatId.fromInt(satelliteId.intValue()), createdMovement.getSourceSatelliteId());
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void createMovementVerifyAllBaseTypeData() throws Exception {
        IncomingMovement incomingMovement = MovementTestHelper.createIncomingMovementType();
        incomingMovement.setAssetGuid(null);
        incomingMovement.setAssetHistoryId(null);
        MovementDetails movementDetails = sendIncomingMovementAndWaitForResponse(incomingMovement);

        assertThat(movementDetails.getMovementGuid(), is(notNullValue()));
        assertNotNull(movementDetails.getConnectId());
        assertNotNull(movementDetails.getAssetGuid());
        assertTrue(movementDetails.isLongTermParked());

        assertThat(movementDetails.getLongitude(), is(incomingMovement.getLongitude()));
        assertThat(movementDetails.getLatitude(), is(incomingMovement.getLatitude()));
        assertEquals(movementDetails.getPositionTime().truncatedTo(ChronoUnit.MILLIS), incomingMovement.getPositionTime().truncatedTo(ChronoUnit.MILLIS));
        assertThat(movementDetails.getStatusCode(), is(incomingMovement.getStatus()));
        assertThat(movementDetails.getReportedSpeed(), is(incomingMovement.getReportedSpeed()));
        assertThat(movementDetails.getReportedCourse(), is(incomingMovement.getReportedCourse()));
        assertThat(movementDetails.getMovementType(), is(incomingMovement.getMovementType()));
        assertThat(movementDetails.getSource(), is(incomingMovement.getMovementSourceType()));
        assertThat(movementDetails.getTripNumber(), is(incomingMovement.getTripNumber()));
        assertThat(movementDetails.getInternalReferenceNumber(), is(incomingMovement.getInternalReferenceNumber()));
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void createMovementVerifyNullAltitudeData() throws Exception {
        IncomingMovement incomingMovement = MovementTestHelper.createIncomingMovementType();
        incomingMovement.setAssetGuid(null);
        incomingMovement.setAssetHistoryId(null);
        incomingMovement.setAltitude(null);
        MovementDetails movementDetails = sendIncomingMovementAndWaitForResponse(incomingMovement);
        assertThat(movementDetails.getMovementGuid(), is(notNullValue()));
        assertThat(movementDetails.getAltitude(), is(incomingMovement.getAltitude()));
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void createMovementVerifyWKTData() throws Exception {
        IncomingMovement incomingMovement = MovementTestHelper.createIncomingMovementType();
        incomingMovement.setAssetGuid(null);
        incomingMovement.setAssetHistoryId(null);
        MovementDetails movementDetails = sendIncomingMovementAndWaitForResponse(incomingMovement);
        assertThat(movementDetails.getMovementGuid(), is(notNullValue()));
        assertThat(movementDetails.getWkt(), is(notNullValue()));
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void createMovementVerifyCalculatedData() throws Exception {
        String uuid = UUID.randomUUID().toString();

        IncomingMovement incomingMovement = MovementTestHelper.createIncomingMovementType();
        incomingMovement.setAssetGuid(null);
        incomingMovement.setAssetHistoryId("TestIrcs");
        incomingMovement.setPositionTime(Instant.now().minusSeconds(10));
        incomingMovement.setAssetIRCS("TestIrcs:" + uuid); //I set the asset mocker up so that TestIrcs returns the id behind the :
        MovementDetails movementDetails = sendIncomingMovementAndWaitForResponse(incomingMovement);

        IncomingMovement incomingMovement2 = MovementTestHelper.createIncomingMovementType();
        incomingMovement2.setAssetGuid(null);
        incomingMovement2.setAssetHistoryId("TestIrcs");
        incomingMovement2.setAssetIRCS("TestIrcs:" + uuid);
        MovementDetails movementDetails2 = sendIncomingMovementAndWaitForResponse(incomingMovement2);

        assertThat(movementDetails2.getMovementGuid(), is(notNullValue()));
        assertThat(movementDetails2.getCalculatedSpeed(), is(notNullValue()));
        assertThat(movementDetails2.getCalculatedCourse(), is(notNullValue()));
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void createMovementVerifyBasicSegment() throws Exception {
        String uuid = UUID.randomUUID().toString();

        IncomingMovement incomingMovement = MovementTestHelper.createIncomingMovementType();
        incomingMovement.setAssetGuid(uuid);
        incomingMovement.setAssetHistoryId(uuid);
        incomingMovement.setAssetIRCS("TestIrcs:" + uuid); //I set the asset mocker up so that TestIrcs returns the id behind the :
        MovementDetails movementDetails = sendIncomingMovementAndWaitForResponse(incomingMovement);

        IncomingMovement incomingMovement2 = MovementTestHelper.createIncomingMovementType();
        incomingMovement2.setAssetGuid(uuid);
        incomingMovement2.setAssetHistoryId(uuid);
        incomingMovement2.setAssetIRCS("TestIrcs:" + uuid);
        MovementDetails movementDetails2 = sendIncomingMovementAndWaitForResponse(incomingMovement2);

        MovementQuery query = MovementTestHelper.createMovementQuery(true, false, false);
        ListCriteria criteria = new ListCriteria();
        criteria.setKey(SearchKey.CONNECT_ID);
        criteria.setValue(movementDetails2.getConnectId());
        query.getMovementSearchCriteria().add(criteria);
        GetMovementListByQueryResponse movementList = jmsHelper.getMovementListByQuery(query, movementDetails2.getConnectId());
        List<MovementType> movements = movementList.getMovement();

        assertThat(movements.size(), is(2));
        assertThat(movements.get(0).getSegmentIds(), is(movements.get(1).getSegmentIds()));
    }

    @Test
    @Ignore("This one needs create batch functionality")
    @OperateOnDeployment("movementservice")
    public void createMovementBatchTest() throws Exception {
        /*
        JMSHelper jmsHelper = new JMSHelper(connectionFactory);
        MovementBaseType m1 = MovementTestHelper.createIncomingMovementType(0d, 0d);
        MovementBaseType m2 = MovementTestHelper.createIncomingMovementType(0d, 1d);
        MovementBaseType m3 = MovementTestHelper.createIncomingMovementType(0d, 2d);
        CreateMovementBatchResponse response = jmsHelper.createMovementBatch(Arrays.asList(m1, m2, m3), "test user");
        List<MovementType> createdMovement = response.getMovements();
        assertThat(createdMovement.size(), is(3));*/
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void createMovementVerifyPreviousPosition() throws Exception {
        UUID assetHistoryId = UUID.randomUUID();
        Double firstLongitude = 3d;
        Double firstLatitude = 4d;
        IncomingMovement firstIncomingMovement = MovementTestHelper.createIncomingMovementType();
        firstIncomingMovement.setAssetHistoryId(assetHistoryId.toString());
        firstIncomingMovement.setAssetIRCS("TestIrcs:" + assetHistoryId);
        firstIncomingMovement.setLongitude(firstLongitude);
        firstIncomingMovement.setLatitude(firstLatitude);
        MovementDetails firstMovementDetails = sendIncomingMovementAndWaitForResponse(firstIncomingMovement);

        assertThat(firstMovementDetails.getPreviousLatitude(), is(nullValue()));
        assertThat(firstMovementDetails.getPreviousLongitude(), is(nullValue()));

        IncomingMovement secondIncomingMovement = MovementTestHelper.createIncomingMovementType();
        secondIncomingMovement.setAssetHistoryId(assetHistoryId.toString());
        secondIncomingMovement.setAssetIRCS("TestIrcs:" + assetHistoryId);
        MovementDetails secondMovementDetails = sendIncomingMovementAndWaitForResponse(secondIncomingMovement);

        assertThat(secondMovementDetails.getPreviousLatitude(), is(firstLatitude));
        assertThat(secondMovementDetails.getPreviousLongitude(), is(firstLongitude));
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void createMovementVerifyPreviousVMSPosition() throws Exception {
        UUID assetHistoryId = UUID.randomUUID();
        Double firstLongitude = 3d;
        Double firstLatitude = 4d;
        IncomingMovement firstIncomingMovement = MovementTestHelper.createIncomingMovementType();
        firstIncomingMovement.setMovementSourceType("NAF");
        firstIncomingMovement.setAssetHistoryId(assetHistoryId.toString());
        firstIncomingMovement.setAssetIRCS("TestIrcs:" + assetHistoryId);
        firstIncomingMovement.setLongitude(firstLongitude);
        firstIncomingMovement.setLatitude(firstLatitude);
        MovementDetails firstMovementDetails = sendIncomingMovementAndWaitForResponse(firstIncomingMovement);

        assertThat(firstMovementDetails.getPreviousVMSLatitude(), is(nullValue()));
        assertThat(firstMovementDetails.getPreviousVMSLongitude(), is(nullValue()));

        IncomingMovement secondIncomingMovement = MovementTestHelper.createIncomingMovementType();
        secondIncomingMovement.setMovementSourceType("NAF");
        secondIncomingMovement.setAssetHistoryId(assetHistoryId.toString());
        secondIncomingMovement.setAssetIRCS("TestIrcs:" + assetHistoryId);
        MovementDetails secondMovementDetails = sendIncomingMovementAndWaitForResponse(secondIncomingMovement);

        assertThat(secondMovementDetails.getPreviousVMSLatitude(), is(firstLatitude));
        assertThat(secondMovementDetails.getPreviousVMSLongitude(), is(firstLongitude));
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void createExitMovementVerifyPreviousVMSPositionData() throws Exception {
        UUID assetHistoryId = UUID.randomUUID();
        Double firstLongitude = 3d;
        Double firstLatitude = 4d;
        IncomingMovement firstIncomingMovement = MovementTestHelper.createIncomingMovementType();
        firstIncomingMovement.setMovementSourceType("NAF");
        firstIncomingMovement.setAssetIRCS("TestIrcs:" + assetHistoryId);
        firstIncomingMovement.setLongitude(firstLongitude);
        firstIncomingMovement.setLatitude(firstLatitude);
        MovementDetails firstMovementDetails = sendIncomingMovementAndWaitForResponse(firstIncomingMovement);

        assertThat(firstMovementDetails.getLatitude(), is(notNullValue()));
        assertThat(firstMovementDetails.getLongitude(), is(notNullValue()));

        IncomingMovement secondIncomingMovement = MovementTestHelper.createIncomingMovementType();
        secondIncomingMovement.setMovementSourceType("NAF");
        secondIncomingMovement.setAssetIRCS("TestIrcs:" + assetHistoryId);
        secondIncomingMovement.setMovementType(MovementTypeType.EXI.value());
        firstIncomingMovement.setLongitude(null);
        firstIncomingMovement.setLatitude(null);
        MovementDetails secondMovementDetails = sendIncomingMovementAndWaitForResponse(secondIncomingMovement);

        assertThat(secondMovementDetails.getLatitude(), is(notNullValue()));
        assertEquals(firstLatitude, secondMovementDetails.getLatitude(), 0);
        assertThat(secondMovementDetails.getLongitude(), is(notNullValue()));
        assertEquals(firstLongitude, secondMovementDetails.getLongitude(), 0);
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void createMovementVerifyPreviousVMSPositionWithMixedPositions() throws Exception {
        UUID assetHistoryId = UUID.randomUUID();
        Double firstLongitude = 3d;
        Double firstLatitude = 4d;
        IncomingMovement firstIncomingMovement = MovementTestHelper.createIncomingMovementType();
        firstIncomingMovement.setMovementSourceType("NAF");
        firstIncomingMovement.setAssetHistoryId(assetHistoryId.toString());
        firstIncomingMovement.setAssetIRCS("TestIrcs:" + assetHistoryId);
        firstIncomingMovement.setLongitude(firstLongitude);
        firstIncomingMovement.setLatitude(firstLatitude);
        MovementDetails firstMovementDetails = sendIncomingMovementAndWaitForResponse(firstIncomingMovement);

        assertThat(firstMovementDetails.getPreviousVMSLatitude(), is(nullValue()));
        assertThat(firstMovementDetails.getPreviousVMSLongitude(), is(nullValue()));

        IncomingMovement secondIncomingMovement = MovementTestHelper.createIncomingMovementType();
        secondIncomingMovement.setMovementSourceType("AIS");
        secondIncomingMovement.setAssetHistoryId(assetHistoryId.toString());
        secondIncomingMovement.setAssetIRCS("TestIrcs:" + assetHistoryId);
        MovementDetails secondMovementDetails = sendIncomingMovementAndWaitForResponse(secondIncomingMovement);

        assertThat(secondMovementDetails.getPreviousVMSLatitude(), is(nullValue()));
        assertThat(secondMovementDetails.getPreviousVMSLongitude(), is(nullValue()));

        IncomingMovement thirdIncomingMovement = MovementTestHelper.createIncomingMovementType();
        thirdIncomingMovement.setMovementSourceType("NAF");
        thirdIncomingMovement.setAssetHistoryId(assetHistoryId.toString());
        thirdIncomingMovement.setAssetIRCS("TestIrcs:" + assetHistoryId);
        MovementDetails thirdMovementDetails = sendIncomingMovementAndWaitForResponse(thirdIncomingMovement);

        assertThat(thirdMovementDetails.getPreviousVMSLatitude(), is(firstLatitude));
        assertThat(thirdMovementDetails.getPreviousVMSLongitude(), is(firstLongitude));
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void createMovementVerifyPreviousVMSPositionWithAISPositions() throws Exception {
        UUID assetHistoryId = UUID.randomUUID();
        Double firstLongitude = 3d;
        Double firstLatitude = 4d;
        IncomingMovement firstIncomingMovement = MovementTestHelper.createIncomingMovementType();
        firstIncomingMovement.setMovementSourceType("AIS");
        firstIncomingMovement.setAssetHistoryId(assetHistoryId.toString());
        firstIncomingMovement.setAssetIRCS("TestIrcs:" + assetHistoryId);
        firstIncomingMovement.setLongitude(firstLongitude);
        firstIncomingMovement.setLatitude(firstLatitude);
        MovementDetails firstMovementDetails = sendIncomingMovementAndWaitForResponse(firstIncomingMovement);

        assertThat(firstMovementDetails.getPreviousVMSLatitude(), is(nullValue()));
        assertThat(firstMovementDetails.getPreviousVMSLongitude(), is(nullValue()));

        IncomingMovement secondIncomingMovement = MovementTestHelper.createIncomingMovementType();
        secondIncomingMovement.setMovementSourceType("AIS");
        secondIncomingMovement.setAssetHistoryId(assetHistoryId.toString());
        secondIncomingMovement.setAssetIRCS("TestIrcs:" + assetHistoryId);
        MovementDetails secondMovementDetails = sendIncomingMovementAndWaitForResponse(secondIncomingMovement);

        assertThat(secondMovementDetails.getPreviousVMSLatitude(), is(nullValue()));
        assertThat(secondMovementDetails.getPreviousVMSLongitude(), is(nullValue()));
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void createMovementVerifySumPositionReport() throws Exception {
        UUID assetHistoryId = UUID.randomUUID();
        IncomingMovement firstIncomingMovement = MovementTestHelper.createIncomingMovementType();
        firstIncomingMovement.setAssetIRCS("TestIrcs:" + assetHistoryId);
        MovementDetails firstMovementDetails = sendIncomingMovementAndWaitForResponse(firstIncomingMovement);

        assertThat(firstMovementDetails.getSumPositionReport(), is(1));

        IncomingMovement secondIncomingMovement = MovementTestHelper.createIncomingMovementType();
        secondIncomingMovement.setAssetIRCS("TestIrcs:" + assetHistoryId);
        MovementDetails secondMovementDetails = sendIncomingMovementAndWaitForResponse(secondIncomingMovement);

        assertThat(secondMovementDetails.getSumPositionReport(), is(2));
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void createMovementVerifySumPositionReportTwoDayGap() throws Exception {
        UUID assetHistoryId = UUID.randomUUID();
        IncomingMovement firstIncomingMovement = MovementTestHelper.createIncomingMovementType();
        firstIncomingMovement.setAssetIRCS("TestIrcs:" + assetHistoryId);
        firstIncomingMovement.setPositionTime(Instant.now().minus(2, ChronoUnit.DAYS));
        sendIncomingMovementAndWaitForResponse(firstIncomingMovement);

        IncomingMovement secondIncomingMovement = MovementTestHelper.createIncomingMovementType();
        secondIncomingMovement.setAssetIRCS("TestIrcs:" + assetHistoryId);
        MovementDetails secondMovementDetails = sendIncomingMovementAndWaitForResponse(secondIncomingMovement);

        assertThat(secondMovementDetails.getSumPositionReport(), is(1));
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void createDuplicateMovement() throws Exception {
        UUID assetHistoryId = UUID.randomUUID();
        IncomingMovement movement = MovementTestHelper.createIncomingMovementType();
        movement.setAssetIRCS("TestIrcs:" + assetHistoryId);
        movement.setPositionTime(Instant.now().minus(2, ChronoUnit.DAYS));
        sendIncomingMovementAndWaitForResponse(movement);

        jmsHelper.clearQueue(MessageConstants.QUEUE_EXCHANGE_EVENT_NAME);
        ProcessedMovementResponse response = sendIncomingMovementAndReturnAlarmResponse(movement);
        assertThat(response.getMovementRefType().getType(), is(MovementRefTypeType.ALARM));
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void createDuplicateMovementDifferentSource() throws Exception {
        UUID assetHistoryId = UUID.randomUUID();
        IncomingMovement movement = MovementTestHelper.createIncomingMovementType();
        movement.setAssetIRCS("TestIrcs:" + assetHistoryId);
        movement.setPositionTime(Instant.now().minus(2, ChronoUnit.DAYS));
        movement.setMovementSourceType(MovementSourceType.AIS.value());
        sendIncomingMovementAndWaitForResponse(movement);

        movement.setMovementSourceType(MovementSourceType.INMARSAT_C.value());
        sendIncomingMovementAndWaitForResponse(movement);

        MovementQuery query = MovementTestHelper.createMovementQuery(true, false, false);
        ListCriteria criteria = new ListCriteria();
        criteria.setKey(SearchKey.CONNECT_ID);
        criteria.setValue(assetHistoryId.toString());
        query.getMovementSearchCriteria().add(criteria);

        GetMovementListByQueryResponse listByQueryResponse = jmsHelper.getMovementListByQuery(query, movement.getAssetGuid());
        List<MovementType> movements = listByQueryResponse.getMovement();
        assertThat(movements.size(), is(2));

        ListCriteria criteria2 = new ListCriteria();
        criteria2.setKey(SearchKey.SOURCE);
        criteria2.setValue(MovementSourceType.INMARSAT_C.value());
        query.getMovementSearchCriteria().add(criteria2);

        GetMovementListByQueryResponse listByQueryResponse2 = jmsHelper.getMovementListByQuery(query, movement.getAssetGuid());
        List<MovementType> movements2 = listByQueryResponse2.getMovement();
        assertThat(movements2.size(), is(1));
        assertThat(movements2.get(0).getPositionTime(), is(Date.from(movement.getPositionTime())));

        criteria2.setValue(MovementSourceType.AIS.value());

        GetMovementListByQueryResponse listByQueryResponse3 = jmsHelper.getMovementListByQuery(query, movement.getAssetGuid());
        List<MovementType> movements3 = listByQueryResponse3.getMovement();
        assertThat(movements3.size(), is(1));
        assertThat(movements3.get(0).getPositionTime(), is(Date.from(movement.getPositionTime().plus(1, ChronoUnit.SECONDS))));
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void getMovementListByConnectId() throws Exception {
        UUID groupId = UUID.randomUUID();
        IncomingMovement incomingMovement = MovementTestHelper.createIncomingMovementType();
        incomingMovement.setAssetGuid(null);
        incomingMovement.setAssetHistoryId(null);
        MovementDetails movementDetails = sendIncomingMovementAndWaitForResponse(incomingMovement, groupId.toString());

        MovementQuery query = MovementTestHelper.createMovementQuery(true, false, false);
        ListCriteria criteria = new ListCriteria();
        criteria.setKey(SearchKey.CONNECT_ID);
        criteria.setValue(movementDetails.getConnectId());
        query.getMovementSearchCriteria().add(criteria);

        GetMovementListByQueryResponse listByQueryResponse = jmsHelper.getMovementListByQuery(query, groupId.toString());
        List<MovementType> movements = listByQueryResponse.getMovement();
        assertThat(movements.size(), is(1));
        assertThat(movements.get(0).getConnectId(), is(movementDetails.getConnectId()));
        assertThat(movements.get(0).getPosition().getLongitude(), is(incomingMovement.getLongitude()));
        assertThat(movements.get(0).getPosition().getLatitude(), is(incomingMovement.getLatitude()));
        assertThat(movements.get(0).getPositionTime().getTime(), is(incomingMovement.getPositionTime().toEpochMilli()));
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void getMovementListByConnectIdTwoPositions() throws Exception {
        JMSHelper jmsHelper = new JMSHelper(connectionFactory);
        String uuid = UUID.randomUUID().toString();
        Instant timestamp = Instant.now();

        IncomingMovement incomingMovement = MovementTestHelper.createIncomingMovementType();
        incomingMovement.setAssetGuid(null);
        incomingMovement.setAssetHistoryId("TestIrcs");
        incomingMovement.setAssetIRCS("TestIrcs:" + uuid);
        incomingMovement.setPositionTime(timestamp.minusSeconds(10));
        MovementDetails movementDetails = sendIncomingMovementAndWaitForResponse(incomingMovement, uuid);

        IncomingMovement incomingMovement2 = MovementTestHelper.createIncomingMovementType();
        incomingMovement2.setAssetGuid(null);
        incomingMovement2.setAssetHistoryId("TestIrcs");
        incomingMovement2.setAssetIRCS("TestIrcs:" + uuid);  //I set the asset mocker up so that TestIrcs returns the id behind the :
        MovementDetails movementDetails2 = sendIncomingMovementAndWaitForResponse(incomingMovement2, uuid);

        MovementQuery query = MovementTestHelper.createMovementQuery(true, false, false);
        ListCriteria criteria = new ListCriteria();
        criteria.setKey(SearchKey.CONNECT_ID);
        criteria.setValue(uuid);
        query.getMovementSearchCriteria().add(criteria);

        GetMovementListByQueryResponse listByQueryResponse = jmsHelper.getMovementListByQuery(query, uuid);
        List<MovementType> movements = listByQueryResponse.getMovement();
        assertThat(movements.size(), is(2));
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void getMovementListByConnectIdDifferentIds() throws Exception {
        String grouping = UUID.randomUUID().toString();
        IncomingMovement incomingMovement = MovementTestHelper.createIncomingMovementType();
        incomingMovement.setAssetGuid(null);
        incomingMovement.setAssetHistoryId(null);
        MovementDetails movementDetails = sendIncomingMovementAndWaitForResponse(incomingMovement, grouping);

        MovementDetails movementDetails2 = sendIncomingMovementAndWaitForResponse(incomingMovement, grouping);

        MovementQuery query = MovementTestHelper.createMovementQuery(true, false, false);
        ListCriteria criteria1 = new ListCriteria();
        criteria1.setKey(SearchKey.CONNECT_ID);
        criteria1.setValue(movementDetails.getConnectId());
        query.getMovementSearchCriteria().add(criteria1);
        ListCriteria criteria2 = new ListCriteria();
        criteria2.setKey(SearchKey.CONNECT_ID);
        criteria2.setValue(movementDetails2.getConnectId());
        query.getMovementSearchCriteria().add(criteria2);

        GetMovementListByQueryResponse listByQueryResponse = jmsHelper.getMovementListByQuery(query, grouping);
        List<MovementType> movements = listByQueryResponse.getMovement();
        assertThat(movements.size(), is(2));
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void getMovementListByMovementId() throws Exception {
        String grouping = UUID.randomUUID().toString();
        IncomingMovement incomingMovement = MovementTestHelper.createIncomingMovementType();
        incomingMovement.setAssetGuid(null);
        incomingMovement.setAssetHistoryId(null);
        MovementDetails movementDetails = sendIncomingMovementAndWaitForResponse(incomingMovement, grouping);

        MovementQuery query = MovementTestHelper.createMovementQuery(true, false, false);
        ListCriteria criteria = new ListCriteria();
        criteria.setKey(SearchKey.MOVEMENT_ID);
        criteria.setValue(movementDetails.getMovementGuid());
        query.getMovementSearchCriteria().add(criteria);

        GetMovementListByQueryResponse listByQueryResponse = jmsHelper.getMovementListByQuery(query, grouping);
        List<MovementType> movements = listByQueryResponse.getMovement();
        assertThat(movements.size(), is(1));
        assertThat(movements.get(0).getConnectId(), is(movementDetails.getConnectId()));
        assertThat(movements.get(0).getPosition().getLongitude(), is(movementDetails.getLongitude()));
        assertThat(movements.get(0).getPosition().getLatitude(), is(movementDetails.getLatitude()));
        assertThat(movements.get(0).getPositionTime().getTime(), is(movementDetails.getPositionTime().toEpochMilli()));
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void getMovementListByMovementIdTwoMovements() throws Exception {
        String grouping = UUID.randomUUID().toString();
        IncomingMovement incomingMovement = MovementTestHelper.createIncomingMovementType();
        incomingMovement.setAssetGuid(null);
        incomingMovement.setAssetHistoryId(null);
        MovementDetails movementDetails = sendIncomingMovementAndWaitForResponse(incomingMovement, grouping);

        IncomingMovement incomingMovement2 = MovementTestHelper.createIncomingMovement(3d, 4d);
        incomingMovement2.setAssetGuid(null);
        incomingMovement2.setAssetHistoryId(null);
        MovementDetails movementDetails2 = sendIncomingMovementAndWaitForResponse(incomingMovement2, grouping);

        MovementQuery query = MovementTestHelper.createMovementQuery(true, false, false);
        ListCriteria criteria1 = new ListCriteria();
        criteria1.setKey(SearchKey.MOVEMENT_ID);
        criteria1.setValue(movementDetails.getMovementGuid());
        query.getMovementSearchCriteria().add(criteria1);
        ListCriteria criteria2 = new ListCriteria();
        criteria2.setKey(SearchKey.MOVEMENT_ID);
        criteria2.setValue(movementDetails2.getMovementGuid());
        query.getMovementSearchCriteria().add(criteria2);

        GetMovementListByQueryResponse listByQueryResponse = jmsHelper.getMovementListByQuery(query, grouping);
        List<MovementType> movements = listByQueryResponse.getMovement();
        assertThat(movements.size(), is(2));
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void getMovementListByDateFromRange() throws Exception {
        Instant timestampBefore = Instant.now().minusSeconds(1);
        String grouping = UUID.randomUUID().toString();

        IncomingMovement incomingMovement = MovementTestHelper.createIncomingMovementType();
        incomingMovement.setAssetGuid(null);
        incomingMovement.setAssetHistoryId(null);
        MovementDetails movementDetails = sendIncomingMovementAndWaitForResponse(incomingMovement, grouping);

        Instant timestampAfter = Instant.now().plusSeconds(1);

        MovementQuery query = MovementTestHelper.createMovementQuery(true, false, false);
        RangeCriteria criteria = new RangeCriteria();
        criteria.setKey(RangeKeyType.DATE);
        criteria.setFrom(DateUtils.dateToEpochMilliseconds(timestampBefore));
        criteria.setTo(DateUtils.dateToEpochMilliseconds(timestampAfter));
        query.getMovementRangeSearchCriteria().add(criteria);
        ListCriteria criteria1 = new ListCriteria();
        criteria1.setKey(SearchKey.CONNECT_ID);
        criteria1.setValue(movementDetails.getConnectId());
        query.getMovementSearchCriteria().add(criteria1);

        GetMovementListByQueryResponse listByQueryResponse = jmsHelper.getMovementListByQuery(query, grouping);
        List<MovementType> movements = listByQueryResponse.getMovement();
        assertThat(movements.size(), is(1));
        assertThat(movements.get(0).getConnectId(), is(movementDetails.getConnectId()));
        assertThat(movements.get(0).getPosition().getLongitude(), is(movementDetails.getLongitude()));
        assertThat(movements.get(0).getPosition().getLatitude(), is(movementDetails.getLatitude()));
        assertThat(movements.get(0).getPositionTime().toInstant().truncatedTo(ChronoUnit.MILLIS),
                is(movementDetails.getPositionTime().truncatedTo(ChronoUnit.MILLIS)));
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void getMovementListByDateTwoMovements() throws Exception {
        Instant timestampBefore = Instant.now().minusSeconds(60);

        String grouping = UUID.randomUUID().toString();
        IncomingMovement incomingMovement = MovementTestHelper.createIncomingMovementType();
        incomingMovement.setAssetGuid(null);
        incomingMovement.setAssetHistoryId(null);
        MovementDetails movementDetails = sendIncomingMovementAndWaitForResponse(incomingMovement, grouping);

        IncomingMovement incomingMovement2 = MovementTestHelper.createIncomingMovementType();
        incomingMovement2.setAssetGuid(null);
        incomingMovement2.setAssetHistoryId(null);
        MovementDetails movementDetails2 = sendIncomingMovementAndWaitForResponse(incomingMovement2, grouping);

        Instant timestampAfter = Instant.now().plusSeconds(60);

        MovementQuery query = MovementTestHelper.createMovementQuery(true, false, false);
        RangeCriteria criteria = new RangeCriteria();
        criteria.setKey(RangeKeyType.DATE);
        criteria.setFrom(DateUtils.dateToEpochMilliseconds(timestampBefore));
        criteria.setTo(DateUtils.dateToEpochMilliseconds(timestampAfter));
        query.getMovementRangeSearchCriteria().add(criteria);
        ListCriteria criteria1 = new ListCriteria();
        criteria1.setKey(SearchKey.CONNECT_ID);
        criteria1.setValue(movementDetails.getConnectId());
        query.getMovementSearchCriteria().add(criteria1);
        ListCriteria criteria2 = new ListCriteria();
        criteria2.setKey(SearchKey.CONNECT_ID);
        criteria2.setValue(movementDetails2.getConnectId());
        query.getMovementSearchCriteria().add(criteria2);

        GetMovementListByQueryResponse listByQueryResponse = jmsHelper.getMovementListByQuery(query, grouping);
        List<MovementType> movements = listByQueryResponse.getMovement();
        assertThat(movements.size(), is(2));
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void createMovementConcurrentProcessingTwoConnectIds() throws Exception {
        int numberOfPositions = 20;
        String connectId1 = UUID.randomUUID().toString();
        String connectId2 = UUID.randomUUID().toString();

        List<String> correlationIds = new ArrayList<>();

        Instant timestamp = Instant.now().minusSeconds(60 * 60);  //to avoid the sanity rule "time in future"

        // Send positions to movement
        for (int i = 0; i < numberOfPositions; i++) {
            IncomingMovement incomingMovement = MovementTestHelper.createIncomingMovementType();
            incomingMovement.setAssetGuid(null);
            incomingMovement.setAssetHistoryId(null);
            incomingMovement.setPositionTime(timestamp);
            incomingMovement.setAssetIRCS("TestIrcs:" + connectId1); //I set the asset mocker up so that TestIrcs returns the id behind the :
            String json = jsonb.toJson(incomingMovement);
            jmsHelper.sendMovementMessage(json, "Grouping:1", "CREATE");

            IncomingMovement incomingMovement2 = MovementTestHelper.createIncomingMovementType();
            incomingMovement2.setAssetGuid(null);
            incomingMovement2.setAssetHistoryId(null);
            incomingMovement2.setPositionTime(timestamp);
            incomingMovement2.setAssetIRCS("TestIrcs:" + connectId2); //I set the asset mocker up so that TestIrcs returns the id behind the :
            String json2 = jsonb.toJson(incomingMovement2);
            jmsHelper.sendMovementMessage(json2, "Grouping:2", "CREATE");

            timestamp = timestamp.plusSeconds(10);
        }

        // Check responses
        for (int i = 0; i < numberOfPositions; i++) {
            TextMessage response = (TextMessage) jmsHelper.listenOnMRQueue();
            String jsonResponse = response.getText();
            MovementDetails movementDetails = jsonb.fromJson(jsonResponse, MovementDetails.class);

            response = (TextMessage) jmsHelper.listenOnMRQueue();
            jsonResponse = response.getText();
            movementDetails = jsonb.fromJson(jsonResponse, MovementDetails.class);
        }

        MovementQuery query = MovementTestHelper.createMovementQuery(true, false, false);
        query.getPagination().setListSize(BigInteger.valueOf(100L));
        ListCriteria criteria = new ListCriteria();
        criteria.setKey(SearchKey.CONNECT_ID);
        criteria.setValue(connectId1);
        query.getMovementSearchCriteria().add(criteria);

        GetMovementListByQueryResponse movementResponse = jmsHelper.getMovementListByQuery(query, connectId1);
        List<MovementType> movements = movementResponse.getMovement();

        movements.sort(Comparator.comparing(MovementBaseType::getPositionTime));
        MovementType previous = null;
        for (MovementType movementType : movements) {
            if (previous != null) {
                assertFalse(Collections.disjoint(previous.getSegmentIds(), movementType.getSegmentIds()));
            }
            previous = movementType;
        }

        MovementQuery query2 = MovementTestHelper.createMovementQuery(true, false, false);
        query2.getPagination().setListSize(BigInteger.valueOf(100L));
        ListCriteria criteria2 = new ListCriteria();
        criteria2.setKey(SearchKey.CONNECT_ID);
        criteria2.setValue(connectId2);
        query2.getMovementSearchCriteria().add(criteria2);

        GetMovementListByQueryResponse movementResponse2 = jmsHelper.getMovementListByQuery(query2, connectId2);
        List<MovementType> movements2 = movementResponse2.getMovement();

        movements2.sort(Comparator.comparing(MovementBaseType::getPositionTime));
        MovementType previous2 = null;
        for (MovementType movementType : movements2) {
            if (previous2 != null) {
                assertFalse(Collections.disjoint(previous2.getSegmentIds(), movementType.getSegmentIds()));
            }
            previous2 = movementType;
        }
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void testMaxRedeliveries() throws Exception {
        jmsHelper.clearQueue("DLQ");
        int responseQueueBefore = jmsHelper.checkQueueSize(JMSHelper.RESPONSE_QUEUE);

        IncomingMovement incomingMovement = MovementTestHelper.createIncomingMovementType();
        incomingMovement.setAssetGuid(null);
        incomingMovement.setAssetHistoryId(null);
        incomingMovement.setPluginType(null);
        incomingMovement.setMovementSourceType(null);
        String json = jsonb.toJson(incomingMovement);
        jmsHelper.sendMovementMessage(json, incomingMovement.getAssetGuid(), "CREATE");   //grouping on null..

        Message dlqMessage = jmsHelper.listenOnQueue("DLQ");
        int responseQueueAfter = jmsHelper.checkQueueSize(JMSHelper.RESPONSE_QUEUE);

        assertThat(dlqMessage, is(notNullValue()));
        assertThat(responseQueueBefore, is(responseQueueAfter));
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void setMovementTypeTest() throws Exception {
        IncomingMovement incomingMovement = MovementTestHelper.createIncomingMovementType();
        incomingMovement.setAssetGuid(null);
        incomingMovement.setAssetHistoryId(null);
        incomingMovement.setMovementType(null);
        MovementDetails movementDetails = sendIncomingMovementAndWaitForResponse(incomingMovement);

        assertNotNull(movementDetails.getMovementType());
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void vicinityOfBasicTest() throws Exception {
        UUID connectId = UUID.randomUUID();
        IncomingMovement incomingMovement = MovementTestHelper.createIncomingMovementType();
        incomingMovement.setAssetGuid(null);
        incomingMovement.setAssetHistoryId(null);
        incomingMovement.setAssetIRCS("TestIrcs:" + connectId);
        incomingMovement.setLongitude(Math.random() * 360d - 180d);
        incomingMovement.setLatitude(Math.random() * 180d - 90d);
        incomingMovement.setPositionTime(Instant.now().minusSeconds(60));
        MovementDetails movementDetails = sendIncomingMovementAndWaitForResponse(incomingMovement);

        assertNotNull(movementDetails);
        assertNotNull(movementDetails.getVicinityOf());
        assertTrue(movementDetails.getVicinityOf().isEmpty());

        incomingMovement.setPositionTime(Instant.now().minusSeconds(30));
        movementDetails = sendIncomingMovementAndWaitForResponse(incomingMovement);

        assertNotNull(movementDetails);
        assertNotNull(movementDetails.getVicinityOf());
        assertTrue(movementDetails.getVicinityOf().isEmpty());
    }

    @Test
    @OperateOnDeployment("movementservice")
    public void vicinityOfSeveralBoatsTest() throws Exception {
        double lon = Math.random() * 360d - 180d;
        double lat = Math.random() * 180d - 90d;
        UUID connectId = UUID.randomUUID();

        IncomingMovement incomingMovement = MovementTestHelper.createIncomingMovementType();
        incomingMovement.setAssetGuid(null);
        incomingMovement.setAssetHistoryId(null);
        incomingMovement.setLongitude(lon);
        incomingMovement.setLatitude(lat);
        // this is bc we save history id in movementsDB but send asset id to MR,
        // setting it like this makes both asset id and asset history id to be the same
        incomingMovement.setAssetIRCS("TestIrcs:" + connectId);
        incomingMovement.setPositionTime(Instant.now().minusSeconds(60));
        MovementDetails movementDetails1 = sendIncomingMovementAndWaitForResponse(incomingMovement);

        assertNotNull(movementDetails1);
        assertNotNull(movementDetails1.getVicinityOf());
        assertTrue("" + movementDetails1.getVicinityOf().size(), movementDetails1.getVicinityOf().isEmpty());

        Thread.sleep(1000);
        incomingMovement.setLongitude(lon + 0.0001);
        incomingMovement.setLatitude(lat + 0.0001);
        incomingMovement.setAssetIRCS(null);
        incomingMovement.setPositionTime(Instant.now().minusSeconds(30));
        MovementDetails movementDetails2 = sendIncomingMovementAndWaitForResponse(incomingMovement);

        assertNotNull(movementDetails2);
        assertNotNull(movementDetails2.getVicinityOf());
        assertEquals(1, movementDetails2.getVicinityOf().size());
        assertEquals(movementDetails1.getAssetGuid(), movementDetails2.getVicinityOf().get(0).getAsset());
        assertTrue(movementDetails2.getVicinityOf().get(0).getDistance() > 0);

        Thread.sleep(1000);
        incomingMovement.setLongitude(lon - 0.0002);
        incomingMovement.setLatitude(lat - 0.0002);
        incomingMovement.setPositionTime(Instant.now().minusSeconds(15));
        MovementDetails movementDetails3 = sendIncomingMovementAndWaitForResponse(incomingMovement);

        assertNotNull(movementDetails3);
        assertNotNull(movementDetails3.getVicinityOf());
        assertEquals(2, movementDetails3.getVicinityOf().size());
        assertTrue(movementDetails3.getVicinityOf().get(0).getDistance() > 0);
        assertTrue(movementDetails3.getVicinityOf().get(1).getDistance() > 0);
    }

    private MovementDetails sendIncomingMovementAndWaitForResponse(IncomingMovement incomingMovement) throws Exception {
        return sendIncomingMovementAndWaitForResponse(incomingMovement, incomingMovement.getAssetGuid());
    }

    private MovementDetails sendIncomingMovementAndWaitForResponse(IncomingMovement incomingMovement, String groupId) throws Exception {
        String json = jsonb.toJson(incomingMovement);
        jmsHelper.sendMovementMessage(json, groupId, "CREATE");

        TextMessage response = (TextMessage) jmsHelper.listenOnMRQueue();
        String jsonResponse = response.getText();
        return jsonb.fromJson(jsonResponse, MovementDetails.class);
    }

    private ProcessedMovementResponse sendIncomingMovementAndReturnAlarmResponse(IncomingMovement incomingMovement) throws Exception {
        String json = jsonb.toJson(incomingMovement);
        jmsHelper.sendMovementMessage(json, incomingMovement.getAssetGuid(), "CREATE");

        Message response = jmsHelper.listenOnQueue(MessageConstants.QUEUE_EXCHANGE_EVENT_NAME);
        return JAXBMarshaller.unmarshallTextMessage((TextMessage) response, ProcessedMovementResponse.class);
    }
}
