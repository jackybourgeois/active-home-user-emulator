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
import org.activehome.com.Notif;
import org.activehome.com.Request;
import org.activehome.context.data.AlternativeEpisode;
import org.activehome.context.data.Event;
import org.activehome.context.data.Schedule;
import org.activehome.context.data.Device;
import org.activehome.context.helper.ModelHelper;
import org.activehome.io.action.Command;
import org.activehome.energy.library.EnergyHelper;
import org.activehome.context.data.MetricRecord;
import org.activehome.mysql.HelperMySQL;
import org.activehome.user.User;
import org.activehome.user.UserSuggestion;
import org.activehome.user.UserSuggestionResponse;
import org.kevoree.ContainerRoot;
import org.kevoree.annotation.ComponentType;
import org.kevoree.annotation.Param;
import org.kevoree.annotation.Start;
import org.kevoree.api.handler.UUIDModel;
import org.kevoree.factory.DefaultKevoreeFactory;
import org.kevoree.factory.KevoreeFactory;
import org.kevoree.pmodeling.api.ModelCloner;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


/**
 * Emulate the behaviour of a user interacting with the system.
 *
 * @author Jacky Bourgeois
 * @version %I%, %G%
 */
@ComponentType
public class EUser extends User {

    @Param(defaultValue = "Emulate a user behavior.")
    private String description;
    @Param(defaultValue = "/active-home-user-emulator")
    private String src;

    private final static long HORIZON = DAY;

    /**
     * Source of the data.
     */
    @Param(optional = false)
    private String urlSQLSource;
    /**
     * Table that contains the data.
     */
    @Param(optional = false)
    private String tableName;
    /**
     * The type of decision to apply suggestions.
     * manual (waiting actual response), random, objective:[objectiveName],
     * time:[day,night,morning,afternoon,evening]
     */
    @Param(defaultValue = "random")
    private String decision;
    /**
     * To get a local copy of Kevoree model.
     */
    private ModelCloner cloner;
    /**
     * SQL date format.
     */
    private SimpleDateFormat dfMySQL;
    /**
     * Scheduler playing the load values at the right time.
     */
    private ScheduledThreadPoolExecutor stpe;
    /**
     * Map of interactive devices running on the platform
     */
    private HashMap<String, Device> deviceMap;

    private Schedule schedule;
    private long nextScheduleUpdate;

    // == == == Component life cycle == == ==

