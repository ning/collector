/*
 * Copyright 2010-2012 Ning, Inc.
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

package com.ning.metrics.collector.filtering;

import com.ning.metrics.collector.endpoint.ParsedRequest;

import com.google.inject.Inject;
import org.weakref.jmx.Managed;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

public class PatternSetFilter implements Filter<ParsedRequest>
{
    private final FieldExtractor fieldExtractor;
    private final ConcurrentMap<String, Pattern> patternMap = new ConcurrentHashMap<String, Pattern>();

    @Inject
    public PatternSetFilter(final FieldExtractor fieldExtractor, final Iterable<Pattern> patterns)
    {
        this.fieldExtractor = fieldExtractor;

        for (final Pattern pattern : patterns) {
            patternMap.put(pattern.toString(), pattern);
        }
    }

    @Override
    public boolean passesFilter(final String name, final ParsedRequest parsedRequest)
    {
        final String input = fieldExtractor.getField(name, parsedRequest);

        if (input == null) {
            return false;
        }

        for (final Pattern pattern : patternMap.values()) {
            if (pattern.matcher(input).find()) {
                return true;
            }
        }

        return false;
    }

    @Managed(description = "list of patterns for this filter")
    public List<String> getPatternSet()
    {
        return new ArrayList<String>(patternMap.keySet());
    }

    @Managed(description = "add a regular expression to filter set")
    public void addPattern(final String patternString)
    {
        patternMap.put(patternString, Pattern.compile(patternString));
    }

    @Managed(description = "add a regular expression to filter set")
    public void removePattern(final String patternString)
    {
        patternMap.remove(patternString);
    }
}
