/**
 *    Copyright 2015-2016 FIX Protocol Ltd
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
 *
 */

package io.fixprotocol.silverflash.fixp;

import static io.fixprotocol.silverflash.fixp.SessionEventTopics.ServiceEventType.SERVICE_STORE_RETREIVE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import io.fixprotocol.silverflash.ExceptionConsumer;
import io.fixprotocol.silverflash.Service;
import io.fixprotocol.silverflash.fixp.FixpSession;
import io.fixprotocol.silverflash.fixp.Retransmitter;
import io.fixprotocol.silverflash.fixp.SessionEventTopics;
import io.fixprotocol.silverflash.fixp.SessionId;
import io.fixprotocol.silverflash.fixp.Sessions;
import io.fixprotocol.silverflash.fixp.messages.MessageHeaderEncoder;
import io.fixprotocol.silverflash.fixp.messages.RetransmitRequestEncoder;
import io.fixprotocol.silverflash.fixp.store.InMemoryMessageStore;
import io.fixprotocol.silverflash.fixp.store.MessageStore;
import io.fixprotocol.silverflash.fixp.store.StoreException;
import io.fixprotocol.silverflash.frame.MessageLengthFrameEncoder;
import io.fixprotocol.silverflash.reactor.ByteBufferDispatcher;
import io.fixprotocol.silverflash.reactor.ByteBufferPayload;
import io.fixprotocol.silverflash.reactor.EventReactor;
import io.fixprotocol.silverflash.reactor.Topic;

/**
 * @author Don Mendelson
 *
 */
public class RetransmitterTest {

  private final MessageLengthFrameEncoder frameEncoder = new MessageLengthFrameEncoder();
  private long lastRequestTimestamp;
  private final MessageHeaderEncoder messageHeaderEncoder = new MessageHeaderEncoder();
  private final MutableDirectBuffer mutableBuffer = new UnsafeBuffer(new byte[0]);
  private EventReactor<ByteBuffer> reactor;
  private final ByteBuffer retransmissionRequestBuffer = ByteBuffer.allocate(128).order(ByteOrder.nativeOrder());
  private final RetransmitRequestEncoder retransmitRequestEncoder = new RetransmitRequestEncoder();
  private Retransmitter retransmitter;
  private FixpSession session;
  private Sessions sessions;
  private MessageStore store;
  private UUID uuid = UUID.randomUUID();


  private void notifyGap(long fromSeqNo, int count) {
    mutableBuffer.wrap(retransmissionRequestBuffer);
    int offset = 0;
    frameEncoder.wrap(retransmissionRequestBuffer, offset).encodeFrameHeader();
    offset += frameEncoder.getHeaderLength();
    messageHeaderEncoder.wrap(mutableBuffer, offset);
    messageHeaderEncoder.blockLength(retransmitRequestEncoder.sbeBlockLength())
        .templateId(retransmitRequestEncoder.sbeTemplateId()).schemaId(retransmitRequestEncoder.sbeSchemaId())
        .version(retransmitRequestEncoder.sbeSchemaVersion());
    offset += messageHeaderEncoder.encodedLength();
    byte [] sessionId = SessionId.UUIDAsBytes(uuid);
    retransmitRequestEncoder.wrap(mutableBuffer, offset);
    for (int i = 0; i < 16; i++) {
      retransmitRequestEncoder.sessionId(i, sessionId[i]);
    }
    retransmitRequestEncoder.timestamp(lastRequestTimestamp);
    retransmitRequestEncoder.fromSeqNo(fromSeqNo);
    retransmitRequestEncoder.count(count);
    frameEncoder.setMessageLength(offset + retransmitRequestEncoder.encodedLength());
    frameEncoder.encodeFrameTrailer();

    Topic retrieveTopic = SessionEventTopics.getTopic(SERVICE_STORE_RETREIVE);
    reactor.post(retrieveTopic, retransmissionRequestBuffer);
  }

  @Before
  public void setUp() throws Exception {
    reactor =
        EventReactor.builder().withDispatcher(new ByteBufferDispatcher())
            .withPayloadAllocator(new ByteBufferPayload(2048)).build();

    // reactor.setTrace(true);
    CompletableFuture<? extends EventReactor<ByteBuffer>> future1 = reactor.open();

    store = new InMemoryMessageStore();
    CompletableFuture<? extends Service> future2 = store.open();

    session = mock(FixpSession.class);
    when(session.getSessionId()).thenReturn(uuid);
    sessions = new Sessions();
    sessions.addSession(session);
    ExceptionConsumer exceptionConsumer = System.err::println;
    retransmitter = new Retransmitter(reactor, store, sessions, exceptionConsumer);
    CompletableFuture<Retransmitter> future3 = retransmitter.open();

    CompletableFuture.allOf(future1, future2, future3).get();

    lastRequestTimestamp = System.nanoTime();
  }

  @After
  public void tearDown() throws Exception {
    reactor.close();
    retransmitter.close();
    store.close();
  }

  @Test
  public void testRetransmit() throws StoreException, InterruptedException, IOException {
    assertEquals(session, sessions.getSession(uuid));

    ByteBuffer message = ByteBuffer.allocate(1024);
    message.put("The quick brown fox".getBytes());
    for (long seqNo = 1; seqNo < 1001; seqNo++) {
      store.insertMessage(uuid, seqNo, message);
    }

    notifyGap(350, 20);
    Thread.sleep(2000);

    ArgumentCaptor<ByteBuffer[]> messages = ArgumentCaptor.forClass(ByteBuffer[].class);
    ArgumentCaptor<Long> timestamp = ArgumentCaptor.forClass(Long.class);
    verify(session).resend(messages.capture(), anyInt(), anyInt(), anyLong(), timestamp.capture());
    assertTrue(messages.getValue().length > 0);
    assertEquals(lastRequestTimestamp, timestamp.getValue().longValue());
  }
}
