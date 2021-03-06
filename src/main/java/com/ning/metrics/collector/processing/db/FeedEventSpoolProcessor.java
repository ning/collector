/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.metrics.collector.processing.db;

import static org.quartz.JobBuilder.newJob;
import static org.quartz.SimpleScheduleBuilder.simpleSchedule;
import static org.quartz.TriggerBuilder.newTrigger;

import com.ning.arecibo.jmx.Monitored;
import com.ning.arecibo.jmx.MonitoringType;
import com.ning.metrics.collector.binder.config.CollectorConfig;
import com.ning.metrics.collector.processing.EventSpoolProcessor;
import com.ning.metrics.collector.processing.SerializationType;
import com.ning.metrics.collector.processing.db.model.FeedEvent;
import com.ning.metrics.collector.processing.db.model.FeedEventData;
import com.ning.metrics.collector.processing.db.model.Subscription;
import com.ning.metrics.collector.processing.quartz.FeedEventCleanUpJob;
import com.ning.metrics.collector.processing.quartz.FeedUpdateQuartzJob;
import com.ning.metrics.serialization.event.Event;
import com.ning.metrics.serialization.event.EventDeserializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Objects;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.mogwee.executors.LoggingExecutor;
import com.mogwee.executors.NamedThreadFactory;

