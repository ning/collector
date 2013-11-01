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
package com.ning.metrics.collector.processing.feed;

import com.ning.metrics.collector.processing.db.model.Feed;
import com.ning.metrics.collector.processing.db.model.FeedEvent;
import com.ning.metrics.collector.processing.db.model.FeedEventData;
import com.ning.metrics.collector.processing.db.model.RolledUpFeedEvent;

import com.google.common.base.Objects;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FeedRollUpProcessor
{
    private static final Logger log = LoggerFactory.getLogger(FeedRollUpProcessor.class);
    
    
    public Feed applyRollUp(final Feed feed, final Map<String,Object> filterMap){
        final List<FeedEvent> compiledFeedEventList = Lists.newArrayList();
        
        // filter out all events which do not match "any" of the provided key value pair
        List<FeedEvent> feedEventList = (filterMap == null || filterMap.isEmpty())?Lists.newArrayList(feed.getFeedEvents()) : Lists.newArrayList(Iterables.filter(feed.getFeedEvents(), FeedEvent.isAnyKeyValuMatching(filterMap)));
        
        if(feedEventList.isEmpty())
        {
            return new Feed(compiledFeedEventList);
        }
        
        FeedEventComparator feedEventComparator = new FeedEventComparator();
        Collections.sort(feedEventList,feedEventComparator);
        
        ArrayListMultimap<String, FeedEvent> arrayListMultimap = ArrayListMultimap.create();
        Iterator<FeedEvent> iterator = feedEventList.iterator();
        Set<String> removalTargetSet = new HashSet<String>();
        
        while(iterator.hasNext()){
            FeedEvent feedEvent = iterator.next();
            
            // Do not include suppress types of events
            if(Objects.equal(FeedEventData.EVENT_TYPE_SUPPRESS, feedEvent.getEvent().getEventType()))
            {
                removalTargetSet.addAll(feedEvent.getEvent().getRemovalTargets());
                iterator.remove();
                continue;
            }
            
            // If any of the removal targets are matching then the specific event is to be suppressed
            if(!removalTargetSet.isEmpty() && !Sets.intersection(removalTargetSet, ImmutableSet.copyOf(feedEvent.getEvent().getRemovalTargets())).isEmpty())
            {
                iterator.remove();
                continue;
            }
            
            if(feedEvent.getEvent() != null && RolledUpEventTypes.asSet.contains(feedEvent.getEvent().getEventType())){
                
                List<FeedEvent> feedEventListByType = arrayListMultimap.get(feedEvent.getEvent().getEventType());
                
                if(feedEventListByType == null){
                    feedEventListByType = Lists.newArrayList();
                }
                
                if(!feedEventListByType.isEmpty()){
                    
                    FeedEvent compareFeedEvent = feedEventListByType.get(0);
                    
                    if(feedEvent.getEvent().getCreatedDate().plusHours(24).isAfter(compareFeedEvent.getEvent().getCreatedDate()))
                    {
                        // event been iterated upon is a candidate for roll up
                        arrayListMultimap.put(feedEvent.getEvent().getEventType(), feedEvent);
                        
                     // Remove the event from the list as it has to be grouped based on the type as it is grouped and we do now want duplicates
                        iterator.remove();
                        
                    }
                }
                else
                {
                    arrayListMultimap.put(feedEvent.getEvent().getEventType(), feedEvent);
                    
                 // Remove the event from the list as it has to be grouped based on the type as it is grouped and we do now want duplicates
                    iterator.remove();
                }
            }
        }
        
        for(String eventType : arrayListMultimap.keySet())
        {
            compiledFeedEventList.add(new RolledUpFeedEvent(eventType, arrayListMultimap.get(eventType)));
        }
        
        // add rest of the events
        compiledFeedEventList.addAll(feedEventList);
        
        return new Feed(compiledFeedEventList);
    }
    
    private static final class FeedEventComparator implements Comparator<FeedEvent>{
        
        @Override
        public int compare(FeedEvent feedEvent1, FeedEvent feedEvent2)
        {
            // Sort feed events by descending date
            return feedEvent2.getEvent().getCreatedDate().compareTo(feedEvent1.getEvent().getCreatedDate());
        }
        
    }

}
