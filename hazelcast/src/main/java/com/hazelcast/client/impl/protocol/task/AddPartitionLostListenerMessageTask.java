/*
 * Copyright (c) 2008-2015, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.client.impl.protocol.task;

import com.hazelcast.client.impl.protocol.ClientMessage;
import com.hazelcast.client.impl.protocol.codec.ClientAddPartitionLostListenerCodec;
import com.hazelcast.instance.Node;
import com.hazelcast.nio.Connection;
import com.hazelcast.partition.InternalPartitionService;
import com.hazelcast.partition.PartitionLostEvent;
import com.hazelcast.partition.PartitionLostListener;

import java.security.Permission;

import static com.hazelcast.partition.InternalPartitionService.PARTITION_LOST_EVENT_TOPIC;

public class AddPartitionLostListenerMessageTask
        extends AbstractCallableMessageTask<ClientAddPartitionLostListenerCodec.RequestParameters> {

    public AddPartitionLostListenerMessageTask(ClientMessage clientMessage, Node node, Connection connection) {
        super(clientMessage, node, connection);
    }

    @Override
    protected Object call() throws Exception {
        final InternalPartitionService partitionService = getService(getServiceName());

        final PartitionLostListener listener = new PartitionLostListener() {
            @Override
            public void partitionLost(PartitionLostEvent event) {
                if (endpoint.isAlive()) {
                    ClientMessage eventMessage =
                            ClientAddPartitionLostListenerCodec.encodePartitionLostEvent(event.getPartitionId(),
                            event.getLostBackupCount(), event.getEventSource());
                    sendClientMessage(null, eventMessage);
                }
            }
        };

        final String registrationId = partitionService.addPartitionLostListener(listener);
        endpoint.setListenerRegistration(getServiceName(), PARTITION_LOST_EVENT_TOPIC, registrationId);
        return registrationId;

    }

    @Override
    protected ClientAddPartitionLostListenerCodec.RequestParameters decodeClientMessage(ClientMessage clientMessage) {
        return ClientAddPartitionLostListenerCodec.decodeRequest(clientMessage);
    }

    @Override
    protected ClientMessage encodeResponse(Object response) {
        return ClientAddPartitionLostListenerCodec.encodeResponse((String) response);
    }

    @Override
    public String getServiceName() {
        return InternalPartitionService.SERVICE_NAME;
    }

    @Override
    public Permission getRequiredPermission() {
        return null;
    }

    @Override
    public String getDistributedObjectName() {
        return null;
    }

    @Override
    public String getMethodName() {
        return "addPartitionLostListener";
    }

    @Override
    public Object[] getParameters() {
        return null;
    }
}
