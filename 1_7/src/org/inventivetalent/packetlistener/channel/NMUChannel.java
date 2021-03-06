/*
 * Copyright 2015-2016 inventivetalent. All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without modification, are
 *  permitted provided that the following conditions are met:
 *
 *     1. Redistributions of source code must retain the above copyright notice, this list of
 *        conditions and the following disclaimer.
 *
 *     2. Redistributions in binary form must reproduce the above copyright notice, this list
 *        of conditions and the following disclaimer in the documentation and/or other materials
 *        provided with the distribution.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE AUTHOR ''AS IS'' AND ANY EXPRESS OR IMPLIED
 *  WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 *  FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE AUTHOR OR
 *  CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 *  CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 *  SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 *  ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 *  NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 *  ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 *  The views and conclusions contained in the software and documentation are those of the
 *  authors and contributors and should not be interpreted as representing official policies,
 *  either expressed or implied, of anybody else.
 */

package org.inventivetalent.packetlistener.channel;

import net.minecraft.util.io.netty.channel.ChannelHandlerContext;
import net.minecraft.util.io.netty.channel.ChannelPromise;
import org.bukkit.entity.Player;
import org.inventivetalent.packetlistener.Cancellable;
import org.inventivetalent.packetlistener.IPacketListener;
import org.inventivetalent.reflection.minecraft.Minecraft;

import java.lang.reflect.Field;
import java.net.SocketAddress;
import java.util.ArrayList;

public class NMUChannel extends ChannelAbstract {

	private static final Field channelField = networkManagerFieldResolver.resolveByFirstTypeSilent(net.minecraft.util.io.netty.channel.Channel.class);

	public NMUChannel(IPacketListener iPacketListener) {
		super(iPacketListener);
	}

	@Override
	public void addChannel(final Player player) {
		try {
			final net.minecraft.util.io.netty.channel.Channel channel = getChannel(player);
			addChannelExecutor.execute(new Runnable() {
				@Override
				public void run() {
					try {
						channel.pipeline().addBefore(KEY_HANDLER, KEY_PLAYER, new ChannelHandler(player));
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				}
			});
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException("Failed to add channel for " + player, e);
		}
	}

	@Override
	public void removeChannel(Player player) {
		try {
			final net.minecraft.util.io.netty.channel.Channel channel = getChannel(player);
			removeChannelExecutor.execute(new Runnable() {
				@Override
				public void run() {
					try {
						if (channel.pipeline().get(KEY_PLAYER) != null) {
							channel.pipeline().remove(KEY_PLAYER);
						}
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				}
			});
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException("Failed to remove channel for " + player, e);
		}
	}

	net.minecraft.util.io.netty.channel.Channel getChannel(Player player) throws ReflectiveOperationException {
		final Object handle = Minecraft.getHandle(player);
		final Object connection = playerConnection.get(handle);
		return (net.minecraft.util.io.netty.channel.Channel) channelField.get(networkManager.get(connection));
	}

	@Override
	public IListenerList newListenerList() {
		return new ListenerList<>();
	}

	class ListenerList<E> extends ArrayList<E> implements IListenerList<E> {

		@Override
		public boolean add(E paramE) {
			try {
				final E a = paramE;
				addChannelExecutor.execute(new Runnable() {

					@Override
					public void run() {
						try {
							net.minecraft.util.io.netty.channel.Channel channel = null;
							while (channel == null) {
								channel = (net.minecraft.util.io.netty.channel.Channel) channelField.get(a);
							}
							if (channel.pipeline().get(KEY_SERVER) == null) {
								channel.pipeline().addBefore(KEY_HANDLER, KEY_SERVER, new ChannelHandler(new NMUChannelWrapper(channel)));
							}
						} catch (Exception e) {
						}
					}
				});
			} catch (Exception e) {
			}
			return super.add(paramE);
		}

		@Override
		public boolean remove(Object arg0) {
			try {
				final Object a = arg0;
				removeChannelExecutor.execute(new Runnable() {
					@Override
					public void run() {
						try {
							net.minecraft.util.io.netty.channel.Channel channel = null;
							while (channel == null) {
								channel = (net.minecraft.util.io.netty.channel.Channel) channelField.get(a);
							}
							channel.pipeline().remove(KEY_SERVER);
						} catch (Exception e) {
						}
					}
				});
			} catch (Exception e) {
			}
			return super.remove(arg0);
		}
	}

	class ChannelHandler extends net.minecraft.util.io.netty.channel.ChannelDuplexHandler implements IChannelHandler {

		private Object owner;

		public ChannelHandler(Player player) {
			this.owner = player;
		}

		public ChannelHandler(ChannelWrapper channelWrapper) {
			this.owner = channelWrapper;
		}

		@Override
		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
			Cancellable cancellable = new Cancellable();
			Object pckt = msg;
			if (Packet.isAssignableFrom(msg.getClass())) {
				pckt = onPacketSend(this.owner, msg, cancellable);
			}
			if (cancellable.isCancelled()) { return; }
			super.write(ctx, pckt, promise);
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
			Cancellable cancellable = new Cancellable();
			Object pckt = msg;
			if (Packet.isAssignableFrom(msg.getClass())) {
				pckt = onPacketReceive(this.owner, msg, cancellable);
			}
			if (cancellable.isCancelled()) { return; }
			super.channelRead(ctx, pckt);
		}

		@Override
		public String toString() {
			return "NMUChannel$ChannelHandler@" + hashCode() + " (" + this.owner + ")";
		}
	}

	class NMUChannelWrapper extends ChannelWrapper<net.minecraft.util.io.netty.channel.Channel> implements IChannelWrapper {

		public NMUChannelWrapper(net.minecraft.util.io.netty.channel.Channel channel) {
			super(channel);
		}

		@Override
		public SocketAddress getRemoteAddress() {
			return this.channel().remoteAddress();
		}

		@Override
		public SocketAddress getLocalAddress() {
			return this.channel().localAddress();
		}
	}
}
