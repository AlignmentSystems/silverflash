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

package org.fixtrading.silverflash.fixp.flow;

import static org.fixtrading.silverflash.fixp.SessionEventTopics.FromSessionEventType.SESSION_SUSPENDED;
import static org.fixtrading.silverflash.fixp.SessionEventTopics.SessionEventType.HEARTBEAT;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.fixtrading.silverflash.Receiver;
import org.fixtrading.silverflash.fixp.SessionEventTopics;
import org.fixtrading.silverflash.fixp.messages.MessageEncoder.TerminateEncoder;
import org.fixtrading.silverflash.fixp.messages.MessageType;
import org.fixtrading.silverflash.fixp.messages.TerminationCode;
import org.fixtrading.silverflash.reactor.Subscription;
import org.fixtrading.silverflash.reactor.TimerSchedule;
import org.fixtrading.silverflash.reactor.Topic;

/**
 * Sends messages on an idempotent flow on a Transport that guarantees FIFO delivery. The
 * implementation sends a Sequence message only at startup and for heartbeats.
 * 
 * @author Don Mendelson
 *
 */
public class IdempotentFlowSender extends AbstractFlow implements FlowSender, MutableSequence {

  @SuppressWarnings("rawtypes")
  public static class Builder<T extends IdempotentFlowSender, B extends FlowBuilder>
      extends AbstractFlow.Builder {

    public IdempotentFlowSender build() {
      return new IdempotentFlowSender(this);
    }
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  public static  Builder<IdempotentFlowSender, FlowBuilder> builder() {
    return new Builder();
  }

  private final Receiver heartbeatEvent = new Receiver() {

    public void accept(ByteBuffer t) {
      try {
        sendHeartbeat();
      } catch (IOException e) {
        try {
          sendEndOfStream();
        } catch (IOException e1) {

        }
        Topic terminatedTopic = SessionEventTopics.getTopic(sessionId, SESSION_SUSPENDED);
        reactor.post(terminatedTopic, t);
      }
    }

  };

  private final TimerSchedule heartbeatSchedule;
  private final Subscription heartbeatSubscription;
  private final AtomicBoolean isHeartbeatDue = new AtomicBoolean(true);
  private static final ByteBuffer[] EMPTY = new ByteBuffer[0];
  private final ByteBuffer[] one = new ByteBuffer[1];
  private final AtomicBoolean criticalSection = new AtomicBoolean();

  protected IdempotentFlowSender(@SuppressWarnings("rawtypes") Builder builder) {
    super(builder);

    final Topic heartbeatTopic = SessionEventTopics.getTopic(sessionId, HEARTBEAT);
    heartbeatSubscription = reactor.subscribe(heartbeatTopic, heartbeatEvent);
    heartbeatSchedule = reactor.postAtInterval(heartbeatTopic, ByteBuffer.allocate(0),
        keepaliveInterval);
  }

  @Override
  public long send(ByteBuffer message) throws IOException {
    Objects.requireNonNull(message);
    one[0] = message;
    return send(one);
  }

  public long send(ByteBuffer[] messages) throws IOException {
    Objects.requireNonNull(messages);
    while (!criticalSection.compareAndSet(false, true)) {
      Thread.yield();
    }
    try {
      transport.write(sequencer.apply(messages));
      isHeartbeatDue.set(false);
      return sequencer.getNextSeqNo();
    } finally {
      criticalSection.compareAndSet(true, false);
    }
  }

  public void sendEndOfStream() throws IOException {
    heartbeatSchedule.cancel();
    heartbeatSubscription.unsubscribe();

    final ByteBuffer terminateBuffer = ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder());
    final TerminateEncoder terminateEncoder = (TerminateEncoder) messageEncoder
        .wrap(terminateBuffer, 0, MessageType.TERMINATE);
    terminateEncoder.setSessionId(uuidAsBytes);
    terminateEncoder.setCode(TerminationCode.FINISHED);
    terminateEncoder.setReasonNull();
    transport.write(terminateBuffer);
  }

  public void sendHeartbeat() throws IOException {
    if (isHeartbeatDue.getAndSet(true)) {
      send(EMPTY);
    }
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.fixtrading.silverflash.Sequenced#getNextSeqNo()
   */
  public long getNextSeqNo() {
    return sequencer.getNextSeqNo();
  }

  /**
   * Alters sequence for test purposes only!
   */
  public void setNextSeqNo(long nextSeqNo) {
    ((MutableSequence) (this.sequencer)).setNextSeqNo(nextSeqNo);
  }
}