import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobDataMap;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.skife.config.TimeSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class FeedEventSpoolProcessor implements EventSpoolProcessor
{
    private static final Logger log = LoggerFactory.getLogger(FeedEventSpoolProcessor.class);
    private final CollectorConfig config;
    private final SubscriptionStorage subscriptionStorage;
    private final FeedEventStorage feedEventStorage;
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String PROCESSOR_NAME = "DBWriter";
    private final BlockingQueue<FeedEvent> eventStorageBuffer;
    private final ExecutorService executorService;
    private final TimeSpan executorShutdownTimeOut;
    private final Scheduler quartzScheduler;
    private final AtomicBoolean isCleanupCronJobScheduled = new AtomicBoolean(false);
    
    @Inject
    public FeedEventSpoolProcessor(final CollectorConfig config, final SubscriptionStorage subscriptionStorage, final FeedEventStorage feedEventStorage, final Scheduler quartzScheduler) throws SchedulerException
    {
        this.config = config;
        this.subscriptionStorage = subscriptionStorage;
        this.feedEventStorage = feedEventStorage;
        this.eventStorageBuffer = new ArrayBlockingQueue<FeedEvent>(1000, false);
        this.executorShutdownTimeOut = config.getSpoolWriterExecutorShutdownTime();
        this.quartzScheduler = quartzScheduler;
        
        final List<String> eventTypesList = Splitter.on(config.getFilters()).omitEmptyStrings().splitToList(config.getFiltersEventType());
        if(eventTypesList.contains(DBStorageTypes.FEED_EVENT.getDbStorageType()))
        {
        	this.executorService = new LoggingExecutor(1, 1 , Long.MAX_VALUE, TimeUnit.DAYS, new ArrayBlockingQueue<Runnable>(2), new NamedThreadFactory("FeedEvents-Storage-Threads"),new ThreadPoolExecutor.CallerRunsPolicy());
            this.executorService.submit(new FeedEventInserter(this.executorService, this));
            
            if(!quartzScheduler.isStarted())
            {
                quartzScheduler.start();
                scheduleFeedEventCleanupCronJob();
            }
        }
        else
        {
        	this.executorService = null;
        }
        
    } 

    @Override
    public void processEventFile(final String eventName, final SerializationType serializationType, final File file, final String outputPath) throws IOException
    {
        // File has Smile type of events
        EventDeserializer eventDeserializer = serializationType.getDeSerializer(new FileInputStream(file));
        
        /*This would handle insertion of Subscriptions and Feed Events. 
         * The subscriptions  would be stored as they come by, however for feed events
         * the storage would be done in bulk after the complete file is read, 
         * since feed events depend upon the subscriptions*/
        while(eventDeserializer.hasNextEvent())
        {
            Event event = eventDeserializer.getNextEvent();
            log.debug(String.format("Recieved DB Event to store with name as %s ",event.getName()));
            
            if(event.getName().equalsIgnoreCase(DBStorageTypes.FEED_EVENT.getDbStorageType()))
            {
               log.debug(String.format("DB Event body to store is %s",event.getData()));
                 
               FeedEventData feedEventData = mapper.readValue(event.getData().toString(), FeedEventData.class);
               //Check is event type is to suppress other events
               boolean isSuppressTypeEvent = Objects.equal(FeedEventData.EVENT_TYPE_SUPPRESS, feedEventData.getEventType());
               
               Set<Subscription> subscriptions = new HashSet<Subscription>();
               for(String topic : feedEventData.getTopics()){
                   // If suppress type event then load all subsciptions which start with the topic else load it by exploding the topic
                   subscriptions.addAll(isSuppressTypeEvent?subscriptionStorage.loadByStartsWithTopic(topic):subscriptionStorage.loadByTopic(topic));
               }
               if(!subscriptions.isEmpty())
               {
                   for(Subscription subscription : subscriptions)
                   {
                       addToBuffer(event.getName(),new FeedEvent(feedEventData, 
                                                           subscription.getChannel(), 
                                                           subscription.getId(), 
                                                           subscription.getMetadata()));
                   }
               }                  
            }            
        }
        
    }
    
    private void addToBuffer(String eventName, FeedEvent feedEvent) {
        try {
            eventStorageBuffer.put(feedEvent);
        }
        catch (InterruptedException e) {
            log.warn(String.format("Could not add event %s to the buffer", eventName),e);
        }
    }
    
    public void flushFeedEventsToDB(){
        try {
            List<FeedEvent> feedEventList = Lists.newArrayListWithCapacity(eventStorageBuffer.size());
            int count;
            boolean inserted = false;
            do {
                count = eventStorageBuffer.drainTo(feedEventList,1000);
                if(count > 0){
                    inserted = true;
                    List<String> feedEventIdList = feedEventStorage.insert(feedEventList);
                    log.info(String.format("Inserted %d events successfully!", count));
                    feedEventList.clear();
                    
                    // Schedule Quartz job for feed preparation of the inserted events
                    scheduleFeedCollectionJob(feedEventIdList);
                }
            }
            while (count > 0);
            
            if (!inserted) {
                try {
                    Thread.sleep(5000);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        catch (Exception e) {
            log.warn("unexpected exception trying to insert events!",e);
        }
    }
    
    private void scheduleFeedCollectionJob(List<String> feedEventIdList){
        try {
            if(this.quartzScheduler.isStarted()){
                SimpleTrigger trigger = (SimpleTrigger)newTrigger()
                        .withIdentity(UUID.randomUUID().toString()+"_feedUpdateTrigger", "feedUpdateGroup")
                        .withSchedule(simpleSchedule().withMisfireHandlingInstructionFireNow())                 
                        .build();
                
                JobDataMap jobMap = new JobDataMap();
                jobMap.put("feedEventIdList",feedEventIdList);
                
                quartzScheduler.scheduleJob(
                    newJob(FeedUpdateQuartzJob.class).withIdentity(UUID.randomUUID().toString()+"_feedUpdateJob", "feedUpdateJobGroup").usingJobData(jobMap).build()
                    ,trigger);
            }
        }
        catch (SchedulerException e) {
            log.warn("unexpected exception trying to schedule Quartz job for feed preparation of the inserted events!",e);
        }
    }

    @Override
    public void close()
    {
        try {
            feedEventStorage.cleanUp();
            subscriptionStorage.cleanUp();  
        }
        finally{
            log.info("Shutting Down Executor Service for Feed Event Storage");
            if(executorService != null)
            {
            	executorService.shutdown();
                
                try {
                    executorService.awaitTermination(executorShutdownTimeOut.getPeriod(), executorShutdownTimeOut.getUnit());
                }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                executorService.shutdownNow();
            }
            
            
            log.info("Executor Service for Feed Event Storage Shut Down success!");
            
            if(!eventStorageBuffer.isEmpty()){
                log.info("Flushing remaining events to database");
                flushFeedEventsToDB();
            }
            
            log.info("Shutting Down Quartz Scheduler");
            try {
                if(!quartzScheduler.isShutdown())
                {
                    final JobKey jobKey = new JobKey("feedEventCleanupCronJob", "feedEventCleanupCronJobGroup");
                    if(this.quartzScheduler.checkExists(jobKey))
                    {
                       this.quartzScheduler.deleteJob(jobKey);
                    }
                    quartzScheduler.shutdown(true);
                }
                
            }
            catch (SchedulerException e) {
                log.error("Unexpected error while shutting down Quartz Scheduler!",e);
            }
            log.info("Quartz Scheduler shutdown success");
        }              
    }
    
    private void scheduleFeedEventCleanupCronJob() throws SchedulerException
    {
        if(this.quartzScheduler.isStarted() && !isCleanupCronJobScheduled.get())
        {
            final JobKey jobKey = new JobKey("feedEventCleanupCronJob", "feedEventCleanupCronJobGroup");
            
            if(!this.quartzScheduler.checkExists(jobKey))
            {
                final CronTrigger cronTrigger = newTrigger()
                        .withIdentity("feedEventCleanupCronTrigger", "feedEventCleanupCronTriggerGroup")
                        .withSchedule(CronScheduleBuilder.cronSchedule(config.getFeedEventsCleanupCronExpression()).withMisfireHandlingInstructionDoNothing())
                        .build();
                
                quartzScheduler.scheduleJob(newJob(FeedEventCleanUpJob.class).withIdentity(jobKey).build()
                    ,cronTrigger);
            }
            
            isCleanupCronJobScheduled.set(true);
            
        }
    }

    @Override
    public String getProcessorName()
    {
        return PROCESSOR_NAME;
    }
    
    @Monitored(description = "Number of events in buffer", monitoringType = {MonitoringType.VALUE, MonitoringType.RATE})
    public long getEventsInBuffer()
    {
        return eventStorageBuffer.size();
    }
    
    private static class FeedEventInserter implements Runnable{

        private final ExecutorService es;
        private final FeedEventSpoolProcessor feedEventSpoolProcessor;
        
        public FeedEventInserter(ExecutorService es,FeedEventSpoolProcessor feedEventSpoolProcessor){
            this.es = es;
            this.feedEventSpoolProcessor = feedEventSpoolProcessor;
        }
        
        @Override
        public void run()
        {
            feedEventSpoolProcessor.flushFeedEventsToDB();
            es.submit(this);
            
        }
        
    }
    
    private class FeedEventScheduledCleaner implements Runnable {
        public void run()
        {
            feedEventStorage.cleanOldFeedEvents();
        }
    }

}
