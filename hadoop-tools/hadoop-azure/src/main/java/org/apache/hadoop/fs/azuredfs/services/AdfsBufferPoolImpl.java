/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.fs.azuredfs.services;

import com.google.inject.Singleton;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.fs.azuredfs.contracts.services.AdfsBufferPool;

/**
 * File System service to provider AzureDistributedFilesystem client.
 */
@Singleton
@InterfaceAudience.Private
@InterfaceStability.Evolving
final class AdfsBufferPoolImpl implements AdfsBufferPool {
  private final PooledByteBufAllocator pooledByteBufAllocator;

  public AdfsBufferPoolImpl() {
    this.pooledByteBufAllocator = PooledByteBufAllocator.DEFAULT;
  }

  @Override
  public ByteBuf getByteBuffer(final int bufferSize) {
    ByteBuf buffer = this.pooledByteBufAllocator.heapBuffer(bufferSize);
    buffer.retain();
    return buffer;
  }

  @Override
  public synchronized boolean releaseByteBuffer(final ByteBuf byteBuf) {
    while (byteBuf.refCnt() != 0) {
      byteBuf.release();
    }

    return true;
  }
}