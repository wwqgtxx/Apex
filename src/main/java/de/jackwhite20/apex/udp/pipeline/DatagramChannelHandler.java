/*
 * Copyright (c) 2016 "JackWhite20"
 *
 * This file is part of Apex.
 *
 * Apex is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.jackwhite20.apex.udp.pipeline;

import de.jackwhite20.apex.Apex;
import de.jackwhite20.apex.udp.ApexDatagram;
import de.jackwhite20.apex.util.BackendInfo;
import de.jackwhite20.apex.util.ChannelUtil;
import de.jackwhite20.apex.util.PipelineUtils;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * Created by JackWhite20 on 05.11.2016.
 */
public class DatagramChannelHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    private Logger logger = LoggerFactory.getLogger(DatagramChannelHandler.class);

    @Override
    protected void messageReceived(ChannelHandlerContext ctx, DatagramPacket datagramPacket) throws Exception {

        BackendInfo backendInfo = ApexDatagram.getBalancingStrategy().selectBackend("", 0);

        if (backendInfo == null) {
            // Gracefully close the channel
            ctx.close();

            logger.error("Unable to select a backend server. All down?");
            return;
        }

        // Only copy if there is at least one backend server
        ByteBuf copy = datagramPacket.content().copy();

        Bootstrap bootstrap = new Bootstrap()
                .channel(PipelineUtils.getDatagramChannel())
                .handler(new DatagramDownstreamHandler(ctx.channel(), datagramPacket.sender()))
                .group(ctx.channel().eventLoop());

        ChannelFuture channelFuture = bootstrap.bind(0);

        // Add the traffic shaping handler to the channel pipeline
        GlobalTrafficShapingHandler trafficShapingHandler = Apex.getInstance().getTrafficShapingHandler();
        if (trafficShapingHandler != null) {
            // The handler needs to be the first handler in the pipeline
            channelFuture.channel().pipeline().addFirst(trafficShapingHandler);
        }

        channelFuture.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture channelFuture) throws Exception {

                if (channelFuture.isSuccess()) {
                    channelFuture.channel().writeAndFlush(new DatagramPacket(copy.retain(), new InetSocketAddress(backendInfo.getHost(), backendInfo.getPort())));
                } else {
                    ChannelUtil.close(ctx.channel());
                }
            }
        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {

        ChannelUtil.close(ctx.channel());

        if (!(cause instanceof IOException)) {
            logger.error(cause.getMessage(), cause);
        }
    }
}