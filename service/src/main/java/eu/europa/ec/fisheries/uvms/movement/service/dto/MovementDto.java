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
package eu.europa.ec.fisheries.uvms.movement.service.dto;

import eu.europa.ec.fisheries.schema.movement.v1.MovementSourceType;
import eu.europa.ec.fisheries.schema.movement.v1.MovementTypeType;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.datatype.XMLGregorianCalendar;
import java.util.Date;

/**
 **/
@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "movement")
public class MovementDto {

    @XmlElement(required = true)
    Date time;
    @XmlElement(required = true)
    Double latitude;
    @XmlElement(required = true)
    Double longitude;
    @XmlElement(required = true)
    String status;
    @XmlElement(required = true)
    Double measuredSpeed;
    @XmlElement(required = true)
    Double calculatedSpeed;
    @XmlElement(required = true)
    Double course;
    @XmlElement(required = true)
    MovementTypeType movementType;
    @XmlElement(required = true)
    MovementSourceType source;
    @XmlElement(required = true)
    String connectId;
    @XmlElement(required = true)
    String movementGUID;

    public Date getTime() {
        return time;
    }

    public void setTime(Date time) {
        this.time = time;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Double getMeasuredSpeed() {
        return measuredSpeed;
    }

    public void setMeasuredSpeed(Double measuredSpeed) {
        this.measuredSpeed = measuredSpeed;
    }

    public Double getCalculatedSpeed() {
        return calculatedSpeed;
    }

    public void setCalculatedSpeed(Double calculatedSpeed) {
        this.calculatedSpeed = calculatedSpeed;
    }

    public Double getCourse() {
        return course;
    }

    public void setCourse(Double course) {
        this.course = course;
    }

    public MovementTypeType getMovementType() {
        return movementType;
    }

    public void setMovementType(MovementTypeType movementType) {
        this.movementType = movementType;
    }

    public MovementSourceType getSource() {
        return source;
    }

    public void setSource(MovementSourceType source) {
        this.source = source;
    }

    public String getConnectId() {
        return connectId;
    }

    public void setConnectId(String connectId) {
        this.connectId = connectId;
    }

    public String getMovementGUID() {
        return movementGUID;
    }

    public void setMovementGUID(String movementGUID) {
        this.movementGUID = movementGUID;
    }

}