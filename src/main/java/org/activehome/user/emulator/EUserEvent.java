package org.activehome.user.emulator;

/*
 * #%L
 * Active Home :: User :: Emulator
 * $Id:$
 * $HeadURL:$
 * %%
 * Copyright (C) 2016 Active Home Project
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the 
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public 
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */


import com.eclipsesource.json.JsonObject;
import org.activehome.context.data.Event;
import org.activehome.context.data.MetricRecord;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.concurrent.ScheduledFuture;

/**
 * @author Jacky Bourgeois
 * @version %I%, %G%
 */
public class EUserEvent extends Event {

    private LinkedList<ScheduledFuture> scheduledFutures;

    public EUserEvent(long theStartTS, long theDuration, HashMap<String, MetricRecord> theMetricRecordMap, boolean onlyPart) {
        super(theStartTS, theDuration, theMetricRecordMap, onlyPart);
        scheduledFutures = new LinkedList<>();
    }

    public EUserEvent(JsonObject json) {
        super(json);
        scheduledFutures = new LinkedList<>();
    }

    public EUserEvent(Event event) {
        super(event.getStartTS(), event.getDuration(), event.getMetricRecordMap(), event.isOnlyPart());
        scheduledFutures = new LinkedList<>();
    }

    public LinkedList<ScheduledFuture> getScheduledFutures() {
        return scheduledFutures;
    }

    public void addScheduledFuture(ScheduledFuture scheduledFuture) {
        scheduledFutures.add(scheduledFuture);
    }
}
