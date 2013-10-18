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

import com.ning.arecibo.jmx.Monitored;
import com.ning.arecibo.jmx.MonitoringType;
import com.ning.metrics.collector.binder.config.CollectorConfig;
import com.ning.metrics.collector.processing.EventSpoolProcessor;
import com.ning.metrics.collector.processing.SerializationType;
import com.ning.metrics.collector.processing.db.model.FeedEvent;
import com.ning.metrics.collector.processing.db.model.FeedEventData;
import com.ning.metrics.collector.processing.db.model.Subscription;
import com.ning.metrics.serialization.event.Event;
import com.ning.metrics.serialization.event.EventDeserializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.mogwee.executors.FailsafeScheduledExecutor;
import com.mogwee.executors.LoggingExecutor;
import com.mogwee.executors.NamedThreadFactory;

import org.skife.config.TimeSpan;
import org.skife.jdbi.v2.IDBI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class DBSpoolProcessor implements EventSpoolProcessor
{
    private static final Logger log = LoggerFactory.getLogger(DBSpoolProcessor.class);
    private final IDBI dbi;
    private final CollectorConfig config;
    private final SubscriptionStorage subscriptionStorage;
    private final FeedEventStorage feedEventStorage;
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String PROCESSOR_NAME = "DBWriter";
    private final BlockingQueue<FeedEvent> eventStorageBuffer;
    private final ExecutorService executorService;
    private final ScheduledExecutorService scheduledExecutorService;
    private final TimeSpan executorShutdownTimeOut;
    
    @Inject
    public DBSpoolProcessor(final IDBI dbi, final CollectorConfig config, final SubscriptionStorage subscriptionStorage, final FeedEventStorage feedEventStorage)
    {
        this.dbi = dbi;
        this.config = config;
        this.subscriptionStorage = subscriptionStorage;
        this.feedEventStorage = feedEventStorage;
        this.eventStorageBuffer = new ArrayBlockingQueue<FeedEvent>(1000, false);
        this.executorShutdownTimeOut = config.getSpoolWriterExecutorShutdownTime();
        this.executorService = new LoggingExecutor(1, 1 , Long.MAX_VALUE, TimeUnit.DAYS, new ArrayBlockingQueue<Runnable>(2), new NamedThreadFactory("FeedEvents-Storage-Threads"),new ThreadPoolExecutor.CallerRunsPolicy());
        this.executorService.submit(new FeedEventInserter(this.executorService, this));
        this.scheduledExecutorService = new FailsafeScheduledExecutor(1, "FeedEvents-Cleaner-Threads");
        this.scheduledExecutorService.schedule(new FeedEventScheduledCleaner(), 15, TimeUnit.MINUTES);
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
            log.info(String.format("Recieved DB Event to store with name as %s ",event.getName()));
            log.info(String.format("DB Event body to store is %s",event.getData()));
            
            if(event.getName().equalsIgnoreCase(DBStorageTypes.FEED_EVENT.getDbStorageType()))
            {
               FeedEventData feedEventData = mapper.readValue(event.getData().toString(), FeedEventData.class);
               
               for(String topic : feedEventData.getTopics()){
                   Set<Subscription> subscriptions = subscriptionStorage.load(topic);
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
                    feedEventStorage.insert(feedEventList);
                    log.info(String.format("Inserted %d events successfully!", count));
                    feedEventList.clear();
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

    @Override
    public void close()
    {
        try {
            feedEventStorage.cleanUp();
            subscriptionStorage.cleanUp();  
        }
        finally{
            log.info("Shutting Down Executor Service for Feed Event Storage");
            executorService.shutdown();
            
            try {
                executorService.awaitTermination(executorShutdownTimeOut.getPeriod(), executorShutdownTimeOut.getUnit());
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            executorService.shutdownNow();
            
            log.info("Executor Service for Feed Event Storage Shut Down success!");
            
            if(!eventStorageBuffer.isEmpty()){
                log.info("Flushing remaining events to database");
                flushFeedEventsToDB();
            }
            
            log.info("Shutting Down Feed Event Cleaner Executor Service");
            scheduledExecutorService.shutdown();
            
            try {
                scheduledExecutorService.awaitTermination(executorShutdownTimeOut.getPeriod(), executorShutdownTimeOut.getUnit());
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            scheduledExecutorService.shutdownNow();
            
            log.info("Feed Event Cleaner Executor Service shutdown success!");
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
        private final DBSpoolProcessor dbSpoolProcessor;
        
        public FeedEventInserter(ExecutorService es,DBSpoolProcessor dbSpoolProcessor){
            this.es = es;
            this.dbSpoolProcessor = dbSpoolProcessor;
        }
        
        @Override
        public void run()
        {
            dbSpoolProcessor.flushFeedEventsToDB();
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
