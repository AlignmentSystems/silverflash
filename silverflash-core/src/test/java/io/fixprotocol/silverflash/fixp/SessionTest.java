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

import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.fixprotocol.silverflash.MessageConsumer;
import io.fixprotocol.silverflash.Session;
import io.fixprotocol.silverflash.auth.SimpleDirectory;
import io.fixprotocol.silverflash.buffer.SingleBufferSupplier;
import io.fixprotocol.silverflash.fixp.Engine;
import io.fixprotocol.silverflash.fixp.FixpSession;
import io.fixprotocol.silverflash.fixp.SessionId;
import io.fixprotocol.silverflash.fixp.SessionReadyFuture;
import io.fixprotocol.silverflash.fixp.SessionTerminatedFuture;
import io.fixprotocol.silverflash.fixp.auth.SimpleAuthenticator;
import io.fixprotocol.silverflash.fixp.messages.FlowType;
import io.fixprotocol.silverflash.fixp.messages.MessageHeaderEncoder;
import io.fixprotocol.silverflash.frame.MessageFrameEncoder;
import io.fixprotocol.silverflash.frame.MessageLengthFrameEncoder;
import io.fixprotocol.silverflash.frame.sofh.SofhFrameEncoder;
import io.fixprotocol.silverflash.frame.sofh.SofhFrameSpliterator;
import io.fixprotocol.silverflash.reactor.ByteBufferDispatcher;
import io.fixprotocol.silverflash.reactor.ByteBufferPayload;
import io.fixprotocol.silverflash.reactor.EventReactor;
import io.fixprotocol.silverflash.transport.PipeTransport;
import io.fixprotocol.silverflash.transport.Transport;
import io.fixprotocol.silverflash.transport.TransportDecorator;

public class SessionTest {

  class TestReceiver implements MessageConsumer<UUID> {
    int bytesReceived = 0;
    private byte[] dst = new byte[16 * 1024];
    int msgsReceived = 0;

    public int getMsgsReceived() {
      return msgsReceived;
    }

    public int getBytesReceived() {
      return bytesReceived;
    }

    @Override
    public void accept(ByteBuffer buf, Session<UUID> session, long seqNo) {
      int bytesToReceive = buf.remaining();
      bytesReceived += bytesToReceive;
      buf.get(dst, 0, bytesToReceive);
      msgsReceived ++;
    }
  }

  static final byte STREAM_ID = 99;

  private static final int templateId = 22;
  private static final int schemaVersion = 0;
  private static final int schemaId = 33;

  private Engine engine;
  private EventReactor<ByteBuffer> reactor2;
  private PipeTransport memoryTransport;
  private int messageCount = Byte.MAX_VALUE;
  private byte[][] messages;
  private String userCredentials = "User1";
  private final MessageHeaderEncoder messageHeaderEncoder = new MessageHeaderEncoder();
  private MutableDirectBuffer mutableBuffer = new UnsafeBuffer(new byte[0]);
  private MessageFrameEncoder frameEncoder;

  @Before
  public void setUp() throws Exception {

    SimpleDirectory directory = new SimpleDirectory();
    engine =
        Engine.builder().withAuthenticator(new SimpleAuthenticator().withDirectory(directory))
            .build();
    engine.open();
    engine.getReactor();

    reactor2 =
        EventReactor.builder().withDispatcher(new ByteBufferDispatcher())
            .withPayloadAllocator(new ByteBufferPayload(2048)).build();

    reactor2.open().get();

    directory.add(userCredentials);

    memoryTransport = new PipeTransport(engine.getIOReactor().getSelector());

    messages = new byte[messageCount][];
    for (int i = 0; i < messageCount; ++i) {
      messages[i] = new byte[i];
      Arrays.fill(messages[i], (byte) i);
    }
  }

  @After
  public void tearDown() throws Exception {
    engine.close();
    reactor2.close();
  }

