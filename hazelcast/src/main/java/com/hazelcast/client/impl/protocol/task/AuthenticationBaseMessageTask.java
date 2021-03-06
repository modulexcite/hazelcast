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

import com.hazelcast.client.AuthenticationException;
import com.hazelcast.client.ClientEndpoint;
import com.hazelcast.client.impl.ClientEndpointImpl;
import com.hazelcast.client.impl.ClientEngineImpl;
import com.hazelcast.client.impl.client.ClientPrincipal;
import com.hazelcast.client.impl.operations.ClientReAuthOperation;
import com.hazelcast.client.impl.protocol.ClientMessage;
import com.hazelcast.config.GroupConfig;
import com.hazelcast.instance.MemberImpl;
import com.hazelcast.instance.Node;
import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.Connection;
import com.hazelcast.security.Credentials;
import com.hazelcast.security.SecurityContext;
import com.hazelcast.security.UsernamePasswordCredentials;
import com.hazelcast.util.UuidUtil;

import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import java.security.Permission;
import java.util.Collection;
import java.util.Set;
import java.util.logging.Level;

/**
 * Base authentication task
 */
public abstract class AuthenticationBaseMessageTask<P>
        extends AbstractCallableMessageTask<P> {

    protected transient ClientPrincipal principal;
    protected transient Credentials credentials;

    public AuthenticationBaseMessageTask(ClientMessage clientMessage, Node node, Connection connection) {
        super(clientMessage, node, connection);
    }

    @Override
    protected ClientEndpointImpl getEndpoint() {
        if (connection.isAlive()) {
            return new ClientEndpointImpl(clientEngine, connection);
        } else {
            handleEndpointNotCreatedConnectionNotAlive();
        }
        return null;
    }

    @Override
    protected boolean isAuthenticationMessage() {
        return true;
    }

    private void handleEndpointNotCreatedConnectionNotAlive() {
        logger.warning("Dropped: " + clientMessage + " -> endpoint not created for AuthenticationRequest, connection not alive");
    }

    @Override
    public Object call() {
        boolean authenticated = authenticate();

        if (authenticated) {
            return handleAuthenticated();
        } else {
            return handleUnauthenticated();
        }
    }

    private boolean authenticate() {
        ClientEngineImpl clientEngine = getService(ClientEngineImpl.SERVICE_NAME);
        Connection connection = endpoint.getConnection();
        ILogger logger = clientEngine.getLogger(getClass());
        boolean authenticated;
        if (credentials == null) {
            authenticated = false;
            logger.severe("Could not retrieve Credentials object!");
        } else if (clientEngine.getSecurityContext() != null) {
            authenticated = authenticate(clientEngine.getSecurityContext());
        } else if (credentials instanceof UsernamePasswordCredentials) {
            UsernamePasswordCredentials usernamePasswordCredentials = (UsernamePasswordCredentials) credentials;
            authenticated = authenticate(usernamePasswordCredentials);
        } else {
            authenticated = false;
            logger.severe("Hazelcast security is disabled.\nUsernamePasswordCredentials or cluster "
                    + "group-name and group-password should be used for authentication!\n"
                    + "Current credentials type is: " + credentials.getClass().getName());
        }


        logger.log((authenticated ? Level.INFO : Level.WARNING), "Received auth from " + connection
                + ", " + (authenticated ? "successfully authenticated" : "authentication failed"));
        return authenticated;
    }

    private boolean authenticate(SecurityContext securityContext) {
        Connection connection = endpoint.getConnection();
        credentials.setEndpoint(connection.getInetAddress().getHostAddress());
        try {
            LoginContext lc = securityContext.createClientLoginContext(credentials);
            lc.login();
            endpoint.setLoginContext(lc);
            return true;
        } catch (LoginException e) {
            logger.warning(e);
            return false;
        }
    }

    private boolean authenticate(UsernamePasswordCredentials credentials) {
        GroupConfig groupConfig = nodeEngine.getConfig().getGroupConfig();
        String nodeGroupName = groupConfig.getName();
        String nodeGroupPassword = groupConfig.getPassword();
        boolean usernameMatch = nodeGroupName.equals(credentials.getUsername());
        boolean passwordMatch = nodeGroupPassword.equals(credentials.getPassword());
        return usernameMatch && passwordMatch;
    }

    private Object handleUnauthenticated() {
        throw new AuthenticationException("Invalid credentials!");
    }

    private Object handleAuthenticated() {
        if (isOwnerConnection()) {
            final String uuid = getUuid();
            final String localMemberUUID = clientEngine.getLocalMember().getUuid();

            principal = new ClientPrincipal(uuid, localMemberUUID);
            reAuthLocal();
            Collection<MemberImpl> members = nodeEngine.getClusterService().getMemberList();
            for (MemberImpl member : members) {
                if (!member.localMember()) {
                    ClientReAuthOperation op = new ClientReAuthOperation(uuid);
                    op.setCallerUuid(localMemberUUID);
                    nodeEngine.getOperationService().send(op, member.getAddress());
                }
            }
        }

        boolean isNotMember = clientEngine.getClusterService().getMember(principal.getOwnerUuid()) == null;
        if (isNotMember) {
            throw new AuthenticationException("Invalid owner-uuid: " + principal.getOwnerUuid()
                    + ", it's not member of this cluster!");
        }

        endpoint.authenticated(principal, credentials, isOwnerConnection());
        endpointManager.registerEndpoint(endpoint);
        clientEngine.bind(endpoint);

        final Address thisAddress = clientEngine.getThisAddress();
        return encodeAuth(thisAddress, principal.getUuid(), principal.getOwnerUuid());
    }

    protected abstract ClientMessage encodeAuth(Address thisAddress, String uuid, String ownerUuid);

    protected abstract boolean isOwnerConnection();

    private String getUuid() {
        if (principal != null) {
            return principal.getUuid();
        }
        return UuidUtil.createClientUuid(endpoint.getConnection().getEndPoint());
    }

    private void reAuthLocal() {
        final Set<ClientEndpoint> endpoints = endpointManager.getEndpoints(principal.getUuid());
        for (ClientEndpoint endpoint : endpoints) {
            endpoint.authenticated(principal);
        }
    }

    @Override
    public Permission getRequiredPermission() {
        return null;
    }
}
