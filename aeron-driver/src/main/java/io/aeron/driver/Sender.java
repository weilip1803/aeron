/*
 * Copyright 2014 - 2016 Real Logic Ltd.
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
package io.aeron.driver;

import io.aeron.driver.media.ControlTransportPoller;
import io.aeron.driver.media.SendChannelEndpoint;
import io.aeron.driver.cmd.SenderCmd;
import org.agrona.collections.ArrayUtil;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.status.AtomicCounter;
import org.agrona.concurrent.NanoClock;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

import java.util.function.Consumer;

import static io.aeron.driver.status.SystemCounterDescriptor.BYTES_SENT;

/**
 * Agent that iterates over networkPublications for sending them to registered subscribers.
 */
public class Sender implements Agent, Consumer<SenderCmd>
{
    private static final NetworkPublication[] EMPTY_PUBLICATIONS = new NetworkPublication[0];

    private final ControlTransportPoller controlTransportPoller;
    private final OneToOneConcurrentArrayQueue<SenderCmd> commandQueue;
    private final DriverConductorProxy conductorProxy;
    private final AtomicCounter totalBytesSent;
    private final NanoClock nanoClock;

    private NetworkPublication[] networkPublications = EMPTY_PUBLICATIONS;
    private int roundRobinIndex = 0;

    public Sender(final MediaDriver.Context ctx)
    {
        this.controlTransportPoller = ctx.controlTransportPoller();
        this.commandQueue = ctx.senderCommandQueue();
        this.conductorProxy = ctx.fromSenderDriverConductorProxy();
        this.totalBytesSent = ctx.systemCounters().get(BYTES_SENT);
        this.nanoClock = ctx.nanoClock();
    }

    public int doWork()
    {
        final int workCount = commandQueue.drain(this);
        final int bytesSent = doSend(nanoClock.nanoTime());
        final int bytesReceived = controlTransportPoller.pollTransports();

        return workCount + bytesSent + bytesReceived;
    }

    public String roleName()
    {
        return "sender";
    }

    public void onRegisterSendChannelEndpoint(final SendChannelEndpoint channelEndpoint)
    {
        channelEndpoint.openChannel();
        channelEndpoint.registerForRead(controlTransportPoller);
    }

    public void onCloseSendChannelEndpoint(final SendChannelEndpoint channelEndpoint)
    {
        channelEndpoint.close();
    }

    public void onNewNetworkPublication(final NetworkPublication publication)
    {
        networkPublications = ArrayUtil.add(networkPublications, publication);
        publication.sendChannelEndpoint().registerForSend(publication);
    }

    public void onRemoveNetworkPublication(final NetworkPublication publication)
    {
        networkPublications = ArrayUtil.remove(networkPublications, publication);
        publication.sendChannelEndpoint().unregisterForSend(publication);
        conductorProxy.closeNetworkPublication(publication);
    }

    public void accept(final SenderCmd cmd)
    {
        cmd.execute(this);
    }

    private int doSend(final long now)
    {
        int bytesSent = 0;
        final NetworkPublication[] publications = this.networkPublications;
        final int length = publications.length;

        int startingIndex = roundRobinIndex++;
        if (startingIndex >= length)
        {
            roundRobinIndex = startingIndex = 0;
        }

        for (int i = startingIndex; i < length; i++)
        {
            bytesSent += publications[i].send(now);
        }

        for (int i = 0; i < startingIndex; i++)
        {
            bytesSent += publications[i].send(now);
        }

        totalBytesSent.addOrdered(bytesSent);

        return bytesSent;
    }
}