  @Test
  public void idempotent() throws Exception {
    frameEncoder = new MessageLengthFrameEncoder();
    Transport serverTransport = memoryTransport.getServerTransport();
    TransportDecorator nonFifoServerTransport = new TransportDecorator(serverTransport, false);
    TestReceiver serverReceiver = new TestReceiver();

    FixpSession serverSession =
        FixpSession
            .builder()
            .withReactor(engine.getReactor())
            .withTransport(nonFifoServerTransport)
            .withBufferSupplier(
                new SingleBufferSupplier(ByteBuffer.allocate(16 * 1024).order(
                    ByteOrder.nativeOrder()))).withMessageConsumer(serverReceiver)
            .withMessageFrameEncoder(new MessageLengthFrameEncoder())
            .withOutboundFlow(FlowType.Idempotent).withOutboundKeepaliveInterval(10000).asServer()
            .build();

    serverSession.open();

    Transport clientTransport = memoryTransport.getClientTransport();
    TransportDecorator nonFifoClientTransport = new TransportDecorator(clientTransport, false);
    TestReceiver clientReceiver = new TestReceiver();
    UUID sessionId = SessionId.generateUUID();

    FixpSession clientSession =
        FixpSession
            .builder()
            .withReactor(reactor2)
            .withTransport(nonFifoClientTransport)
            .withBufferSupplier(
                new SingleBufferSupplier(ByteBuffer.allocate(16 * 1024).order(
                    ByteOrder.nativeOrder()))).withMessageConsumer(clientReceiver)
            .withOutboundFlow(FlowType.Idempotent).withSessionId(sessionId)
            .withMessageFrameEncoder(new MessageLengthFrameEncoder())
            .withClientCredentials(userCredentials.getBytes()).withOutboundKeepaliveInterval(10000)
            .build();

    SessionReadyFuture future = new SessionReadyFuture(sessionId, reactor2);
    clientSession.open();
    future.get(3000, TimeUnit.MILLISECONDS);

    ByteBuffer buf = ByteBuffer.allocate(8096).order(ByteOrder.nativeOrder());
    int bytesSent = 0;
    for (int i = 0; i < messageCount; ++i) {
      buf.clear();
      bytesSent += encodeApplicationMessageWithFrame(buf, messages[i]);
      clientSession.send(buf);
    }

    try {
      Thread.sleep(1000);
    } catch (InterruptedException e) {

    }
    assertEquals(messageCount, serverReceiver.getMsgsReceived());

    SessionTerminatedFuture future2 = new SessionTerminatedFuture(sessionId, reactor2);
    clientSession.close();
    future.get(1000, TimeUnit.MILLISECONDS);
  }

  @Test
  public void unsequenced() throws Exception {
    frameEncoder = new MessageLengthFrameEncoder();
    Transport serverTransport = memoryTransport.getServerTransport();
    TestReceiver serverReceiver = new TestReceiver();

    FixpSession serverSession =
        FixpSession
            .builder()
            .withReactor(engine.getReactor())
            .withTransport(serverTransport)
            .withBufferSupplier(
                new SingleBufferSupplier(ByteBuffer.allocate(16 * 1024).order(
                    ByteOrder.nativeOrder()))).withMessageConsumer(serverReceiver)
            .withMessageFrameEncoder(new MessageLengthFrameEncoder())
            .withOutboundFlow(FlowType.Unsequenced).withOutboundKeepaliveInterval(10000).asServer()
            .build();

    serverSession.open();

    Transport clientTransport = memoryTransport.getClientTransport();
    TestReceiver clientReceiver = new TestReceiver();
    UUID sessionId = SessionId.generateUUID();

    FixpSession clientSession =
        FixpSession
            .builder()
            .withReactor(reactor2)
            .withTransport(clientTransport)
            .withBufferSupplier(
                new SingleBufferSupplier(ByteBuffer.allocate(16 * 1024).order(
                    ByteOrder.nativeOrder()))).withMessageConsumer(clientReceiver)
            .withOutboundFlow(FlowType.Unsequenced).withSessionId(sessionId)
            .withMessageFrameEncoder(new MessageLengthFrameEncoder())
            .withClientCredentials(userCredentials.getBytes()).withOutboundKeepaliveInterval(10000)
            .build();

    SessionReadyFuture future = new SessionReadyFuture(sessionId, reactor2);
    clientSession.open();
    future.get(3000, TimeUnit.MILLISECONDS);

    ByteBuffer buf = ByteBuffer.allocate(8096).order(ByteOrder.nativeOrder());
    int bytesSent = 0;
    for (int i = 0; i < messageCount; ++i) {
      buf.clear();
      bytesSent += encodeApplicationMessageWithFrame(buf, messages[i]);
      clientSession.send(buf);
    }

    try {
      Thread.sleep(1000);
    } catch (InterruptedException e) {

    }
    assertEquals(messageCount, serverReceiver.getMsgsReceived());

    SessionTerminatedFuture future2 = new SessionTerminatedFuture(sessionId, reactor2);
    clientSession.close();
    future.get(1000, TimeUnit.MILLISECONDS);
  }
  
