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

import com.ning.metrics.collector.processing.db.model.FeedEvent;

import java.util.Collection;
import java.util.List;

public interface FeedEventStorage
{
    public List<String> insert(final Collection<FeedEvent> feedEvents);
    
    public List<FeedEvent> load(final String channel, final List<String> idList, final int count);
    
    public int cleanOldFeedEvents();
    
    public void cleanUp();

}
