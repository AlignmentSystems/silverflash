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

package io.fixprotocol.silverflash.reactor.bridge;

import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.fixprotocol.silverflash.Receiver;
import io.fixprotocol.silverflash.fixp.Engine;
import io.fixprotocol.silverflash.reactor.ByteBufferPayload;
import io.fixprotocol.silverflash.reactor.Subscription;
import io.fixprotocol.silverflash.reactor.Topic;
import io.fixprotocol.silverflash.reactor.Topics;
import io.fixprotocol.silverflash.reactor.bridge.EventReactorWithBridge;
import io.fixprotocol.silverflash.transport.PipeTransport;

/**
 * @author Don Mendelson
 *
 */
public class EventReactorWithBridgeTest {

  int received = 0;
  private Engine engine;
  private PipeTransport memoryTransport;
  private EventReactorWithBridge reactor1;
  private EventReactorWithBridge reactor2;

  /**
   * @throws java.lang.Exception
   */
  @Before
  public void setUp() throws Exception {
    received = 0;

    engine = Engine.builder().build();
    engine.open();
    memoryTransport = new PipeTransport(engine.getIOReactor().getSelector());

    reactor1 =
        EventReactorWithBridge.builder().withTransport(memoryTransport.getClientTransport())
            .withPayloadAllocator(new ByteBufferPayload(2048)).build();
    reactor2 =
        EventReactorWithBridge.builder().withTransport(memoryTransport.getServerTransport())
            .withPayloadAllocator(new ByteBufferPayload(2048)).build();

    // reactor1.setTrace(true, "reactor1");
    // reactor2.setTrace(true, "reactor2");
    reactor1.open();
    reactor2.open();
  }

  @After
  public void tearDown() throws Exception {
    reactor1.close();
    reactor2.close();
  }

  @Test
  public void testForward() throws Exception {
    Topic topic = Topics.getTopic("test1");

    reactor1.forward(topic);

    Receiver receiver2 = new Receiver() {

      public void accept(ByteBuffer buf) {
        received++;
      }
    };
    Subscription subscription2 = reactor2.subscribe(topic, receiver2);

    ByteBuffer msg = ByteBuffer.allocate(1024);
    msg.put("This is a test".getBytes());
    reactor1.post(topic, msg);

    Thread.sleep(500L);
    assertEquals(1, received);
  }

}
