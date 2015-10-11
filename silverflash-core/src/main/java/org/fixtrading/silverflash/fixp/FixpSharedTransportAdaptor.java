/**
 *    Copyright 2015 FIX Protocol Ltd
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

package org.fixtrading.silverflash.fixp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.fixtrading.silverflash.MessageConsumer;
import org.fixtrading.silverflash.buffer.BufferSupplier;
import org.fixtrading.silverflash.buffer.SingleBufferSupplier;
import org.fixtrading.silverflash.fixp.frame.FixpWithMessageLengthFrameSpliterator;
import org.fixtrading.silverflash.fixp.messages.FlowType;
import org.fixtrading.silverflash.fixp.messages.MessageSessionIdentifier;
import org.fixtrading.silverflash.reactor.EventReactor;
import org.fixtrading.silverflash.transport.IdentifiableTransportConsumer;
import org.fixtrading.silverflash.transport.SharedTransportDecorator;
import org.fixtrading.silverflash.transport.Transport;
import org.fixtrading.silverflash.transport.TransportConsumer;

/**
 * Wraps a shared Transport for multiple sessions
 * <p>
 * Received application messages are routed to sessions by session ID (UUID) carried by FIXP session
 * messages.
 * <p>
 * This class may be used with sessions in either client or server mode. A new FixpSession in server
 * mode is created on the arrival of a Negotiate message on the Transport. New sessions are
 * configured to multiplex (sends a Context message when context switching).
 * 
 * 
 * @author Don Mendelson
 *
 */
public class FixpSharedTransportAdaptor extends SharedTransportDecorator<UUID> {

  public static class Builder extends
      SharedTransportDecorator.Builder<UUID, FixpSharedTransportAdaptor, Builder> {

    public Supplier<MessageConsumer<UUID>> consumerSupplier;
    public FlowType flowType;
    public EventReactor<ByteBuffer> reactor;

    protected Builder() {
      super();
      this.withMessageFramer(new FixpWithMessageLengthFrameSpliterator());
      this.withMessageIdentifer(new MessageSessionIdentifier());
    }

    /**
     * Build a new FixpSharedTransportAdaptor object
     * 
     * @return a new adaptor
     */
    public FixpSharedTransportAdaptor build() {
      return new FixpSharedTransportAdaptor(this);
    }

    public Builder withMessageConsumerSupplier(Supplier<MessageConsumer<UUID>> consumerSupplier) {
      this.consumerSupplier = consumerSupplier;
      return this;
    }

    public Builder withFlowType(FlowType flowType) {
      this.flowType = flowType;
      return this;
    }

    public Builder withReactor(EventReactor<ByteBuffer> reactor) {
      this.reactor = reactor;
      return this;
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  private final Supplier<MessageConsumer<UUID>> consumerSupplier;
  private final FlowType flowType;

  private final Consumer<UUID> newSessionConsumer = new Consumer<UUID>() {

    @SuppressWarnings("unchecked")
    public void accept(UUID sessionId) {
      FixpSession session = FixpSession.builder().withReactor(reactor)
          .withTransport(FixpSharedTransportAdaptor.this, true)
          .withBufferSupplier(new SingleBufferSupplier(
              ByteBuffer.allocate(16 * 1024).order(ByteOrder.nativeOrder())))
          .withMessageConsumer(consumerSupplier.get()).withOutboundFlow(flowType)
          .withSessionId(sessionId).asServer().build();

      session.open().handle((s, error) -> {
        if (error instanceof Exception) {
          exceptionConsumer.accept((Exception) error);
        }
        return s;
      });
    }

  };

  private final EventReactor<ByteBuffer> reactor;

  private final Consumer<? super ByteBuffer> router = new Consumer<ByteBuffer>() {

    private UUID lastId;

    /**
     * Gets session ID from message, looks up session and invokes session consumer. If a message
     * doesn't contain a session ID, then it continues to send to the last identified session until
     * the context changes.
     */
    public void accept(ByteBuffer buffer) {
      UUID id = getMessageIdentifier().apply(buffer);
      if (id != null) {
        lastId = id;
      }
      if (lastId != null) {
        ConsumerWrapper wrapper = getConsumerWrapper(lastId);

        if (wrapper != null) {
          wrapper.getConsumer().accept(buffer);
        } else {
          wrapper = uninitialized.poll();
          if (wrapper != null) {
            final TransportConsumer consumer = wrapper.getConsumer();
            try {
              open(wrapper.getBuffers(), consumer, id);
              consumer.accept(buffer);
            } catch (IOException | InterruptedException | ExecutionException e) {
              exceptionConsumer.accept(e);
            }
          } else {
            System.out.println("Unknown sesion ID and no uninitialized session available");
          }
        }
      }
    }

  };

  private final Queue<ConsumerWrapper> uninitialized = new ConcurrentLinkedQueue<>();

  protected FixpSharedTransportAdaptor(Builder builder) {
    super(builder);
    this.reactor = builder.reactor;
    this.flowType = builder.flowType;
    this.consumerSupplier = builder.consumerSupplier;
    if (this.getMessageIdentifier() instanceof MessageSessionIdentifier) {
      this.messageIdentifier =
          new MessageSessionIdentifier().withNewSessionConsumer(newSessionConsumer);
    }
  }

  @Override
  public CompletableFuture<? extends Transport> open(BufferSupplier buffers, IdentifiableTransportConsumer<UUID> consumer) {
    if (consumer.getSessionId().equals(SessionId.EMPTY)) {
      uninitialized.add(new ConsumerWrapper(buffers, consumer));
      CompletableFuture<? extends Transport> future = openUnderlyingTransport();
      consumer.connected();
      return future;
    } else {
      return super.open(buffers, consumer);
    }
  }

  protected Consumer<? super ByteBuffer> getRouter() {
    return router;
  }

}
