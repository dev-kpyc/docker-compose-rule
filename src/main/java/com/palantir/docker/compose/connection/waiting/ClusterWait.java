/*
 * Copyright 2016 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.docker.compose.connection.waiting;

import static java.util.stream.Collectors.toList;

import com.google.common.collect.ImmutableList;
import com.jayway.awaitility.Awaitility;
import com.jayway.awaitility.core.ConditionTimeoutException;
import com.palantir.docker.compose.connection.Cluster;
import com.palantir.docker.compose.connection.Container;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClusterWait {
    private static final Logger log = LoggerFactory.getLogger(ClusterWait.class);
    private ClusterHealthCheck clusterHealthCheck;
    private List<String> containerNames;
    private MultiServiceHealthCheck healthCheck;
    private Duration timeout;

    public ClusterWait(String containerName, SingleServiceHealthCheck healthCheck, Duration timeout) {
        this(ImmutableList.of(containerName), MultiServiceHealthCheck.fromSingleServiceHealthCheck(healthCheck),
                timeout);
    }

    public ClusterWait(List<String> containerNames, MultiServiceHealthCheck healthCheck, Duration timeout) {
        this.containerNames = containerNames;
        this.healthCheck = healthCheck;
        this.timeout = timeout;
    }

    public ClusterWait(ClusterHealthCheck clusterHealthCheck, Duration timeout) {
        this.clusterHealthCheck = clusterHealthCheck;
        this.timeout = timeout;
    }

    public void waitUntilReady(Cluster cluster) {
        final AtomicReference<Optional<SuccessOrFailure>> lastSuccessOrFailure = new AtomicReference<>(
                Optional.empty());
        final Callable<Boolean> evaluator = weHaveSuccess(cluster, lastSuccessOrFailure);

        log.debug("Waiting for services [{}]", containerNames);
        try {
            Awaitility.await()
                    .pollInterval(50, TimeUnit.MILLISECONDS)
                    .atMost(timeout.getMillis(), TimeUnit.MILLISECONDS)
                    .until(evaluator);
        } catch (ConditionTimeoutException e) {
            throw new IllegalStateException(serviceDidNotStartupExceptionMessage(lastSuccessOrFailure));
        }
    }

    private List<Container> containersToCheck(Cluster cluster) {
        return containerNames.stream().map(cluster::container).collect(toList());
    }

    private Callable<Boolean> weHaveSuccess(Cluster cluster,
            AtomicReference<Optional<SuccessOrFailure>> lastSuccessOrFailure) {
        if (clusterHealthCheck == null) {
            return () -> {
                SuccessOrFailure successOrFailure = healthCheck.areServicesUp(containersToCheck(cluster));
                lastSuccessOrFailure.set(Optional.of(successOrFailure));
                return successOrFailure.succeeded();
            };
        } else {
            return () -> {
                SuccessOrFailure successOrFailure = clusterHealthCheck.isClusterHealthy(cluster);
                lastSuccessOrFailure.set(Optional.of(successOrFailure));
                return successOrFailure.succeeded();
            };
        }
    }

    private String serviceDidNotStartupExceptionMessage(
            AtomicReference<Optional<SuccessOrFailure>> lastSuccessOrFailure) {
        String healthcheckFailureMessage = lastSuccessOrFailure.get()
                .flatMap(SuccessOrFailure::toOptionalFailureMessage)
                .orElse("The healthcheck did not finish before the timeout");

        return String.format("%s '%s' failed to pass startup check:%n%s",
                containerNames.size() > 1 ? "Containers" : "Container",
                containerNames,
                healthcheckFailureMessage);
    }

}