/*
 * Copyright 2013 The FIX.io Project
 *
 * The FIX.io Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package fixio.netty.pipeline.server;

import fixio.events.LogonEvent;
import fixio.fixprotocol.*;
import fixio.fixprotocol.session.FixSession;
import fixio.netty.pipeline.AbstractSessionHandler;
import fixio.netty.pipeline.SessionRepository;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ServerSessionHandler extends AbstractSessionHandler {

    private final static Logger LOGGER = LoggerFactory.getLogger(ServerSessionHandler.class);
    private final FixAuthenticator authenticator;
    private int heartbeatIntervalSec = 30;

    public ServerSessionHandler(FixAuthenticator authenticator) {
        assert (authenticator != null) : "FixAuthenticator is required for ServerSessionHandler";
        this.authenticator = authenticator;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        LOGGER.info("Connection established. Waiting for Logon.");
    }

    @Override
    protected Logger getLogger() {
        return LOGGER;
    }

    private FixMessage createLogonResponse() {
        FixMessageBuilderImpl logonResponse = new FixMessageBuilderImpl(MessageTypes.LOGON);
        logonResponse.add(FieldType.HeartBtInt, heartbeatIntervalSec);
        return logonResponse;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, FixMessage msg, List<Object> out) throws Exception {
        LOGGER.debug("Message Received: {}", msg);

        FixSession fixSession = getSession(ctx);
        final FixMessageHeader header = msg.getHeader();
        if (MessageTypes.LOGON.equals(header.getMessageType())) {

            LOGGER.debug("Logon request: {}", msg);
            if (fixSession != null) {
                throw new IllegalStateException("Duplicate Logon Request. Session Already Established.");
            } else {
                if (authenticator.authenticate(msg)) {
                    fixSession = initSession(ctx, header);

                    final int msgSeqNum = header.getMsgSeqNum();

                    boolean seqTooHigh = false;
                    if (!fixSession.checkIncomingSeqNum(msgSeqNum)) {
                        final int expectedMsgSeqNum = fixSession.getNextIncomingMessageSeqNum();
                        if (msgSeqNum < expectedMsgSeqNum) {
                            sendLogoutAndClose(ctx, "Sequence Number Too Low. Expected = " + expectedMsgSeqNum);
                            return;
                        } else {
                            seqTooHigh = true;
                        }
                    }

                    final FixMessage logonResponse = createLogonResponse();
                    updateFixMessageHeader(ctx, logonResponse);
                    LOGGER.info("Sending Logon Response: {}", logonResponse);
                    ctx.write(logonResponse);

                    if (seqTooHigh) {
                        FixMessage resendRequest = new FixMessageBuilderImpl(MessageTypes.RESEND_REQUEST);
                        updateFixMessageHeader(ctx, resendRequest);
                        ctx.write(resendRequest);
                    }
                    ctx.flush();
                    out.add(new LogonEvent());

                } else {
                    //If the authentication (of the session initiator's logon message) fails,
                    // the session acceptor should shut down the connection.
                    ctx.close();
                }
            }
        } else {
            super.decode(ctx, msg, out);
        }
    }

    private FixSession initSession(ChannelHandlerContext ctx, FixMessageHeader header) {
        FixSession session = SessionRepository.getInstance().createSession(header);

        session.setNextOutgoingMessageSeqNum(1);

        setSession(ctx, session);
        LOGGER.info("Fix Session Established.");
        return session;
    }

    private void sendLogoutAndClose(ChannelHandlerContext ctx, String text) {
        //rejected logon

        FixMessageBuilderImpl logout = new FixMessageBuilderImpl(MessageTypes.LOGOUT);
        if (text != null) {
            logout.add(58, text);
        }
        ctx.writeAndFlush(logout).addListener(ChannelFutureListener.CLOSE);
    }
}