    @Start
    public final void start() {
        super.start();
        KevoreeFactory kevFactory = new DefaultKevoreeFactory();
        cloner = kevFactory.createModelCloner();

        dfMySQL = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        dfMySQL.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    // == == == Time life cycle == == ==

    @Override
    public final void onStartTime() {
        initExecutor();
        nextScheduleUpdate = getCurrentTime();
        updateSchedule();
    }

    @Override
    public final void onPauseTime() {
        stpe.shutdownNow();
    }

    @Override
    public final void onResumeTime() {
        initExecutor();
        if (schedule != null) {
            for (Event event : schedule.getEpisode().getEvents()) {
                if (event.getProp("auto").equals("false") && !event.isOnlyPart()) {
                    scheduleEvent((EUserEvent) event);
                }
            }
        }
        stpe.schedule(this::updateSchedule,
                (nextScheduleUpdate - getCurrentTime()) / getTic().getZip(), TimeUnit.MILLISECONDS);
    }

    @Override
    public final void onStopTime() {
        stpe.shutdownNow();
    }

    @Override
    public final void getNotif(final String notifStr) {
        Notif notif = new Notif(JsonObject.readFrom(notifStr));
        if (notif.getContent() instanceof UserSuggestion) {
            manageSuggestion((UserSuggestion) notif.getContent(), notif.getSrc());
        }
    }

    private void updateSchedule() {
        updateSource();
        long start = getCurrentTime();
        long end = getCurrentTime() + HORIZON;
        schedule = loadData(start, end);
        nextScheduleUpdate = end;
        for (Event event : schedule.getEpisode().getEvents()) {
            event.setProp("auto", "false");
            if (!event.isOnlyPart()) {
                scheduleEvent((EUserEvent) event);
            } else if (event.getStartTS() < nextScheduleUpdate) {
                nextScheduleUpdate = event.getStartTS();
            }
        }
        stpe.schedule(this::updateSchedule,
                (nextScheduleUpdate - getCurrentTime()) / getTic().getZip(), TimeUnit.MILLISECONDS);
    }

    /**
     * Send the parameters to an appliance,
     * getting it ready to run.
     *
     * @param metricRecord the details of the load to set
     */
    private void setAppliance(final MetricRecord metricRecord) {
        if (pushCmd != null
                && pushCmd.getConnectedBindingsSize() > 0) {
            pushCmd.send(new Request(getFullId(),
                    getNode() + metricRecord.getMetricId()
                            .substring(metricRecord.getMetricId().lastIndexOf(".")),
                    getCurrentTime(), Command.SET.name(),
                    new Object[]{metricRecord}).toString(), null);
        }

    }

    /**
     * Send a comand to an appliance.
     *
     * @param applianceId the appliance to control
     * @param cmd         the command to fire
     */
    private void ctrlApp(final String applianceId,
                         final Command cmd) {
        if (pushCmd != null
                && pushCmd.getConnectedBindingsSize() > 0) {
            pushCmd.send(new Request(getFullId(),
                    getNode() + applianceId.substring(applianceId.lastIndexOf(".")),
                    getCurrentTime(), cmd.name()).toString(), null);
        }
    }

    private void scheduleEvent(final EUserEvent event) {
        long delay = (event.getStartTS() - getCurrentTime())
                / getTic().getZip();
        event.addScheduledFuture(stpe.schedule(() -> setAppliance(event.getMetricRecordMap().get("power")),
                delay - MINUTE / getTic().getZip(), TimeUnit.MILLISECONDS));

        event.addScheduledFuture(stpe.schedule(() -> ctrlApp(event.getMetricRecordMap().get("power").getMetricId(),
                Command.START), delay, TimeUnit.MILLISECONDS));
    }

    private void unscheduleEvent(final EUserEvent event) {
        if (stpe != null && !stpe.isShutdown()) {
            for (ScheduledFuture sf : event.getScheduledFutures()) {
                sf.cancel(true);
            }
        }
        schedule.getEpisode().getEvents().remove(event);
    }

    private void updateSource() {
        UUIDModel model = getModelService().getCurrentModel();
        ContainerRoot localModel = cloner.clone(model.getModel());

        deviceMap = ModelHelper.findAllRunningDevice("InteractiveAppliance",
                new String[]{context.getNodeName()}, localModel);
    }

    private void manageSuggestion(final UserSuggestion suggestion,
                                  final String src) {
        checkExpiredSuggestions();
        userSuggestions.add(suggestion);
        if (decision.equals("manual")) {
            pushSuggestionToAvailableUI(suggestion);
        } else {
            // currently: says yes to all suggestion and manage them (no automation)
            if (schedule != null) {
                applySuggestion(suggestion, false, src);
            }

        }

//        suggestion.setStatus(Suggestion.SuggestionStatus.MANUAL);
//        sendNotif(new Notif(getFullId(), src, getCurrentTime(), suggestion));
//        applySuggestion(suggestion, false);
//
//        if (decision.startsWith("random")) {
//            decide(Math.random(), suggestion, src);
//        } else if (decision.startsWith("objective")) {
//            decide(suggestion.getDifference().getImpactPerObjectiveMap()
//                    .get(decision.split(":")[1]), suggestion, src);
//        } else if (decision.startsWith("time")) {
//            long timeOfDay = getCurrentTime() % DAY;
//            // time of the day with the best chance
//            int ref = Integer.valueOf(decision.split(":")[1]);
//            // compute gaussian mean based on distance to the ref
//            double mean = Math.abs(timeOfDay - ref) / 12.;
//            double variance = 0.5;
//            Random rand = new Random();
//            double val = Math.abs(mean + rand.nextGaussian() * variance);
//            decide(val, suggestion, src);
//        } else {
//            Log.warn("No valid decision strategy (" + decision + ").");
//        }

    }

    private void checkExpiredSuggestions() {
        LinkedList<UserSuggestion> toRemove = userSuggestions.stream()
                .filter(us -> us.getExpireTS() > getCurrentTime())
                .collect(Collectors.toCollection(LinkedList::new));
        userSuggestions.removeAll(toRemove);
    }

    private void pushSuggestionToAvailableUI(UserSuggestion suggestion) {

    }

    private void decide(final double choice,
                        final UserSuggestion suggestion,
                        final String src) {
//        if (choice < 0.165) {
//            // reply 'yes, it's ready, do it for me'
//            Log.info("'yes, it's ready, do it for me");
//            suggestion.setStatus(Suggestion.SuggestionStatus.AUTO);
//            sendNotif(new Notif(getFullId(), src, getCurrentTime(), suggestion));
//            applySuggestion(suggestion, true);
//        } else if (choice < 0.33) {
//            // reply 'yes, I'll do' and apply the suggestion around suggested time
//            Log.info("'yes, I'll do' and apply the suggestion around suggested time");
//            suggestion.setStatus(Suggestion.SuggestionStatus.MANUAL);
//            sendNotif(new Notif(getFullId(), src, getCurrentTime(), suggestion));
//            applySuggestion(suggestion, false);
//        } else if (choice < 0.495) {
//            // reply 'yes, I'll do' and DO NOT apply the suggestion
//            Log.info("'yes, I'll do' and DO NOT apply the suggestion");
//            suggestion.setStatus(Suggestion.SuggestionStatus.MANUAL);
//            sendNotif(new Notif(getFullId(), src, getCurrentTime(), suggestion));
//        } else if (choice < 0.66) {
//            // reply 'no, I can't' and do not apply the suggestion
//            Log.info("'no, I can't' and do not apply the suggestion");
//            suggestion.setStatus(Suggestion.SuggestionStatus.REJECTED);
//            sendNotif(new Notif(getFullId(), src, getCurrentTime(), suggestion));
//        } else if (choice < 0.825) {
//            // do not reply but apply suggestion around suggested time
//            Log.info("Do not reply but apply suggestion around suggested time");
//            applySuggestion(suggestion, false);
//        } else {
//            // else do not reply and do not apply suggestion
//            Log.info("do not reply and do not apply suggestion");
//        }

    }

    private void applySuggestion(final UserSuggestion suggestion,
                                 final boolean auto,
                                 final String suggestionSrc) {
        LinkedList<Event> userCurrentEvents = new LinkedList<>(schedule.getEpisode().getEvents());
        LinkedList<Event> suggestedEvents = suggestion.getEpisode().getEvents();

        LinkedList<Event> newUserEvents = new LinkedList<>();
        LinkedList<Event> newSuggestionReply = new LinkedList<>();

        // choose alternative (only one choice currently)
        AlternativeEpisode alternative = suggestion.getEpisode().getAlternatives().get("alternative");

        int i = 0;
        for (Event sugEvent : suggestedEvents) {
            EUserEvent event = null;
            // look for equivalent event in user schedule
            for (Event userEvent : userCurrentEvents) {
                if (fromSameDevice(userEvent, sugEvent)) {
                    event = (EUserEvent) userEvent;
                }
            }
            // unschedule current event, schedule suggested one and notify the system
            if (event != null) {
                unscheduleEvent(event);
                sugEvent.transform(alternative.getTransformations().get(i));
                event.transform(alternative.getTransformations().get(i));
                newUserEvents.add(event);
                newSuggestionReply.add(sugEvent);
            }
            i++;
        }

        for (Event newEvent : newUserEvents) {
            scheduleEvent(new EUserEvent(newEvent));
        }

        for (Event sugEvent : newSuggestionReply) {
            UserSuggestionResponse response = new UserSuggestionResponse(suggestion.getId(), sugEvent, false);
            sendNotif(new Notif(getFullId(), suggestionSrc, getCurrentTime(), response));
        }

    }

    private boolean fromSameDevice(Event e1, Event e2) {
        return e1.getMetricRecordMap().get("power").getMetricId()
                .equals(e2.getMetricRecordMap().get("power").getMetricId());
    }


    /**
     * Extract the list of appliance's load for a given period.
     *
     * @param startTS start timestamp of the period
     * @param endTS   end timestamp of the period
     * @return the details of the loads as MetricRecord
     */
    public final Schedule loadData(final long startTS,
                                   final long endTS) {
        Connection dbConnect = HelperMySQL.connect(urlSQLSource);
        Schedule schedule = new Schedule("schedule.euser", startTS, endTS - startTS, HOUR);
        try {
            for (Device device : deviceMap.values()) {
                MetricRecord metricRecord = loadData(dbConnect, tableName, startTS, endTS, device.getID());
                LinkedList<Event> events = EnergyHelper.energyEvents(metricRecord,
                        Double.valueOf(device.getAttributeMap().getOrDefault("onOffThreshold", "0")),
                        Long.valueOf(device.getAttributeMap().getOrDefault("maxDurationWithoutChange", "900000")),
                        Long.valueOf(device.getAttributeMap().getOrDefault("minDuration", "600000")), HOUR);
                for (Event event : events) {
                    schedule.getEpisode().getEvents().add(new EUserEvent(event));
                }
            }
        } catch (ParseException e) {
            e.printStackTrace();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        HelperMySQL.closeDbConnection(dbConnect);
        return schedule;
    }

    public MetricRecord loadData(final Connection dbConnect,
                                 final String tableName,
                                 final long startTS,
                                 final long endTS,
                                 final String metricId)
            throws ParseException, SQLException {

        String query = "SELECT `metricID`, `timestamp`, `value` "
                + " FROM `" + tableName + "` "
                + " WHERE (`timestamp` BETWEEN ? AND ?) "
                + " AND `metricID`=? ORDER BY `timestamp`";

        SimpleDateFormat dfMySQL = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        dfMySQL.setTimeZone(TimeZone.getTimeZone("UTC"));

        MetricRecord metricRecord = new MetricRecord("power.cons.inter." + metricId, endTS - startTS);
        metricRecord.setMainVersion("0");
        String prevVal = null;
        PreparedStatement prepStmt = dbConnect.prepareStatement(query);
        prepStmt.setString(1, dfMySQL.format(startTS));
        prepStmt.setString(2, dfMySQL.format(endTS));
        prepStmt.setString(3, metricId);
        ResultSet result = prepStmt.executeQuery();
        while (result.next()) {
            String val = result.getString("value");
            if (prevVal == null || !val.equals(prevVal)) {
                long ts = dfMySQL.parse(
                        result.getString("timestamp")).getTime();
                metricRecord.addRecord(ts, val, "0", 1);
                prevVal = val;
            }
        }

        return metricRecord;
    }

    private void initExecutor() {
        stpe = new ScheduledThreadPoolExecutor(1, r -> {
            return new Thread(r, getFullId() + "-emulator-user-pool");
        });
    }

}
