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
package io.fixprotocol.silverflash.transport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import io.fixprotocol.silverflash.buffer.BufferSupplier;
import io.fixprotocol.silverflash.buffer.SingleBufferSupplier;
import io.fixprotocol.silverflash.transport.Dispatcher;
import io.fixprotocol.silverflash.transport.SharedMemoryTransport;
import io.fixprotocol.silverflash.transport.Transport;
import io.fixprotocol.silverflash.transport.TransportConsumer;
import io.fixprotocol.silverflash.util.platform.AffinityThreadFactory;

@State(Scope.Benchmark)
public class MemoryTransportBenchmark {

  private class NoopConsumer implements TransportConsumer {

    @Override
    public void accept(ByteBuffer t) {
      // do nothing

    }

    @Override
    public void connected() {
      // do nothing

    }

    @Override
    public void disconnected() {
      // do nothing

    }

  }

  private class Reflector implements TransportConsumer {

    private final Transport transport;

    public Reflector(Transport transport) {
      this.transport = transport;
    }

    @Override
    public void accept(ByteBuffer inbound) {
      inbound.rewind();
      try {
        transport.write(inbound);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void connected() {
      // do nothing

    }

    @Override
    public void disconnected() {
      // do nothing

    }

  }

  private static AffinityThreadFactory threadFactory;

  @Param({"128", "256", "1024"})
  public int bufferSize;

  private BufferSupplier clientBuffers;
  private Transport clientTransport;
  private byte[] message;
  private BufferSupplier serverBuffers;
  private Transport serverTransport;
  private ByteBuffer src;

  private Dispatcher serverDispatcher;

  private Dispatcher clientDispatcher;

  @TearDown
  public void detroyTestEnvironment() {
    clientTransport.close();
    serverTransport.close();
  }

  @Setup
  public void initTestEnvironment() throws IOException, InterruptedException, ExecutionException {
    threadFactory = new AffinityThreadFactory(true, true, "benchmark");
    serverBuffers =
        new SingleBufferSupplier(ByteBuffer.allocate(bufferSize).order(ByteOrder.nativeOrder()));
    serverDispatcher = new Dispatcher(threadFactory);
    serverTransport = new SharedMemoryTransport(false, true, 1, serverDispatcher);
    serverTransport.open(serverBuffers, new Reflector(serverTransport));
    clientBuffers =
        new SingleBufferSupplier(ByteBuffer.allocate(bufferSize).order(ByteOrder.nativeOrder()));
    clientDispatcher = new Dispatcher(threadFactory);
    clientTransport = new SharedMemoryTransport(true, true, 1, clientDispatcher);
    clientTransport.open(clientBuffers, new NoopConsumer());
    message = new byte[bufferSize];
    Arrays.fill(message, (byte) 'x');
    src = ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.nativeOrder());
  }

  @Benchmark
  public void inject() throws IOException {
    src.clear();
    src.put(message);
    clientTransport.write(src);
  }

}