  @Test
  public void withSofh() throws Exception {
    frameEncoder = new SofhFrameEncoder();
    Transport serverTransport = memoryTransport.getServerTransport();
    TransportDecorator nonFifoServerTransport = new TransportDecorator(serverTransport, false);
    TestReceiver serverReceiver = new TestReceiver();

    FixpSession serverSession =
        FixpSession
            .builder()
            .withReactor(engine.getReactor())
            .withTransport(nonFifoServerTransport)
            .withBufferSupplier(
                new SingleBufferSupplier(ByteBuffer.allocate(16 * 1024).order(
                    ByteOrder.nativeOrder()))).withMessageConsumer(serverReceiver)
            .withMessageFramer(new SofhFrameSpliterator())
            .withMessageFrameEncoder(new SofhFrameEncoder())
            .withOutboundFlow(FlowType.Idempotent).withOutboundKeepaliveInterval(10000).asServer()
            .build();

    serverSession.open();

    Transport clientTransport = memoryTransport.getClientTransport();
    TransportDecorator nonFifoClientTransport = new TransportDecorator(clientTransport, false);
    TestReceiver clientReceiver = new TestReceiver();
    UUID sessionId = SessionId.generateUUID();

    FixpSession clientSession =
        FixpSession
            .builder()
            .withReactor(reactor2)
            .withTransport(nonFifoClientTransport)
            .withBufferSupplier(
                new SingleBufferSupplier(ByteBuffer.allocate(16 * 1024).order(
                    ByteOrder.nativeOrder()))).withMessageConsumer(clientReceiver)
            .withMessageFramer(new SofhFrameSpliterator())
            .withMessageFrameEncoder(new SofhFrameEncoder())
            .withOutboundFlow(FlowType.Idempotent).withSessionId(sessionId)
            .withClientCredentials(userCredentials.getBytes()).withOutboundKeepaliveInterval(10000)
            .build();

    SessionReadyFuture future = new SessionReadyFuture(sessionId, reactor2);
    clientSession.open();
    future.get(3000, TimeUnit.MILLISECONDS);

    ByteBuffer buf = ByteBuffer.allocate(8096).order(ByteOrder.nativeOrder());
    int bytesSent = 0;
    for (int i = 0; i < messageCount; ++i) {
      buf.clear();
      bytesSent += encodeApplicationMessageWithFrame(buf, messages[i]);
      clientSession.send(buf);
    }

    try {
      Thread.sleep(1000);
    } catch (InterruptedException e) {

    }
    assertEquals(messageCount, serverReceiver.getMsgsReceived());

    SessionTerminatedFuture future2 = new SessionTerminatedFuture(sessionId, reactor2);
    clientSession.close();
    future.get(1000, TimeUnit.MILLISECONDS);
  }
  
  private long encodeApplicationMessageWithFrame(ByteBuffer buffer, byte[] message) {
    int offset = 0;
    mutableBuffer.wrap(buffer);
    frameEncoder.wrap(buffer, offset).encodeFrameHeader();
    offset += frameEncoder.getHeaderLength();
    messageHeaderEncoder.wrap(mutableBuffer, offset);
    messageHeaderEncoder.blockLength(message.length)
        .templateId(templateId).schemaId(schemaId)
        .version(schemaVersion);
    offset += MessageHeaderEncoder.ENCODED_LENGTH; 
    buffer.position(offset);
    buffer.put(message, 0, message.length);
    frameEncoder.setMessageLength(message.length + MessageHeaderEncoder.ENCODED_LENGTH);
    frameEncoder.encodeFrameTrailer();
    return frameEncoder.getEncodedLength();
  }
}
