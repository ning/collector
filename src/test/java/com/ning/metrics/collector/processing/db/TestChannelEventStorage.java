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

import com.ning.metrics.collector.processing.db.model.ChannelEvent;
import com.ning.metrics.collector.processing.db.model.ChannelEventData;
import com.ning.metrics.collector.processing.db.model.EventMetaData;
import com.ning.metrics.collector.processing.db.model.Subscription;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Guice;
import com.google.inject.Inject;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.testng.collections.Lists;

import java.io.IOException;
import java.util.List;
import java.util.Set;

@Test(groups = {"slow", "database"})
public class TestChannelEventStorage
{
    private CollectorMysqlTestingHelper helper;
    
    @Inject
    SubscriptionStorage subscriptionStorage;
    
    @Inject
    ChannelEventStorage channelEventStorage;
    
    private static final ObjectMapper mapper = new ObjectMapper();
    
    private Subscription subscription;
    final String target = "target";
    final String channel = "channel";
    final String feed = "feed";
    
    @BeforeClass(groups = {"slow", "database"})
    public void startDB() throws Exception{
        helper = new CollectorMysqlTestingHelper();
        helper.startMysql();
        helper.initDb();
        
        System.setProperty("collector.spoolWriter.jdbc.url", helper.getJdbcUrl());
        System.setProperty("collector.spoolWriter.jdbc.user", CollectorMysqlTestingHelper.USERNAME);
        System.setProperty("collector.spoolWriter.jdbc.password", CollectorMysqlTestingHelper.PASSWORD);
        
        Guice.createInjector(new DBConfigModule()).injectMembers(this);
                
    }
    
    @BeforeMethod(alwaysRun = true, groups = {"slow", "database"})
    public void clearDB(){
        helper.clear();
        subscriptionStorage.insert(getSubscription(target,channel,feed));
        
        Set<Subscription> subscriptions = subscriptionStorage.load(target);
        Assert.assertNotEquals(subscriptions.size(), 0);        
        
        subscription = subscriptions.iterator().next();
        Assert.assertEquals(subscription.getTarget(), target);
        
    }
    
    @AfterClass(alwaysRun = true,groups = {"slow", "database"})
    public void stopDB() throws Exception{
        helper.stopMysql();
    }
    
    @Test
    public void testInsertChannelEvents() throws Exception{
        
        String eventData = "{"
                                + "\"content-id\": \"123:Meal:456\","
                                + "\"content-type\": \"Meal\","
                                + "\"targets\": [\""+target+"\"]"                
                         + "}";
        
        List<ChannelEvent> channelEvents = Lists.newArrayList();
        
        for(int i=0;i<10;i++){
            channelEvents.add(getChannelEvent(subscription, eventData));
        } 
        
        channelEventStorage.insert(channelEvents);
        
        channelEvents.clear();
        Assert.assertTrue(channelEvents.size() == 0);
        
        channelEvents = channelEventStorage.load(channel, 0, 10);
        
        Assert.assertTrue(channelEvents.size() == 10);
        Assert.assertEquals(channelEvents.get(0).getChannel(), channel);    
        Assert.assertEquals(channelEvents.get(0).getMetadata().getFeed(), feed);
        Assert.assertEquals(channelEvents.get(0).getSubscriptionId(), subscription.getId());
        
    }
    
    private Subscription getSubscription(String target, String channel, String feed){
        EventMetaData metadata = new EventMetaData(feed);
        Subscription subscription = new Subscription(target, metadata, channel);
        return subscription;
    }
    
    private ChannelEvent getChannelEvent(Subscription subscription, String eventData) throws JsonParseException, JsonMappingException, IOException{        
        return new ChannelEvent(mapper.readValue(eventData, ChannelEventData.class), 
            subscription.getChannel(), 
            subscription.getId(), 
            subscription.getMetadata());
    }

}